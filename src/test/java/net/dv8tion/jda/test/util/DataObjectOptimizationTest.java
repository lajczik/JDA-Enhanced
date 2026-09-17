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
import io.netty.buffer.Unpooled;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.etf.ExTermDecoder;
import net.dv8tion.jda.api.utils.data.etf.ExTermEncoder;
import net.dv8tion.jda.internal.utils.SerializationUtil;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataObjectOptimizationTest {
    @Test
    void testDataObjectAccessorsAndCoercions() {
        DataObject obj = DataObject.empty()
                .put("str", "hello")
                .put("numStr", "123456789012345678")
                .put("intVal", 42)
                .put("longVal", 9876543210L)
                .put("doubleVal", 3.14159)
                .put("doubleStr", "2.71828")
                .put("boolVal", true)
                .put("boolStr", "true")
                .put("timeStr", "2026-08-22T21:00:00.000Z")
                .put("nestedObj", DataObject.empty().put("subKey", "subVal"))
                .put("nestedArr", DataArray.empty().add("item1").add("item2"));

        // Strings
        assertThat(obj.getString("str")).isEqualTo("hello");
        assertThat(obj.getString("intVal")).isEqualTo("42");
        assertThat(obj.getString("missing", "def")).isEqualTo("def");

        // Longs & Snowflakes
        assertThat(obj.getLong("numStr")).isEqualTo(123456789012345678L);
        assertThat(obj.getUnsignedLong("numStr")).isEqualTo(123456789012345678L);
        assertThat(obj.getLong("longVal")).isEqualTo(9876543210L);
        assertThat(obj.getLong("intVal")).isEqualTo(42L);
        assertThat(obj.getLong("missing", 999L)).isEqualTo(999L);
        assertThat(obj.getUnsignedLong("missing", 999L)).isEqualTo(999L);

        // Integers
        assertThat(obj.getInt("intVal")).isEqualTo(42);
        assertThat(obj.getInt("missing", 100)).isEqualTo(100);
        assertThat(obj.getUnsignedInt("missing", 100)).isEqualTo(100);
        assertThatThrownBy(() -> obj.getInt("numStr", 0)).isInstanceOf(NumberFormatException.class);

        // Doubles
        assertThat(obj.getDouble("doubleVal")).isEqualTo(3.14159);
        assertThat(obj.getDouble("doubleStr")).isEqualTo(2.71828);
        assertThat(obj.getDouble("intVal")).isEqualTo(42.0);
        assertThat(obj.getDouble("missing", 1.23)).isEqualTo(1.23);

        // Booleans
        assertThat(obj.getBoolean("boolVal")).isTrue();
        assertThat(obj.getBoolean("boolStr")).isTrue();
        assertThat(obj.getBoolean("missing", false)).isFalse();
        assertThat(obj.getBoolean("missing", true)).isTrue();

        // Timestamps
        assertThat(obj.getOffsetDateTime("timeStr"))
                .isEqualTo(OffsetDateTime.of(2026, 8, 22, 21, 0, 0, 0, ZoneOffset.UTC));
        assertThat(obj.getOffsetDateTime("missing", null)).isNull();

        // Nested Objects & Arrays
        assertThat(obj.getObject("nestedObj").getString("subKey")).isEqualTo("subVal");
        assertThat(obj.optObject("nestedObj")).isPresent();
        assertThat(obj.optObject("missing")).isEmpty();

        assertThat(obj.getArray("nestedArr").getString(0)).isEqualTo("item1");
        assertThat(obj.optArray("nestedArr")).isPresent();
        assertThat(obj.optArray("missing")).isEmpty();

        // Errors
        assertThatThrownBy(() -> obj.getObject("missing")).isInstanceOf(ParsingException.class);
        assertThatThrownBy(() -> obj.getArray("missing")).isInstanceOf(ParsingException.class);
        assertThatThrownBy(() -> obj.getString("missing")).isInstanceOf(ParsingException.class);
        assertThatThrownBy(() -> obj.getLong("missing")).isInstanceOf(ParsingException.class);
        assertThatThrownBy(() -> obj.getInt("missing")).isInstanceOf(ParsingException.class);
        assertThatThrownBy(() -> obj.getDouble("missing")).isInstanceOf(ParsingException.class);
        assertThatThrownBy(() -> obj.getOffsetDateTime("missing")).isInstanceOf(ParsingException.class);
    }

    @Test
    void testDataArrayAccessorsAndCoercions() {
        DataArray arr = DataArray.empty()
                .add("hello")
                .add("123456789012345678")
                .add(42)
                .add(9876543210L)
                .add(3.14159)
                .add(true)
                .add("2026-08-22T21:00:00.000Z")
                .add(DataObject.empty().put("key", "val"))
                .add(DataArray.empty().add("sub"));

        assertThat(arr.getString(0)).isEqualTo("hello");
        assertThat(arr.getLong(1)).isEqualTo(123456789012345678L);
        assertThat(arr.getUnsignedLong(1)).isEqualTo(123456789012345678L);
        assertThat(arr.getInt(2)).isEqualTo(42);
        assertThat(arr.getLong(3)).isEqualTo(9876543210L);
        assertThat(arr.getDouble(4)).isEqualTo(3.14159);
        assertThat(arr.getBoolean(5)).isTrue();
        assertThat(arr.getOffsetDateTime(6)).isEqualTo(OffsetDateTime.of(2026, 8, 22, 21, 0, 0, 0, ZoneOffset.UTC));

        assertThat(arr.getObject(7).getString("key")).isEqualTo("val");
        assertThat(arr.optObject(7)).isPresent();
        assertThat(arr.optObject(99)).isEmpty();

        assertThat(arr.getArray(8).getString(0)).isEqualTo("sub");
        assertThat(arr.optArray(8)).isPresent();
        assertThat(arr.optArray(99)).isEmpty();

        // Defaults
        assertThat(arr.getString(99, "fallback")).isEqualTo("fallback");
        assertThat(arr.getLong(99, 123L)).isEqualTo(123L);
        assertThat(arr.getUnsignedLong(99, 123L)).isEqualTo(123L);
        assertThat(arr.getInt(99, 456)).isEqualTo(456);
        assertThat(arr.getUnsignedInt(99, 456)).isEqualTo(456);
        assertThat(arr.getDouble(99, 7.89)).isEqualTo(7.89);
        assertThat(arr.getBoolean(99, true)).isTrue();
        assertThat(arr.getOffsetDateTime(99, null)).isNull();
    }

    @Test
    void testDirectByteBufJsonParsing() {
        String json = "{\"user_id\":\"987654321\",\"count\":5,\"enabled\":true}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        // Direct buffer without backing array
        ByteBuf directBuf = Unpooled.directBuffer(bytes.length);
        try {
            directBuf.writeBytes(bytes);
            assertThat(directBuf.hasArray()).isFalse();

            Map<String, Object> map = SerializationUtil.fromJson(SerializationUtil.getMapType(), directBuf);
            assertThat(map).containsEntry("user_id", "987654321");
            assertThat(map).containsEntry("count", 5);
            assertThat(map).containsEntry("enabled", true);
        } finally {
            directBuf.release();
        }
    }

    @Test
    void testEtfByteBufAndByteBuffer() {
        DataObject original = DataObject.empty()
                .put("id", "123456789")
                .put("name", "test-guild")
                .put("count", 10);

        byte[] etfBytes = original.toETF();

        // Test fromETF(byte[])
        DataObject fromBytes = DataObject.fromETF(etfBytes);
        assertThat(fromBytes.getString("id")).isEqualTo("123456789");
        assertThat(fromBytes.getString("name")).isEqualTo("test-guild");
        assertThat(fromBytes.getInt("count")).isEqualTo(10);

        // Test fromETF(ByteBuffer)
        DataObject fromByteBuffer = DataObject.fromETF(ByteBuffer.wrap(etfBytes));
        assertThat(fromByteBuffer.getString("id")).isEqualTo("123456789");
        assertThat(fromByteBuffer.getString("name")).isEqualTo("test-guild");

        // Test fromETF(ByteBuf)
        ByteBuf directEtf = Unpooled.directBuffer(etfBytes.length);
        try {
            directEtf.writeBytes(etfBytes);
            DataObject fromByteBuf = DataObject.fromETF(directEtf);
            assertThat(fromByteBuf.getString("id")).isEqualTo("123456789");
            assertThat(fromByteBuf.getString("name")).isEqualTo("test-guild");
            assertThat(fromByteBuf.getInt("count")).isEqualTo(10);
        } finally {
            directEtf.release();
        }

        // Test DataArray fromETF
        DataArray arr = DataArray.empty().add("val1").add("val2");
        byte[] arrEtfBytes = arr.toETF();

        DataArray fromArrBytes = DataArray.fromETF(arrEtfBytes);
        assertThat(fromArrBytes.getString(0)).isEqualTo("val1");

        DataArray fromArrBuffer = DataArray.fromETF(ByteBuffer.wrap(arrEtfBytes));
        assertThat(fromArrBuffer.getString(1)).isEqualTo("val2");

        ByteBuf directArrEtf = Unpooled.directBuffer(arrEtfBytes.length);
        try {
            directArrEtf.writeBytes(arrEtfBytes);
            DataArray fromArrByteBuf = DataArray.fromETF(directArrEtf);
            assertThat(fromArrByteBuf.getString(0)).isEqualTo("val1");
            assertThat(fromArrByteBuf.getString(1)).isEqualTo("val2");
        } finally {
            directArrEtf.release();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExTermDecoderAndEncoderDirectByteBuf() {
        // Test direct ByteBuf encoding with ExTermEncoder
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeByte(131);
            Map<String, Object> data = new HashMap<>();
            data.put("snowflake", 123456789012345678L);
            data.put("name", "antigravity");
            data.put("flag", true);
            data.put("nilValue", null);
            data.put("score", 99.5);
            data.put("intArr", new int[] {1, 2, 3});
            data.put("longArr", new long[] {100L, 200L});

            ExTermEncoder.pack(buf, data);

            // Test decoding directly with ExTermDecoder
            Map<String, Object> decoded = ExTermDecoder.unpackMap(buf);
            assertThat(decoded).containsEntry("snowflake", 123456789012345678L);
            assertThat(decoded).containsEntry("name", "antigravity");
            assertThat(decoded).containsEntry("flag", true);
            assertThat(decoded).containsEntry("nilValue", null);
            assertThat(decoded).containsEntry("score", 99.5);
            assertThat((List<Object>) decoded.get("intArr")).containsExactly(1, 2, 3);
            assertThat((List<Object>) decoded.get("longArr")).containsExactly(100, 200);
        } finally {
            buf.release();
        }
    }

    @Test
    void testSerializationUtilWriteJsonDirectByteBuf() {
        ByteBuf buf = Unpooled.buffer();
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("op", 2);
            data.put("token", "secret_token");
            data.put("compress", true);

            SerializationUtil.writeJson(buf, data);

            // Read directly via DataObject.fromJson(buf)
            DataObject obj = DataObject.fromJson(buf);
            assertThat(obj.getInt("op")).isEqualTo(2);
            assertThat(obj.getString("token")).isEqualTo("secret_token");
            assertThat(obj.getBoolean("compress")).isTrue();
        } finally {
            buf.release();
        }
    }

    @Test
    void testExTermEncoderPackReturnsByteBuf() {
        Map<String, Object> data = new HashMap<>();
        data.put("key", "value");
        ByteBuf packed = ExTermEncoder.pack(data);
        try {
            assertThat(packed).isNotNull();
            assertThat(packed.readableBytes()).isGreaterThan(0);
            Map<String, Object> unpacked = ExTermDecoder.unpackMap(packed);
            assertThat(unpacked).containsEntry("key", "value");
        } finally {
            packed.release();
        }
    }

    @Test
    void testStringDeduplicationInJsonAndEtf() {
        String dynVal = "dyn_val_" + System.nanoTime();
        String json1 = "{\"guild_id\": \"" + dynVal + "\", \"status\": \"online\"}";
        String json2 = "{\"guild_id\": \"" + dynVal + "\", \"status\": \"online\"}";

        // Test JSON string deduplication enabled
        DataObject obj1 = DataObject.fromJson(json1, true);
        DataObject obj2 = DataObject.fromJson(json2, true);
        assertThat(obj1.getString("guild_id")).isSameAs(obj2.getString("guild_id"));

        // Test nested JSON keys and values
        String nestedJson1 = "{\"t\": \"PRESENCE_UPDATE\", \"d\": {\"user\": {\"id\": \"" + dynVal
                + "\"}, \"status\": \"dnd\", \"activities\": [\"" + dynVal + "\"]}}";
        String nestedJson2 = "{\"t\": \"PRESENCE_UPDATE\", \"d\": {\"user\": {\"id\": \"" + dynVal
                + "\"}, \"status\": \"dnd\", \"activities\": [\"" + dynVal + "\"]}}";
        DataObject nested1 = DataObject.fromJson(nestedJson1, true);
        DataObject nested2 = DataObject.fromJson(nestedJson2, true);
        assertThat(nested1.getObject("d").getObject("user").getString("id"))
                .isSameAs(nested2.getObject("d").getObject("user").getString("id"));
        assertThat(nested1.getObject("d").getString("status"))
                .isSameAs(nested2.getObject("d").getString("status"));
        assertThat(nested1.getObject("d").getArray("activities").getString(0))
                .isSameAs(nested2.getObject("d").getArray("activities").getString(0));

        // Test JSON string deduplication disabled
        DataObject nonDedup1 = DataObject.fromJson(json1, false);
        DataObject nonDedup2 = DataObject.fromJson(json2, false);
        assertThat(nonDedup1.getString("guild_id")).isNotSameAs(nonDedup2.getString("guild_id"));

        // Test ETF string deduplication enabled
        String dynRole = "role_" + System.nanoTime();
        Map<String, Object> map1 = new HashMap<>();
        map1.put("role_id", dynRole);
        Map<String, Object> map2 = new HashMap<>();
        map2.put("role_id", dynRole);

        ByteBuf etfBuf1 = ExTermEncoder.pack(map1);
        ByteBuf etfBuf2 = ExTermEncoder.pack(map2);
        try {
            Map<String, Object> etf1 = ExTermDecoder.unpackMap(etfBuf1, true);
            Map<String, Object> etf2 = ExTermDecoder.unpackMap(etfBuf2, true);
            assertThat((String) etf1.get("role_id")).isSameAs((String) etf2.get("role_id"));
        } finally {
            etfBuf1.release();
            etfBuf2.release();
        }

        // Test ETF string deduplication disabled
        ByteBuf etfPlain1 = ExTermEncoder.pack(map1);
        ByteBuf etfPlain2 = ExTermEncoder.pack(map2);
        try {
            Map<String, Object> etf1 = ExTermDecoder.unpackMap(etfPlain1, false);
            Map<String, Object> etf2 = ExTermDecoder.unpackMap(etfPlain2, false);
            assertThat((String) etf1.get("role_id")).isNotSameAs((String) etf2.get("role_id"));
        } finally {
            etfPlain1.release();
            etfPlain2.release();
        }
    }
}
