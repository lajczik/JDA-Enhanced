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

package net.dv8tion.jda.test.data;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class SerializationByteBufTest {
    @Test
    void testDirectByteBufDataObject() {
        String json = "{\"id\":1234567890,\"name\":\"discord-test\",\"active\":true,\"values\":[1,2,3]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        // Test with Direct ByteBuf (off-heap)
        ByteBuf directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(bytes.length);
        try {
            directBuf.writeBytes(bytes);
            DataObject obj = DataObject.fromJson(directBuf);

            assertThat(obj.getLong("id")).isEqualTo(1234567890L);
            assertThat(obj.getString("name")).isEqualTo("discord-test");
            assertThat(obj.getBoolean("active")).isTrue();
            assertThat(obj.getArray("values").toList()).containsExactly(1, 2, 3);
        } finally {
            directBuf.release();
        }
    }

    @Test
    void testHeapByteBufDataObject() {
        String json = "{\"code\":0,\"message\":\"hello\"}";
        ByteBuf heapBuf = Unpooled.wrappedBuffer(json.getBytes(StandardCharsets.UTF_8));
        try {
            DataObject obj = DataObject.fromJson(heapBuf);
            assertThat(obj.getInt("code")).isEqualTo(0);
            assertThat(obj.getString("message")).isEqualTo("hello");
        } finally {
            heapBuf.release();
        }
    }

    @Test
    void testDirectByteBufDataArray() {
        String json = "[{\"id\":1},{\"id\":2},{\"id\":3}]";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        ByteBuf directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(bytes.length);
        try {
            directBuf.writeBytes(bytes);
            DataArray array = DataArray.fromJson(directBuf);

            assertThat(array).hasSize(3);
            assertThat(array.getObject(0).getInt("id")).isEqualTo(1);
            assertThat(array.getObject(1).getInt("id")).isEqualTo(2);
            assertThat(array.getObject(2).getInt("id")).isEqualTo(3);
        } finally {
            directBuf.release();
        }
    }
}
