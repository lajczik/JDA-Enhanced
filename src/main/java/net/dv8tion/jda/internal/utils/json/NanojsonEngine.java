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

import com.grack.nanojson.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.data.SerializableArray;
import net.dv8tion.jda.api.utils.data.SerializableData;
import net.dv8tion.jda.internal.utils.Checks;

import java.io.*;
import java.util.*;

import javax.annotation.Nonnull;

/**
 * Default JSON engine implementation using the lightweight {@code nanojson} library.
 */
public class NanojsonEngine implements JsonEngine {
    private static final String TRUNCATED_ARRAY = "[…truncated array…]";
    private static final String TRUNCATED_OBJECT = "{…truncated object…}";
    private static volatile boolean jacksonCheckDone = false;
    private static volatile JsonEngine fallbackEngine = null;

    private static JsonEngine getFallbackEngine() {
        if (!jacksonCheckDone) {
            synchronized (NanojsonEngine.class) {
                if (!jacksonCheckDone) {
                    try {
                        Class.forName(
                                "tools.jackson.databind.ObjectMapper", false, NanojsonEngine.class.getClassLoader());
                        fallbackEngine = Class.forName("net.dv8tion.jda.internal.utils.json.Jackson3Engine")
                                .asSubclass(JsonEngine.class)
                                .getDeclaredConstructor()
                                .newInstance();
                    } catch (Throwable t1) {
                        try {
                            Class.forName(
                                    "com.fasterxml.jackson.databind.ObjectMapper",
                                    false,
                                    NanojsonEngine.class.getClassLoader());
                            fallbackEngine = Class.forName("net.dv8tion.jda.internal.utils.json.Jackson2Engine")
                                    .asSubclass(JsonEngine.class)
                                    .getDeclaredConstructor()
                                    .newInstance();
                        } catch (Throwable t2) {
                            fallbackEngine = null;
                        }
                    }
                    jacksonCheckDone = true;
                }
            }
        }
        return fallbackEngine;
    }

    @Nonnull
    @Override
    public String getName() {
        return "nanojson";
    }

    @Nonnull
    @Override
    public byte[] toJson(@Nonnull Object data) {
        Checks.notNull(data, "Data");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            JsonAppendableWriter writer = JsonWriter.on(out);
            writer.value(data);
            writer.done();
            return out.toByteArray();
        } catch (JsonWriterException | IllegalArgumentException ex) {
            JsonEngine fallback = getFallbackEngine();
            if (fallback != null) {
                return fallback.toJson(data);
            }
            throw new ParsingException(
                    "Failed to serialize to JSON bytes (nanojson does not support "
                            + data.getClass().getName() + ")",
                    ex);
        } catch (ParsingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ParsingException("Failed to serialize to JSON bytes", ex);
        }
    }

    @Nonnull
    @Override
    public String toJsonString(@Nonnull Object data, boolean pretty) {
        Checks.notNull(data, "Data");
        if (pretty) {
            JsonEngine fallback = getFallbackEngine();
            if (fallback != null) {
                return fallback.toJsonString(data, true);
            }
            try {
                JsonStringWriter writer = JsonWriter.indent("  ").string();
                writer.value(sortMapKeys(data));
                return writer.done();
            } catch (JsonWriterException | IllegalArgumentException ex) {
                throw new ParsingException(
                        "Failed to serialize to JSON string (nanojson does not support "
                                + data.getClass().getName() + ")",
                        ex);
            } catch (Exception ex) {
                throw new ParsingException("Failed to serialize to JSON string", ex);
            }
        }
        try {
            JsonStringWriter writer = JsonWriter.string();
            writer.value(data);
            return writer.done();
        } catch (JsonWriterException | IllegalArgumentException ex) {
            JsonEngine fallback = getFallbackEngine();
            if (fallback != null) {
                return fallback.toJsonString(data, false);
            }
            throw new ParsingException(
                    "Failed to serialize to JSON string (nanojson does not support "
                            + data.getClass().getName() + ")",
                    ex);
        } catch (Exception ex) {
            throw new ParsingException("Failed to serialize to JSON string", ex);
        }
    }

    @Override
    public void writeJson(@Nonnull OutputStream out, @Nonnull Object data) {
        Checks.notNull(out, "OutputStream");
        Checks.notNull(data, "Data");
        try {
            byte[] bytes = toJson(data);
            out.write(bytes);
        } catch (IOException ex) {
            throw new ParsingException("Failed to write JSON to OutputStream", ex);
        }
    }

    @Override
    public void writeJson(@Nonnull ByteBuf target, @Nonnull Object data) {
        Checks.notNull(target, "ByteBuf");
        Checks.notNull(data, "Data");
        byte[] bytes = toJson(data);
        target.writeBytes(bytes);
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull byte[] data) {
        Checks.notNull(data, "Data");
        return fromJsonMap(new ByteArrayInputStream(data));
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull ByteBuf data) {
        Checks.notNull(data, "Data");
        try (ByteBufInputStream in = new ByteBufInputStream(data)) {
            return fromJsonMap((InputStream) in);
        } catch (ParsingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ParsingException("Failed to parse JSON map from ByteBuf", ex);
        }
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull InputStream stream) {
        Checks.notNull(stream, "InputStream");
        try {
            return JsonParser.object().from(stream);
        } catch (Exception ex) {
            throw new ParsingException("Failed to parse JSON map from InputStream", ex);
        }
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull Reader reader) {
        Checks.notNull(reader, "Reader");
        try {
            return JsonParser.object().from(reader);
        } catch (Exception ex) {
            throw new ParsingException("Failed to parse JSON map from Reader", ex);
        }
    }

    @Nonnull
    @Override
    public Map<String, Object> fromJsonMap(@Nonnull String json) {
        Checks.notNull(json, "JSON String");
        try {
            return JsonParser.object().from(json);
        } catch (Exception ex) {
            throw new ParsingException("Failed to parse JSON map from String", ex);
        }
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull byte[] data) {
        Checks.notNull(data, "Data");
        return fromJsonList(new ByteArrayInputStream(data));
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull ByteBuf data) {
        Checks.notNull(data, "Data");
        try (ByteBufInputStream in = new ByteBufInputStream(data)) {
            return fromJsonList((InputStream) in);
        } catch (ParsingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ParsingException("Failed to parse JSON list from ByteBuf", ex);
        }
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull InputStream stream) {
        Checks.notNull(stream, "InputStream");
        try {
            return JsonParser.array().from(stream);
        } catch (Exception ex) {
            throw new ParsingException("Failed to parse JSON list from InputStream", ex);
        }
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull Reader reader) {
        Checks.notNull(reader, "Reader");
        try {
            return JsonParser.array().from(reader);
        } catch (Exception ex) {
            throw new ParsingException("Failed to parse JSON list from Reader", ex);
        }
    }

    @Nonnull
    @Override
    public List<Object> fromJsonList(@Nonnull String json) {
        Checks.notNull(json, "JSON String");
        try {
            return JsonParser.array().from(json);
        } catch (Exception ex) {
            throw new ParsingException("Failed to parse JSON list from String", ex);
        }
    }

    @Nonnull
    @Override
    @SuppressWarnings("unchecked")
    public <T> T fromJson(@Nonnull Class<T> clazz, @Nonnull byte[] data) {
        Checks.notNull(clazz, "Class");
        Checks.notNull(data, "Data");
        if (Map.class.isAssignableFrom(clazz)) {
            return (T) fromJsonMap(data);
        } else if (List.class.isAssignableFrom(clazz)) {
            return (T) fromJsonList(data);
        }
        JsonEngine fallback = getFallbackEngine();
        if (fallback != null) {
            return fallback.fromJson(clazz, data);
        }
        throw new UnsupportedOperationException("nanojson does not support POJO deserialization for " + clazz.getName()
                + ". Consider adding Jackson 3.x or 2.x to your classpath.");
    }

    @Nonnull
    @Override
    public String toShallowJsonString(@Nonnull Object object) {
        Checks.notNull(object, "Object");
        JsonEngine fallback = getFallbackEngine();
        if (fallback != null) {
            return fallback.toShallowJsonString(object);
        }
        try {
            Object pruned = pruneOneLevel(object);
            JsonStringWriter writer = JsonWriter.string();
            writer.value(pruned);
            return writer.done().replace("\\u2026", "…");
        } catch (Exception ex) {
            throw new ParsingException("Failed to serialize shallow JSON string", ex);
        }
    }

    private Object sortMapKeys(Object data) {
        if (data instanceof SerializableData sd) {
            data = sd.toData().toMap();
        } else if (data instanceof SerializableArray sa) {
            data = sa.toDataArray().toList();
        }
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortMapKeys(entry.getValue()));
            }
            return sorted;
        } else if (data instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(sortMapKeys(item));
            }
            return copy;
        }
        return data;
    }

    private Object pruneOneLevel(Object obj) {
        if (obj instanceof SerializableData sd) {
            obj = sd.toData().toMap();
        } else if (obj instanceof SerializableArray sa) {
            obj = sa.toDataArray().toList();
        }
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object val = e.getValue();
                if (val instanceof Map || val instanceof SerializableData) {
                    out.put(key, TRUNCATED_OBJECT);
                } else if (val instanceof Iterable
                        || val instanceof SerializableArray
                        || (val != null && val.getClass().isArray())) {
                    out.put(key, TRUNCATED_ARRAY);
                } else {
                    out.put(key, val);
                }
            }
            return out;
        } else if (obj instanceof Iterable<?> iter) {
            List<Object> out = new ArrayList<>();
            for (Object val : iter) {
                if (val instanceof Map || val instanceof SerializableData) {
                    out.add(TRUNCATED_OBJECT);
                } else if (val instanceof Iterable
                        || val instanceof SerializableArray
                        || (val != null && val.getClass().isArray())) {
                    out.add(TRUNCATED_ARRAY);
                } else {
                    out.add(val);
                }
            }
            return out;
        }
        return obj;
    }
}
