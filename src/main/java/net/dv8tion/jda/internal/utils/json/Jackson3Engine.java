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

package net.dv8tion.jda.internal.utils.json;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.internal.utils.Checks;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.MapType;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * JSON engine implementation using Jackson 3.x ({@code tools.jackson}).
 */
public class Jackson3Engine implements JsonEngine {
    private static final String TRUNCATED_ARRAY = "[…truncated array…]";
    private static final String TRUNCATED_OBJECT = "{…truncated object…}";

    private final ObjectMapper mapper;
    private final MapType mapType;
    private final CollectionType listType;

    public Jackson3Engine() {
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(Map.class, HashMap.class);
        module.addAbstractTypeMapping(List.class, ArrayList.class);
        this.mapper = JsonMapper.builder().addModule(module).build();
        this.mapType = mapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class);
        this.listType = mapper.getTypeFactory().constructRawCollectionType(ArrayList.class);
    }

    @Nonnull
    @Override
    public String getName() {
        return "Jackson 3.x";
    }

    @Nonnull
    public ObjectMapper getMapper() {
        return mapper;
    }

    @Nonnull
    public MapType getMapType() {
        return mapType;
    }

    @Nonnull
    public CollectionType getListType() {
        return listType;
    }

    @Nonnull
    @Override
    public byte[] toJson(@Nonnull Object data) {
        Checks.notNull(data, "Data");
        try {
            return mapper.writeValueAsBytes(data);
        } catch (JacksonException ex) {
            throw new ParsingException(ex);
        }
    }

    @Nonnull
    @Override
    public String toJsonString(@Nonnull Object data, boolean pretty) {
        Checks.notNull(data, "Data");
        try {
            ObjectWriter writer = getObjectWriter(pretty);
            return writer.writeValueAsString(data);
        } catch (JacksonException ex) {
            throw new UncheckedIOException(new IOException(ex));
        }
    }

    @Nonnull
    public ObjectWriter getObjectWriter(boolean pretty) {
        return !pretty
                ? mapper.writer()
                : mapper.writerWithDefaultPrettyPrinter()
                        .with(SerializationFeature.INDENT_OUTPUT)
                        .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Override
    public void writeJson(@Nonnull OutputStream out, @Nonnull Object data) {
        Checks.notNull(out, "OutputStream");
        Checks.notNull(data, "Data");
        try {
            mapper.writeValue(out, data);
        } catch (JacksonException ex) {
            throw new ParsingException(ex);
        }
    }

    @Override
    public void writeJson(@Nonnull ByteBuf target, @Nonnull Object data) {
        Checks.notNull(target, "ByteBuf");
        Checks.notNull(data, "Data");
        try (ByteBufOutputStream out = new ByteBufOutputStream(target)) {
            mapper.writeValue((OutputStream) out, data);
        } catch (Exception ex) {
            throw new ParsingException(ex);
        }
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull byte[] data) {
        return fromJson(mapType, data);
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull ByteBuf data) {
        return fromJson(mapType, data);
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull InputStream stream) {
        return fromJson(mapType, stream);
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull Reader reader) {
        return fromJson(mapType, reader);
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull String json) {
        return fromJson(mapType, json);
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull byte[] data) {
        return fromJson(listType, data);
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull ByteBuf data) {
        return fromJson(listType, data);
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull InputStream stream) {
        return fromJson(listType, stream);
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull Reader reader) {
        return fromJson(listType, reader);
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull String json) {
        return fromJson(listType, json);
    }

    @Nonnull
    @Override
    public <T> T fromJson(@Nonnull Class<T> clazz, @Nonnull byte[] data) {
        Checks.notNull(clazz, "Class");
        return fromJson(mapper.constructType(clazz), data);
    }

    @Nonnull
    public <T> T fromJson(@Nonnull JavaType type, @Nonnull byte[] data) {
        Checks.notNull(type, "Type");
        Checks.notNull(data, "Data");
        try {
            return mapper.readValue(data, type);
        } catch (JacksonException ex) {
            throw new ParsingException(ex);
        }
    }

    @Nonnull
    public <T> T fromJson(@Nonnull JavaType type, @Nonnull ByteBuf data) {
        Checks.notNull(type, "Type");
        Checks.notNull(data, "Data");
        try (ByteBufInputStream in = new ByteBufInputStream(data)) {
            return mapper.readValue((InputStream) in, type);
        } catch (Exception ex) {
            throw new ParsingException(ex);
        }
    }

    @Nonnull
    public <T> T fromJson(@Nonnull JavaType type, @Nonnull InputStream data) {
        Checks.notNull(type, "Type");
        Checks.notNull(data, "Data");
        try {
            return mapper.readValue(data, type);
        } catch (JacksonException ex) {
            throw new ParsingException(ex);
        }
    }

    @Nonnull
    public <T> T fromJson(@Nonnull JavaType type, @Nonnull Reader data) {
        Checks.notNull(type, "Type");
        Checks.notNull(data, "Data");
        try {
            return mapper.readValue(data, type);
        } catch (JacksonException ex) {
            throw new ParsingException(ex);
        }
    }

    @Nonnull
    public <T> T fromJson(@Nonnull JavaType type, @Nonnull String data) {
        Checks.notNull(type, "Type");
        Checks.notNull(data, "Data");
        try {
            return mapper.readValue(data, type);
        } catch (JacksonException ex) {
            throw new ParsingException(ex);
        }
    }

    @Nonnull
    @Override
    public String toShallowJsonString(@Nonnull Object object) {
        Checks.notNull(object, "Object");
        try {
            JsonNode root = mapper.valueToTree(object);
            JsonNode shallowRoot = pruneOneLevel(root);
            return mapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(shallowRoot);
        } catch (JacksonException ex) {
            throw new ParsingException(ex);
        }
    }

    private JsonNode pruneOneLevel(JsonNode n) {
        if (n.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                JsonNode v = e.getValue();
                if (v.isValueNode()) {
                    out.set(e.getKey(), v);
                } else if (v.isObject()) {
                    out.put(e.getKey(), TRUNCATED_OBJECT);
                } else if (v.isArray()) {
                    out.put(e.getKey(), TRUNCATED_ARRAY);
                }
            }
            return out;
        } else if (n.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode v : n.values()) {
                if (v.isValueNode()) {
                    out.add(v);
                } else if (v.isObject()) {
                    out.add(TRUNCATED_OBJECT);
                } else if (v.isArray()) {
                    out.add(TRUNCATED_ARRAY);
                }
            }
            return out;
        }
        return n;
    }
}
