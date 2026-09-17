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

package net.dv8tion.jda.test.compress;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.utils.compress.ZlibStreamDecoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ZlibStreamDecoderTest {
    private static byte[] compressZlibSyncFlush(Deflater deflater, byte[] uncompressed) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater, true);
        dos.write(uncompressed);
        dos.flush();
        return baos.toByteArray();
    }

    @Test
    void testZlibStreamDecompressionZeroCopy() throws IOException {
        EmbeddedChannel channel = new EmbeddedChannel(new ZlibStreamDecoder());
        Deflater deflater = new Deflater();

        try {
            String json =
                    "{\"op\":0,\"d\":{\"guild_id\":\"123456\",\"name\":\"discord-server\"},\"t\":\"GUILD_CREATE\"}";
            byte[] uncompressed = json.getBytes(StandardCharsets.UTF_8);
            byte[] compressed = compressZlibSyncFlush(deflater, uncompressed);

            ByteBuf directIn = PooledByteBufAllocator.DEFAULT.directBuffer(compressed.length);
            directIn.writeBytes(compressed);
            BinaryWebSocketFrame frame = new BinaryWebSocketFrame(directIn);

            boolean written = channel.writeInbound(frame);
            assertThat(written).isTrue();
            assertThat(frame.refCnt()).isEqualTo(0);

            ByteBuf out = channel.readInbound();
            assertThat(out).isNotNull();
            try {
                DataObject obj = DataObject.fromJson(out);
                assertThat(obj.getInt("op")).isEqualTo(0);
                assertThat(obj.getString("t")).isEqualTo("GUILD_CREATE");
                assertThat(obj.getObject("d").getString("name")).isEqualTo("discord-server");
            } finally {
                out.release();
            }
        } finally {
            deflater.end();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testContinuousZlibStreamMultipleMessages() throws IOException {
        EmbeddedChannel channel = new EmbeddedChannel(new ZlibStreamDecoder());
        Deflater deflater = new Deflater();

        try {
            // Message 1
            String json1 = "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"d\":{\"content\":\"hello world\"}}";
            byte[] comp1 = compressZlibSyncFlush(deflater, json1.getBytes(StandardCharsets.UTF_8));
            ByteBuf buf1 =
                    PooledByteBufAllocator.DEFAULT.directBuffer(comp1.length).writeBytes(comp1);
            channel.writeInbound(new BinaryWebSocketFrame(buf1));

            ByteBuf out1 = channel.readInbound();
            assertThat(out1).isNotNull();
            try {
                DataObject obj1 = DataObject.fromJson(out1);
                assertThat(obj1.getString("t")).isEqualTo("MESSAGE_CREATE");
                assertThat(obj1.getObject("d").getString("content")).isEqualTo("hello world");
            } finally {
                out1.release();
            }

            // Message 2 (relies on shared compression stream dictionary)
            String json2 = "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"d\":{\"content\":\"hello world 2\"}}";
            byte[] comp2 = compressZlibSyncFlush(deflater, json2.getBytes(StandardCharsets.UTF_8));
            ByteBuf buf2 =
                    PooledByteBufAllocator.DEFAULT.directBuffer(comp2.length).writeBytes(comp2);
            channel.writeInbound(new BinaryWebSocketFrame(buf2));

            ByteBuf out2 = channel.readInbound();
            assertThat(out2).isNotNull();
            try {
                DataObject obj2 = DataObject.fromJson(out2);
                assertThat(obj2.getString("t")).isEqualTo("MESSAGE_CREATE");
                assertThat(obj2.getObject("d").getString("content")).isEqualTo("hello world 2");
            } finally {
                out2.release();
            }
        } finally {
            deflater.end();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testFragmentedZlibStreamPayload() throws IOException {
        EmbeddedChannel channel = new EmbeddedChannel(new ZlibStreamDecoder());
        Deflater deflater = new Deflater();

        try {
            String json = "{\"op\":0,\"d\":{\"large_field\":\"" + "A".repeat(5000) + "\"},\"t\":\"BIG_EVENT\"}";
            byte[] compressed = compressZlibSyncFlush(deflater, json.getBytes(StandardCharsets.UTF_8));

            // Split into 2 chunks: chunk 1 does not have Z_SYNC_FLUSH suffix, chunk 2 has it
            int split = compressed.length - 10;
            ByteBuf part1 = Unpooled.copiedBuffer(compressed, 0, split);
            ByteBuf part2 = Unpooled.copiedBuffer(compressed, split, compressed.length - split);

            // First chunk should not trigger channel read because it's not flushed yet
            channel.writeInbound(new BinaryWebSocketFrame(part1));
            ByteBuf firstChunk = channel.readInbound();
            assertThat(firstChunk).isNull();

            // Second chunk completes the flush suffix and emits the full message
            boolean written = channel.writeInbound(new BinaryWebSocketFrame(part2));
            assertThat(written).isTrue();

            ByteBuf out = channel.readInbound();
            assertThat(out).isNotNull();
            try {
                DataObject obj = DataObject.fromJson(out);
                assertThat(obj.getString("t")).isEqualTo("BIG_EVENT");
                assertThat(obj.getObject("d").getString("large_field")).hasSize(5000);
            } finally {
                out.release();
            }
        } finally {
            deflater.end();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testPassThroughTextFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZlibStreamDecoder());

        String json = "{\"op\":1,\"d\":100}";
        ByteBuf buf = Unpooled.wrappedBuffer(json.getBytes(StandardCharsets.UTF_8));
        TextWebSocketFrame textFrame = new TextWebSocketFrame(buf);

        boolean written = channel.writeInbound(textFrame);
        assertThat(written).isTrue();

        ByteBuf out = channel.readInbound();
        assertThat(out).isNotNull();
        assertThat(out.refCnt()).isEqualTo(1);
        try {
            DataObject obj = DataObject.fromJson(out);
            assertThat(obj.getInt("op")).isEqualTo(1);
            assertThat(obj.getInt("d")).isEqualTo(100);
        } finally {
            out.release();
        }

        assertThat(textFrame.refCnt()).isEqualTo(0);
        channel.finishAndReleaseAll();
    }

    @Test
    void testCustomMaxBufferSize() throws IOException {
        EmbeddedChannel channel = new EmbeddedChannel(new ZlibStreamDecoder(4096));
        Deflater deflater = new Deflater();

        try {
            String json = "{\"op\":0,\"d\":{\"message\":\"buffer test\"},\"t\":\"CUSTOM_BUFFER\"}";
            byte[] compressed = compressZlibSyncFlush(deflater, json.getBytes(StandardCharsets.UTF_8));

            ByteBuf directIn = PooledByteBufAllocator.DEFAULT
                    .directBuffer(compressed.length)
                    .writeBytes(compressed);
            channel.writeInbound(new BinaryWebSocketFrame(directIn));

            ByteBuf out = channel.readInbound();
            assertThat(out).isNotNull();
            try {
                DataObject obj = DataObject.fromJson(out);
                assertThat(obj.getString("t")).isEqualTo("CUSTOM_BUFFER");
                assertThat(obj.getObject("d").getString("message")).isEqualTo("buffer test");
            } finally {
                out.release();
            }
        } finally {
            deflater.end();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testDownstreamExceptionDoesNotCauseDoubleFree() throws IOException {
        // Downstream handler consumes/releases the buffer, then throws an exception
        ChannelInboundHandlerAdapter faultyDownstream = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof ByteBuf buf) {
                    buf.release(); // downstream releases buffer
                    throw new RuntimeException("Downstream parsing failure");
                }
            }
        };

        EmbeddedChannel channel = new EmbeddedChannel(new ZlibStreamDecoder(), faultyDownstream);
        Deflater deflater = new Deflater();

        try {
            String json = "{\"op\":1}";
            byte[] compressed = compressZlibSyncFlush(deflater, json.getBytes(StandardCharsets.UTF_8));

            ByteBuf directIn = PooledByteBufAllocator.DEFAULT
                    .directBuffer(compressed.length)
                    .writeBytes(compressed);

            // The exception should be the original RuntimeException, NOT IllegalReferenceCountException
            assertThatThrownBy(() -> channel.writeInbound(new BinaryWebSocketFrame(directIn)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Downstream parsing failure");
        } finally {
            deflater.end();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void testUnboundedFlushBufferGrowthThrowsException() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZlibStreamDecoder());

        // Create a single frame exceeding the 32 MB compressed buffer limit
        ByteBuf largeBuf = Unpooled.buffer(33 * 1024 * 1024);
        largeBuf.writerIndex(33 * 1024 * 1024);
        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(largeBuf);

        assertThatThrownBy(() -> channel.writeInbound(frame))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeded maximum limit of 33554432 bytes");

        assertThat(frame.refCnt()).isEqualTo(0);
        channel.finishAndReleaseAll();
    }
}
