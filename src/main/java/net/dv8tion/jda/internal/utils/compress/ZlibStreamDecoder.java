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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.nio.ByteBuffer;
import java.util.zip.Inflater;

/**
 * Zero-copy streaming ZLIB (deflate) decoder for Discord Gateway websocket.
 * Decompresses continuous zlib-stream packets ending with Z_SYNC_FLUSH (0x00
 * 0x00 0xFF 0xFF).
 */
public class ZlibStreamDecoder extends ChannelInboundHandlerAdapter {
    private static final int DEFAULT_MAX_BUFFER_SIZE = 2048;
    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_COMPRESSED_BUFFER_SIZE = 32 * 1024 * 1024; // 32 MB guard against unbounded growth
    // without Z_SYNC_FLUSH
    private static final int MAX_DECOMPRESSED_SIZE = 64 * 1024 * 1024; // 64 MB guard against compression bombs

    private final int maxBufferSize;
    private final Inflater inflater = new Inflater();
    private ByteBuf flushBuffer = null;

    public ZlibStreamDecoder() {
        this(DEFAULT_MAX_BUFFER_SIZE);
    }

    public ZlibStreamDecoder(int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof BinaryWebSocketFrame frame) {
            ByteBuf content = frame.content();
            if (!content.isReadable()) {
                frame.release();
                return;
            }

            if (content.readableBytes() > MAX_COMPRESSED_BUFFER_SIZE) {
                reset();
                frame.release();
                throw new IllegalStateException(
                        "Compressed frame exceeded maximum limit of " + MAX_COMPRESSED_BUFFER_SIZE + " bytes");
            }

            try {
                if (!isFlush(flushBuffer, content)) {
                    if (flushBuffer == null) {
                        flushBuffer = ctx.alloc().buffer(content.readableBytes());
                    }
                    if (flushBuffer.readableBytes() + content.readableBytes() > MAX_COMPRESSED_BUFFER_SIZE) {
                        reset();
                        throw new IllegalStateException("Compressed buffer exceeded maximum limit of "
                                + MAX_COMPRESSED_BUFFER_SIZE + " bytes without Z_SYNC_FLUSH");
                    }
                    flushBuffer.writeBytes(content);
                    return;
                }

                boolean ownsInputBuffer = (flushBuffer != null);
                ByteBuf input;
                if (ownsInputBuffer) {
                    if (flushBuffer.readableBytes() + content.readableBytes() > MAX_COMPRESSED_BUFFER_SIZE) {
                        reset();
                        throw new IllegalStateException("Compressed buffer exceeded maximum limit of "
                                + MAX_COMPRESSED_BUFFER_SIZE + " bytes without Z_SYNC_FLUSH");
                    }
                    flushBuffer.writeBytes(content);
                    input = flushBuffer;
                    flushBuffer = null;
                } else {
                    input = content;
                }

                try {
                    int minAlloc = Math.max(CHUNK_SIZE, maxBufferSize);
                    int allocSize = Math.clamp(input.readableBytes() * 3L, minAlloc, 65536);
                    ByteBuf out = ctx.alloc().directBuffer(allocSize);
                    boolean transferred = false;

                    try {
                        ByteBuf directIn = null;
                        try {
                            ByteBuffer srcNio;
                            if (input.isDirect() && input.nioBufferCount() == 1) {
                                srcNio = input.nioBuffer();
                            } else {
                                directIn = ctx.alloc().directBuffer(input.readableBytes());
                                directIn.writeBytes(input);
                                srcNio = directIn.nioBuffer();
                            }

                            inflater.setInput(srcNio);
                            while (!inflater.needsInput() && !inflater.finished()) {
                                out.ensureWritable(CHUNK_SIZE);
                                int writerIndex = out.writerIndex();
                                ByteBuffer targetNio = out.internalNioBuffer(writerIndex, out.writableBytes());
                                int bytesInflated = inflater.inflate(targetNio);
                                if (bytesInflated > 0) {
                                    out.writerIndex(writerIndex + bytesInflated);
                                    if (out.readableBytes() > MAX_DECOMPRESSED_SIZE) {
                                        throw new IllegalStateException(
                                                "Decompressed payload exceeded maximum limit of "
                                                        + MAX_DECOMPRESSED_SIZE + " bytes");
                                    }
                                } else {
                                    break;
                                }
                            }
                        } finally {
                            if (directIn != null) {
                                directIn.release();
                            }
                        }

                        if (out.isReadable()) {
                            transferred = true;
                            ctx.fireChannelRead(out);
                        } else {
                            out.release();
                            transferred = true;
                        }
                    } finally {
                        if (!transferred) {
                            out.release();
                            reset();
                        }
                    }
                } finally {
                    if (ownsInputBuffer) {
                        input.release();
                    }
                }
            } finally {
                frame.release();
            }
        } else if (msg instanceof TextWebSocketFrame frame) {
            ByteBuf content = frame.content().retain();
            frame.release();
            ctx.fireChannelRead(content);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private byte getByteFromEnd(ByteBuf prefix, ByteBuf current, int offsetFromEnd) {
        int curLen = current.readableBytes();
        if (offsetFromEnd <= curLen) {
            return current.getByte(current.readerIndex() + curLen - offsetFromEnd);
        } else {
            int prefixOffset = offsetFromEnd - curLen;
            return prefix.getByte(prefix.readerIndex() + prefix.readableBytes() - prefixOffset);
        }
    }

    private boolean isFlush(ByteBuf prefix, ByteBuf current) {
        int totalLen = (prefix != null ? prefix.readableBytes() : 0) + current.readableBytes();
        if (totalLen < 4) {
            return false;
        }
        return getByteFromEnd(prefix, current, 4) == 0x00
                && getByteFromEnd(prefix, current, 3) == 0x00
                && (getByteFromEnd(prefix, current, 2) & 0xFF) == 0xFF
                && (getByteFromEnd(prefix, current, 1) & 0xFF) == 0xFF;
    }

    public void reset() {
        if (flushBuffer != null) {
            if (flushBuffer.refCnt() > 0) {
                flushBuffer.release();
            }
            flushBuffer = null;
        }
        inflater.reset();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        reset();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        reset();
        inflater.end();
        super.handlerRemoved(ctx);
    }
}
