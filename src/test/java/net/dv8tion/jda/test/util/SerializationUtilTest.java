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
import net.dv8tion.jda.api.GatewayEncoding;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.utils.Compression;
import net.dv8tion.jda.api.utils.JsonEngineType;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.generated.CreateRoleRequestDto;
import net.dv8tion.jda.internal.utils.SerializationUtil;
import net.dv8tion.jda.internal.utils.config.MetaConfig;
import net.dv8tion.jda.internal.utils.config.flags.ConfigFlag;
import net.dv8tion.jda.internal.utils.config.sharding.ShardingMetaConfig;
import net.dv8tion.jda.internal.utils.json.Jackson2Engine;
import net.dv8tion.jda.internal.utils.json.Jackson3Engine;
import net.dv8tion.jda.internal.utils.json.NanojsonEngine;
import net.dv8tion.jda.internal.utils.requestbody.JsonRequestBody;
import net.dv8tion.jda.test.AbstractSnapshotTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SerializationUtilTest extends AbstractSnapshotTest {
    @Test
    void testEngineActive() {
        assertThat(SerializationUtil.getEngineName()).isEqualTo("nanojson");
        assertThat(SerializationUtil.getEngine()).isNotNull();
    }

    @Test
    void testResolveEngineExplicitOptions() {
        assertThat(SerializationUtil.resolveEngine("nanojson").getName()).isEqualTo("nanojson");
        assertThat(SerializationUtil.resolveEngine("jackson2").getName()).isEqualTo("Jackson 2.x");
        assertThat(SerializationUtil.resolveEngine("jackson3").getName()).isEqualTo("Jackson 3.x");
        assertThat(SerializationUtil.resolveEngine(null).getName()).isEqualTo("nanojson");
    }

    @Test
    void testJsonEngineTypeEnumAndSwitching() {
        assertThat(JsonEngineType.NANOJSON.isSupported()).isTrue();
        assertThat(JsonEngineType.JACKSON3.isSupported()).isTrue();
        assertThat(JsonEngineType.JACKSON2.isSupported()).isTrue();

        assertThat(JsonEngineType.fromKey("nanojson")).isEqualTo(JsonEngineType.NANOJSON);
        assertThat(JsonEngineType.fromKey("jackson3")).isEqualTo(JsonEngineType.JACKSON3);
        assertThat(JsonEngineType.fromKey("jackson2")).isEqualTo(JsonEngineType.JACKSON2);

        assertThat(JsonEngineType.NANOJSON.getDependencyExample()).isEqualTo("com.grack:nanojson");
        assertThat(JsonEngineType.JACKSON3.getDependencyExample()).isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(JsonEngineType.JACKSON2.getDependencyExample())
                .isEqualTo("com.fasterxml.jackson.core:jackson-databind");
        assertThat(JsonEngineType.JACKSON3.getDependency()).isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(JsonEngineType.JACKSON3.getSuggestedDependency()).isEqualTo("tools.jackson.core:jackson-databind");

        // Test manual engine switching
        SerializationUtil.setEngine(JsonEngineType.JACKSON3);
        assertThat(SerializationUtil.getEngineName()).isEqualTo("Jackson 3.x");

        SerializationUtil.setEngine(JsonEngineType.JACKSON2);
        assertThat(SerializationUtil.getEngineName()).isEqualTo("Jackson 2.x");

        SerializationUtil.setEngine(JsonEngineType.NANOJSON);
        assertThat(SerializationUtil.getEngineName()).isEqualTo("nanojson");
    }

    @Test
    void testJDABuilderAndShardManagerBuilderSetJsonEngine() {
        JDABuilder jdaBuilder = JDABuilder.createDefault("test-token");
        jdaBuilder.setJsonEngine(JsonEngineType.NANOJSON);

        DefaultShardManagerBuilder shardBuilder = DefaultShardManagerBuilder.createDefault("test-token");
        shardBuilder.setJsonEngine(JsonEngineType.JACKSON3);
    }

    @Test
    void testMetaConfigJsonEngine() {
        MetaConfig metaConfig = new MetaConfig(2048, null, null, ConfigFlag.getDefault(), JsonEngineType.JACKSON2);
        assertThat(metaConfig.getJsonEngine()).isEqualTo(JsonEngineType.JACKSON2);

        ShardingMetaConfig shardingMetaConfig = new ShardingMetaConfig(
                2048,
                null,
                null,
                ConfigFlag.getDefault(),
                Compression.NONE,
                GatewayEncoding.JSON,
                JsonEngineType.JACKSON3);
        assertThat(shardingMetaConfig.getJsonEngine()).isEqualTo(JsonEngineType.JACKSON3);
    }

    @Test
    void testToJson() {
        CreateRoleRequestDto request =
                new CreateRoleRequestDto().setName("test").setMentionable(true).setIcon(null);

        byte[] json = SerializationUtil.toJson(request);
        assertThat(json).isNotEmpty();

        assertWithSnapshot(DataObject.fromJson(Unpooled.wrappedBuffer(json)));
    }

    @Test
    void testJsonRequestBody() throws Exception {
        CreateRoleRequestDto original = new CreateRoleRequestDto().setName("Test role");
        JsonRequestBody body = new JsonRequestBody(original);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        body.writeTo(outputStream);

        Map<String, Object> output = SerializationUtil.fromJson(
                SerializationUtil.getMapType(), Unpooled.wrappedBuffer(outputStream.toByteArray()));
        assertThat(output.get("name")).isEqualTo("Test role");
    }

    @Test
    void testFromJsonByteBuf() {
        String json = "{\"name\":\"jda-bot\",\"active\":true,\"count\":42}";
        ByteBuf buf = Unpooled.wrappedBuffer(json.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> map = SerializationUtil.fromJson(SerializationUtil.getMapType(), buf);
        assertThat(map).containsEntry("name", "jda-bot");
        assertThat(map).containsEntry("active", true);
        assertThat(map).containsEntry("count", 42);
    }

    @Test
    void testFromJsonInputStreamAndReader() {
        String json = "[\"first\", \"second\", \"third\"]";

        List<Object> fromStream = SerializationUtil.fromJson(
                SerializationUtil.getListType(), new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(fromStream).containsExactly("first", "second", "third");

        List<Object> fromReader = SerializationUtil.fromJson(SerializationUtil.getListType(), new StringReader(json));
        assertThat(fromReader).containsExactly("first", "second", "third");
    }

    @Test
    void testFromJsonString() {
        String json = "{\"key\":\"value\"}";
        Map<String, Object> map = SerializationUtil.fromJson(SerializationUtil.getMapType(), json);
        assertThat(map).containsEntry("key", "value");
    }

    @Test
    void testToJsonStringPrettyAndCompact() {
        Map<String, Object> data = new HashMap<>();
        data.put("b", 2);
        data.put("a", 1);

        String compact = SerializationUtil.toJsonString(data, false);
        assertThat(compact).doesNotContain("\n");

        String pretty = SerializationUtil.toJsonString(data, true);
        assertThat(pretty).contains("\n");
        // Keys should be ordered alphabetically
        assertThat(pretty.indexOf("\"a\"")).isLessThan(pretty.indexOf("\"b\""));
    }

    @Test
    void testToShallowJsonString() {
        Map<String, Object> root = new HashMap<>();
        root.put("primitive", "value");

        Map<String, Object> nestedObj = new HashMap<>();
        nestedObj.put("innerKey", "innerValue");
        root.put("nested", nestedObj);

        List<String> nestedList = List.of("item1", "item2");
        root.put("list", nestedList);

        String shallow = SerializationUtil.toShallowJsonString(root);
        assertThat(shallow).contains("\"primitive\":\"value\"");
        assertThat(shallow).contains("truncated object");
        assertThat(shallow).contains("truncated array");
    }

    @Test
    void testInvalidJsonThrowsParsingException() {
        String invalidJson = "{invalid json}";
        assertThatThrownBy(() -> SerializationUtil.fromJson(SerializationUtil.getMapType(), invalidJson))
                .isInstanceOf(ParsingException.class);

        ByteBuf invalidBuf = Unpooled.wrappedBuffer(invalidJson.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> SerializationUtil.fromJson(SerializationUtil.getMapType(), invalidBuf))
                .isInstanceOf(ParsingException.class);
    }

    // --- Direct Tests for NanojsonEngine ---

    @Test
    void testNanojsonEngineSerializationAndDeserialization() {
        NanojsonEngine engine = new NanojsonEngine();
        assertThat(engine.getName()).isEqualTo("nanojson");

        Map<String, Object> map = new HashMap<>();
        map.put("b", 2);
        map.put("a", 1);

        // Compact
        String compact = engine.toJsonString(map, false);
        assertThat(compact).doesNotContain("\n");

        // Pretty
        String pretty = engine.toJsonString(map, true);
        assertThat(pretty).contains("\n");
        assertThat(pretty.indexOf("\"a\"")).isLessThan(pretty.indexOf("\"b\""));

        // Bytes
        byte[] bytes = engine.toJson(map);
        assertThat(bytes).isNotEmpty();

        // From String
        Map<String, Object> parsedMap = engine.fromJsonMap(compact);
        assertThat(parsedMap).containsEntry("a", 1).containsEntry("b", 2);

        // From ByteBuf
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        Map<String, Object> bufMap = engine.fromJsonMap(buf);
        assertThat(bufMap).containsEntry("a", 1).containsEntry("b", 2);

        // From InputStream & Reader
        Map<String, Object> streamMap = engine.fromJsonMap(new ByteArrayInputStream(bytes));
        assertThat(streamMap).containsEntry("a", 1);
        Map<String, Object> readerMap = engine.fromJsonMap(new StringReader(compact));
        assertThat(readerMap).containsEntry("a", 1);

        // List parsing
        String jsonList = "[\"one\", \"two\"]";
        List<Object> list = engine.fromJsonList(jsonList);
        assertThat(list).containsExactly("one", "two");

        // ByteBuf writing
        ByteBuf target = Unpooled.buffer();
        try {
            engine.writeJson(target, map);
            assertThat(target.readableBytes()).isGreaterThan(0);
        } finally {
            target.release();
        }

        // Shallow string
        Map<String, Object> root = new HashMap<>();
        root.put("primitive", "val");
        root.put("nested", Map.of("key", "val"));
        root.put("list", List.of("x", "y"));

        String shallow = engine.toShallowJsonString(root);
        assertThat(shallow).contains("\"primitive\":\"val\"");
        assertThat(shallow).contains("truncated object");
        assertThat(shallow).contains("truncated array");

        // Error handling
        assertThatThrownBy(() -> engine.fromJsonMap("{invalid json}")).isInstanceOf(ParsingException.class);
    }

    // --- Direct Tests for Jackson2Engine ---

    @Test
    void testJackson2EngineSerializationAndDeserialization() {
        Jackson2Engine engine = new Jackson2Engine();
        assertThat(engine.getName()).isEqualTo("Jackson 2.x");

        Map<String, Object> map = new HashMap<>();
        map.put("b", 2);
        map.put("a", 1);

        String compact = engine.toJsonString(map, false);
        assertThat(compact).doesNotContain("\n");

        String pretty = engine.toJsonString(map, true);
        assertThat(pretty).contains("\n");
        assertThat(pretty.indexOf("\"a\"")).isLessThan(pretty.indexOf("\"b\""));

        byte[] bytes = engine.toJson(map);
        assertThat(bytes).isNotEmpty();

        Map<String, Object> parsedMap = engine.fromJsonMap(compact);
        assertThat(parsedMap).containsEntry("a", 1).containsEntry("b", 2);

        Map<String, Object> streamMap = engine.fromJsonMap(new ByteArrayInputStream(bytes));
        assertThat(streamMap).containsEntry("a", 1);

        List<Object> list = engine.fromJsonList("[\"alpha\", \"beta\"]");
        assertThat(list).containsExactly("alpha", "beta");

        Map<String, Object> root = new HashMap<>();
        root.put("primitive", "val");
        root.put("nested", Map.of("key", "val"));
        root.put("list", List.of("x", "y"));

        String shallow = engine.toShallowJsonString(root);
        assertThat(shallow).contains("\"nested\":\"{…truncated object…}\"");
        assertThat(shallow).contains("\"list\":\"[…truncated array…]\"");

        assertThatThrownBy(() -> engine.fromJsonMap("{invalid}")).isInstanceOf(ParsingException.class);
    }

    // --- Direct Tests for Jackson3Engine ---

    @Test
    void testJackson3EngineSerializationAndDeserialization() {
        Jackson3Engine engine = new Jackson3Engine();
        assertThat(engine.getName()).isEqualTo("Jackson 3.x");

        Map<String, Object> map = new HashMap<>();
        map.put("b", 2);
        map.put("a", 1);

        String compact = engine.toJsonString(map, false);
        assertThat(compact).doesNotContain("\n");

        String pretty = engine.toJsonString(map, true);
        assertThat(pretty).contains("\n");
        assertThat(pretty.indexOf("\"a\"")).isLessThan(pretty.indexOf("\"b\""));

        byte[] bytes = engine.toJson(map);
        assertThat(bytes).isNotEmpty();

        Map<String, Object> parsedMap = engine.fromJsonMap(compact);
        assertThat(parsedMap).containsEntry("a", 1).containsEntry("b", 2);

        List<Object> list = engine.fromJsonList("[\"x\", \"y\"]");
        assertThat(list).containsExactly("x", "y");

        assertThatThrownBy(() -> engine.fromJsonMap("{invalid}")).isInstanceOf(ParsingException.class);
    }
}
