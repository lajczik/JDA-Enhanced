/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.internal.utils.compress;

import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.nio.ByteBuffer;

/**
 * Zero-copy off-heap streaming Zstandard decoder for Discord Gateway websocket.
 * Uses zstd-jni direct buffer decompression without intermediate byte[] or String allocations.
 * <p>
 * Note: A continuous zstd stream context is maintained across frames for the lifetime
 * of the connection, matching Discord Gateway's zstd-stream specification.
 */
public class ZstdStreamDecoder extends ChannelInboundHandlerAdapter {
    private static final int DEFAULT_INITIAL_CAPACITY = 2048;
    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_INITIAL_ALLOCATION = 2 * 1024 * 1024; // 2 MB initial allocation ceiling
    private static final int MAX_COMPRESSED_BUFFER_SIZE = 32 * 1024 * 1024; // 32 MB guard against oversized frames
    private static final int MAX_DECOMPRESSED_SIZE = 64 * 1024 * 1024; // 64 MB guard against compression bombs

    private static final ByteBuffer EMPTY_DIRECT_BUFFER = ByteBuffer.allocateDirect(0);

    private final int initialCapacity;
    private final ZstdDecompressCtx decompressCtx = new ZstdDecompressCtx();

    public ZstdStreamDecoder() {
        this(DEFAULT_INITIAL_CAPACITY);
    }

    public ZstdStreamDecoder(int initialCapacity) {
        this.initialCapacity = initialCapacity;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof BinaryWebSocketFrame frame) {
            ByteBuf content = frame.content();
            if (!content.isReadable()) {
                frame.release();
                return;
            }

            int compressedLen = content.readableBytes();
            if (compressedLen > MAX_COMPRESSED_BUFFER_SIZE) {
                reset();
                frame.release();
                ctx.close();
                throw new IllegalStateException(
                        "Compressed frame exceeded maximum limit of " + MAX_COMPRESSED_BUFFER_SIZE + " bytes");
            }

            ByteBuffer[] srcBuffers;
            ByteBuf directIn = null;

            if (content.isDirect()) {
                if (content.nioBufferCount() == 1) {
                    srcBuffers = new ByteBuffer[] {content.nioBuffer()};
                } else {
                    // CompositeByteBuf or multi-chunk direct buffer: zero-copy access to underlying slices
                    srcBuffers = content.nioBuffers();
                }
            } else {
                // If heap buffer, copy to direct memory once as required by JNI
                directIn = ctx.alloc().directBuffer(compressedLen);
                directIn.writeBytes(content);
                srcBuffers = new ByteBuffer[] {directIn.nioBuffer()};
            }

            try {
                // Estimate decompressed size (JSON typically expands ~3-6x with zstd).
                // Avoid tiny clamp limits and prevent IllegalArgumentException if initialCapacity > 64KB.
                int minAlloc = Math.max(CHUNK_SIZE, initialCapacity);
                int estimated = Math.clamp(compressedLen * 3L, minAlloc, MAX_DECOMPRESSED_SIZE);
                int allocSize = Math.min(estimated, MAX_INITIAL_ALLOCATION);

                ByteBuf out = ctx.alloc().directBuffer(allocSize);
                boolean transferred = false;

                try {
                    boolean frameFinished = false;

                    for (ByteBuffer srcNio : srcBuffers) {
                        while (srcNio.hasRemaining()) {
                            out.ensureWritable(CHUNK_SIZE);
                            int writerIndex = out.writerIndex();
                            ByteBuffer targetNio = out.internalNioBuffer(writerIndex, out.writableBytes());

                            int targetRemainingBefore = targetNio.remaining();
                            int srcRemainingBefore = srcNio.remaining();

                            frameFinished = decompressCtx.decompressDirectByteBufferStream(targetNio, srcNio);

                            int written = targetRemainingBefore - targetNio.remaining();
                            int consumed = srcRemainingBefore - srcNio.remaining();

                            if (written > 0) {
                                out.writerIndex(writerIndex + written);
                                if (out.readableBytes() > MAX_DECOMPRESSED_SIZE) {
                                    throw new IllegalStateException("Decompressed payload exceeded maximum limit of "
                                            + MAX_DECOMPRESSED_SIZE + " bytes");
                                }
                            }

                            if (consumed == 0 && written == 0) {
                                throw new IllegalStateException("Zstd decompression made no progress");
                            }
                        }
                    }

                    // If the frame wasn't marked finished by the native stream decoder, flush with empty input
                    while (!frameFinished) {
                        out.ensureWritable(CHUNK_SIZE);
                        int writerIndex = out.writerIndex();
                        ByteBuffer targetNio = out.internalNioBuffer(writerIndex, out.writableBytes());
                        int targetRemainingBefore = targetNio.remaining();

                        ByteBuffer empty = EMPTY_DIRECT_BUFFER.duplicate();
                        frameFinished = decompressCtx.decompressDirectByteBufferStream(targetNio, empty);

                        int written = targetRemainingBefore - targetNio.remaining();
                        if (written > 0) {
                            out.writerIndex(writerIndex + written);
                            if (out.readableBytes() > MAX_DECOMPRESSED_SIZE) {
                                throw new IllegalStateException("Decompressed payload exceeded maximum limit of "
                                        + MAX_DECOMPRESSED_SIZE + " bytes");
                            }
                        } else {
                            break;
                        }
                    }

                    if (out.isReadable()) {
                        transferred = true;
                        ctx.fireChannelRead(out);
                    } else {
                        out.release();
                        transferred = true;
                    }
                } catch (Throwable t) {
                    reset();
                    ctx.close();
                    ctx.fireExceptionCaught(t);
                    return;
                } finally {
                    if (!transferred) {
                        out.release();
                    }
                }
            } finally {
                if (directIn != null) {
                    directIn.release();
                }
                frame.release();
            }
        } else if (msg instanceof TextWebSocketFrame frame) {
            ByteBuf content = frame.content().retain();
            frame.release();
            ctx.fireChannelRead(content);
        } else {
            // Forward other frames (Ping, Pong, Close, Http Responses)
            ctx.fireChannelRead(msg);
        }
    }

    public void reset() {
        try {
            decompressCtx.reset();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        reset();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        reset();
        try {
            decompressCtx.close();
        } catch (Exception ignored) {
        }
        super.handlerRemoved(ctx);
    }
}
