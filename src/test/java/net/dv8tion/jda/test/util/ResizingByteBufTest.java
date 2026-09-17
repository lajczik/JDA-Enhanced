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

package net.dv8tion.jda.test.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.internal.utils.ResizingByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ResizingByteBufTest {
    @Test
    void testDefaultAllocatorUsesNettyConfigGlobalAllocator() {
        try (ResizingByteBuf buf = new ResizingByteBuf(64)) {
            assertThat(buf.getAllocator()).isSameAs(NettyConfig.getGlobalAllocator());
            assertThat(buf.buffer().isDirect()).isTrue();
            assertThat(buf.buffer().capacity()).isGreaterThanOrEqualTo(64);
        }
    }

    @Test
    void testCustomAllocator() {
        ByteBufAllocator customAllocator = new UnpooledByteBufAllocator(false);
        try (ResizingByteBuf buf = new ResizingByteBuf(customAllocator, 128)) {
            assertThat(buf.getAllocator()).isSameAs(customAllocator);
        }
    }

    @Test
    void testPrepareWriteReusesCapacityOrReallocates() {
        try (ResizingByteBuf buf = new ResizingByteBuf(64)) {
            ByteBuf initialBuf = buf.buffer();
            buf.prepareWrite(32);
            // Smaller capacity should reuse the same buffer
            assertThat(buf.buffer()).isSameAs(initialBuf);

            // Larger capacity should reallocate and release old buffer
            buf.prepareWrite(256);
            assertThat(buf.buffer()).isNotSameAs(initialBuf);
            assertThat(initialBuf.refCnt()).isEqualTo(0);
            assertThat(buf.buffer().capacity()).isGreaterThanOrEqualTo(256);
        }
    }

    @Test
    void testEnsureWritable() {
        try (ResizingByteBuf buf = new ResizingByteBuf(32)) {
            buf.ensureWritable(128);
            assertThat(buf.buffer().writableBytes()).isGreaterThanOrEqualTo(128);
        }
    }

    @Test
    void testReplaceByteBufZeroCopy() {
        try (ResizingByteBuf buf = new ResizingByteBuf(32)) {
            ByteBuf oldBuf = buf.buffer();

            ByteBuf externalData = buf.getAllocator().buffer(64);
            externalData.writeCharSequence("hello world", StandardCharsets.UTF_8);

            buf.replace(externalData);

            // Old buffer should be released
            assertThat(oldBuf.refCnt()).isEqualTo(0);
            // New buffer in ResizingByteBuf should have readable bytes
            assertThat(buf.buffer().toString(StandardCharsets.UTF_8)).isEqualTo("hello world");

            // externalData was retained, so its ref count is 2 (externalData + buf's slice)
            assertThat(externalData.refCnt()).isEqualTo(2);
            externalData.release();
            assertThat(buf.buffer().refCnt()).isEqualTo(1);
        }
    }

    @Test
    void testReplaceByteArrayWrapping() {
        try (ResizingByteBuf buf = new ResizingByteBuf(64)) {
            ByteBuf initialBuf = buf.buffer();
            byte[] data = "wrapped byte array".getBytes(StandardCharsets.UTF_8);
            buf.replace(data);

            assertThat(initialBuf.refCnt()).isEqualTo(0);
            assertThat(buf.buffer().toString(StandardCharsets.UTF_8)).isEqualTo("wrapped byte array");
            assertThat(buf.buffer().hasArray()).isTrue();
            assertThat(buf.buffer().array()).isSameAs(data);

            byte[] multi = "prefix_target_suffix".getBytes(StandardCharsets.UTF_8);
            buf.replace(multi, 7, 6);
            assertThat(buf.buffer().toString(StandardCharsets.UTF_8)).isEqualTo("target");
        }
    }

    @Test
    void testReplaceByteBufferWrapping() {
        try (ResizingByteBuf buf = new ResizingByteBuf(64)) {
            ByteBuf initialBuf = buf.buffer();
            ByteBuffer nio = ByteBuffer.wrap("wrapped nio".getBytes(StandardCharsets.UTF_8));
            buf.replace(nio);

            assertThat(initialBuf.refCnt()).isEqualTo(0);
            assertThat(buf.buffer().toString(StandardCharsets.UTF_8)).isEqualTo("wrapped nio");

            ByteBuffer largeNio = ByteBuffer.allocate(512);
            largeNio.putInt(123456);
            largeNio.flip();

            buf.replace(largeNio);
            assertThat(buf.buffer().readInt()).isEqualTo(123456);
        }
    }

    @Test
    void testNioBufferViews() {
        try (ResizingByteBuf buf = new ResizingByteBuf(64)) {
            buf.buffer().writeInt(98765);
            ByteBuffer nio = buf.nioBuffer();
            assertThat(nio.position()).isEqualTo(0);
            assertThat(nio.limit()).isEqualTo(4);
            assertThat(nio.getInt()).isEqualTo(98765);
        }
    }

    @Test
    void testReleaseAndClose() {
        ResizingByteBuf buf = new ResizingByteBuf(64);
        ByteBuf inner = buf.buffer();
        assertThat(inner.refCnt()).isGreaterThanOrEqualTo(1);

        buf.release();
        assertThat(inner.refCnt()).isEqualTo(0);
        assertThatThrownBy(buf::buffer).isInstanceOf(IllegalStateException.class);

        // Multiple calls to close / release should be safe
        buf.close();
        buf.release();
    }
}
