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

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.utils.compress.ZstdStreamDecoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ZstdStreamDecoderTest {
    @Test
    void testZstdStreamDecompressionZeroCopy() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdStreamDecoder());

        String json = "{\"op\":0,\"d\":{\"guild_id\":\"123456\",\"name\":\"discord-server\"},\"t\":\"GUILD_CREATE\"}";
        byte[] uncompressed = json.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = Zstd.compress(uncompressed);

        ByteBuf directIn = PooledByteBufAllocator.DEFAULT.directBuffer(compressed.length);
        directIn.writeBytes(compressed);
        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(directIn);

        // Write frame to channel
        boolean written = channel.writeInbound(frame);
        assertThat(written).isTrue();

        // Golden rule check: frame and directIn should be released
        assertThat(frame.refCnt()).isEqualTo(0);

        // Read decompressed output ByteBuf
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

        channel.finishAndReleaseAll();
    }

    @Test
    void testContinuousZstdStreamMultipleMessages() throws IOException {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdStreamDecoder());

        // Simulate continuous Discord Gateway zstd-stream where stream is flushed (not closed) per message
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZstdOutputStream zos = new ZstdOutputStream(baos, 3);
        zos.setCloseFrameOnFlush(false); // Discord keeps the zstd frame open across messages!

        // Message 1
        String json1 = "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"d\":{\"content\":\"stream hello 1\"}}";
        zos.write(json1.getBytes(StandardCharsets.UTF_8));
        zos.flush();
        byte[] comp1 = baos.toByteArray();
        baos.reset();

        ByteBuf buf1 = PooledByteBufAllocator.DEFAULT.directBuffer(comp1.length).writeBytes(comp1);
        channel.writeInbound(new BinaryWebSocketFrame(buf1));

        ByteBuf out1 = channel.readInbound();
        assertThat(out1).isNotNull();
        try {
            DataObject obj1 = DataObject.fromJson(out1);
            assertThat(obj1.getString("t")).isEqualTo("MESSAGE_CREATE");
            assertThat(obj1.getObject("d").getString("content")).isEqualTo("stream hello 1");
        } finally {
            out1.release();
        }

        // Message 2
        String json2 = "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"d\":{\"content\":\"stream hello 2\"}}";
        zos.write(json2.getBytes(StandardCharsets.UTF_8));
        zos.flush();
        byte[] comp2 = baos.toByteArray();
        baos.reset();

        ByteBuf buf2 = PooledByteBufAllocator.DEFAULT.directBuffer(comp2.length).writeBytes(comp2);
        channel.writeInbound(new BinaryWebSocketFrame(buf2));

        ByteBuf out2 = channel.readInbound();
        assertThat(out2).isNotNull();
        try {
            DataObject obj2 = DataObject.fromJson(out2);
            assertThat(obj2.getString("t")).isEqualTo("MESSAGE_CREATE");
            assertThat(obj2.getObject("d").getString("content")).isEqualTo("stream hello 2");
        } finally {
            out2.release();
        }

        zos.close();
        channel.finishAndReleaseAll();
    }

    @Test
    void testPassThroughTextFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdStreamDecoder());

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
    void testLargeFragmentedPayloadWithAggregator() {
        EmbeddedChannel channel =
                new EmbeddedChannel(new WebSocketFrameAggregator(65536 * 1024), new ZstdStreamDecoder());

        // Build a large JSON payload > 10KB
        StringBuilder sb = new StringBuilder();
        sb.append("{\"op\":0,\"t\":\"GUILD_CREATE\",\"d\":{\"roles\":[");
        for (int i = 0; i < 200; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"")
                    .append(100000 + i)
                    .append("\",\"name\":\"role_")
                    .append(i)
                    .append("\",\"permissions\":8}");
        }
        sb.append("]}}");
        String json = sb.toString();

        byte[] uncompressed = json.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = Zstd.compress(uncompressed);

        // Split compressed payload across 2 frames (binary frame with fin=false, and continuation with fin=true)
        int split = compressed.length / 2;
        ByteBuf part1 = Unpooled.copiedBuffer(compressed, 0, split);
        ByteBuf part2 = Unpooled.copiedBuffer(compressed, split, compressed.length - split);

        BinaryWebSocketFrame frame1 = new BinaryWebSocketFrame(false, 0, part1);
        ContinuationWebSocketFrame frame2 = new ContinuationWebSocketFrame(true, 0, part2);

        channel.writeInbound(frame1);
        channel.writeInbound(frame2);

        ByteBuf out = channel.readInbound();
        assertThat(out).isNotNull();
        try {
            DataObject obj = DataObject.fromJson(out);
            assertThat(obj.getInt("op")).isEqualTo(0);
            assertThat(obj.getString("t")).isEqualTo("GUILD_CREATE");
            assertThat(obj.getObject("d").getArray("roles").length()).isEqualTo(200);
        } finally {
            out.release();
        }

        channel.finishAndReleaseAll();
    }

    @Test
    void testCompositeByteBufZeroCopy() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdStreamDecoder());

        String json = "{\"op\":0,\"d\":{\"name\":\"composite test\"},\"t\":\"COMPOSITE\"}";
        byte[] compressed = Zstd.compress(json.getBytes(StandardCharsets.UTF_8));

        int split = compressed.length / 2;
        ByteBuf c1 = PooledByteBufAllocator.DEFAULT.directBuffer(split).writeBytes(compressed, 0, split);
        ByteBuf c2 = PooledByteBufAllocator.DEFAULT
                .directBuffer(compressed.length - split)
                .writeBytes(compressed, split, compressed.length - split);

        CompositeByteBuf composite = PooledByteBufAllocator.DEFAULT.compositeDirectBuffer(2);
        composite.addComponents(true, c1, c2);
        assertThat(composite.nioBufferCount()).isGreaterThan(1);

        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(composite);
        channel.writeInbound(frame);

        ByteBuf out = channel.readInbound();
        assertThat(out).isNotNull();
        try {
            DataObject obj = DataObject.fromJson(out);
            assertThat(obj.getString("t")).isEqualTo("COMPOSITE");
            assertThat(obj.getObject("d").getString("name")).isEqualTo("composite test");
        } finally {
            out.release();
        }

        assertThat(frame.refCnt()).isEqualTo(0);
        channel.finishAndReleaseAll();
    }

    @Test
    void testCustomMaxBufferSize() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdStreamDecoder(4096));

        String json = "{\"op\":0,\"d\":{\"message\":\"zstd buffer test\"},\"t\":\"CUSTOM_BUFFER\"}";
        byte[] compressed = Zstd.compress(json.getBytes(StandardCharsets.UTF_8));

        ByteBuf directIn =
                PooledByteBufAllocator.DEFAULT.directBuffer(compressed.length).writeBytes(compressed);
        channel.writeInbound(new BinaryWebSocketFrame(directIn));

        ByteBuf out = channel.readInbound();
        assertThat(out).isNotNull();
        try {
            DataObject obj = DataObject.fromJson(out);
            assertThat(obj.getString("t")).isEqualTo("CUSTOM_BUFFER");
            assertThat(obj.getObject("d").getString("message")).isEqualTo("zstd buffer test");
        } finally {
            out.release();
        }

        channel.finishAndReleaseAll();
    }

    @Test
    void testCustomLargeBufferSizeOver64KBDoesNotThrow() {
        // Tests that initialCapacity > 65536 does not throw IllegalArgumentException
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdStreamDecoder(131072));

        String json = "{\"op\":0,\"d\":{\"message\":\"large buffer test\"},\"t\":\"LARGE_BUFFER\"}";
        byte[] compressed = Zstd.compress(json.getBytes(StandardCharsets.UTF_8));

        ByteBuf directIn =
                PooledByteBufAllocator.DEFAULT.directBuffer(compressed.length).writeBytes(compressed);
        channel.writeInbound(new BinaryWebSocketFrame(directIn));

        ByteBuf out = channel.readInbound();
        assertThat(out).isNotNull();
        try {
            DataObject obj = DataObject.fromJson(out);
            assertThat(obj.getString("t")).isEqualTo("LARGE_BUFFER");
            assertThat(obj.getObject("d").getString("message")).isEqualTo("large buffer test");
        } finally {
            out.release();
        }

        channel.finishAndReleaseAll();
    }

    @Test
    void testDownstreamExceptionDoesNotCauseDoubleFree() {
        ChannelInboundHandlerAdapter faultyDownstream = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof ByteBuf buf) {
                    buf.release(); // downstream consumes and releases
                    throw new RuntimeException("Zstd downstream failure");
                }
            }
        };

        EmbeddedChannel channel = new EmbeddedChannel(new ZstdStreamDecoder(), faultyDownstream);

        String json = "{\"op\":1}";
        byte[] compressed = Zstd.compress(json.getBytes(StandardCharsets.UTF_8));

        ByteBuf directIn =
                PooledByteBufAllocator.DEFAULT.directBuffer(compressed.length).writeBytes(compressed);

        assertThatThrownBy(() -> channel.writeInbound(new BinaryWebSocketFrame(directIn)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Zstd downstream failure");

        channel.finishAndReleaseAll();
    }

    @Test
    void testOversizedCompressedFrameThrowsException() {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdStreamDecoder());

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
