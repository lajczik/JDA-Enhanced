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

package net.dv8tion.jda.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.dv8tion.jda.api.utils.Compression;
import net.dv8tion.jda.internal.audio.AudioPacket;
import net.dv8tion.jda.internal.utils.NettyUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NettyUtilsTest {
    @Test
    void testSslAndTransportNames() {
        assertEquals("JDK", NettyUtils.getSslProviderName());
        assertNotNull(NettyUtils.getTransportName(true));
        assertNotNull(NettyUtils.getTransportName(false));
    }

    @Test
    void testCompressionSupported() {
        assertTrue(Compression.NONE.isSupported());
        assertTrue(Compression.ZLIB.isSupported());
        // ZSTD is on classpath in test environment
        assertTrue(Compression.ZSTD.isSupported());
    }

    @Test
    void testDatagramChannelClass() {
        assertNotNull(NettyUtils.getDatagramChannelClass());
        assertNotNull(NettyUtils.getDatagramChannelClass(true));
        assertNotNull(NettyUtils.getDatagramChannelClass(false));
    }

    @Test
    void testAudioPacketFromByteBuf() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0x80); // version 2
        buf.writeByte(0x78); // type
        buf.writeShort(1234); // seq
        buf.writeInt(5678); // timestamp
        buf.writeInt(9999); // ssrc
        buf.writeBytes(new byte[] {1, 2, 3, 4});

        AudioPacket packet = new AudioPacket(buf);
        assertEquals(1234, (int) packet.getSequence());
        assertEquals(5678, packet.getTimestamp());
        assertEquals(9999, packet.getSSRC());
        assertEquals(4, packet.getEncodedAudio().remaining());
    }
}
