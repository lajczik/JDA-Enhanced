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

package net.dv8tion.jda.internal.utils;

import io.netty.buffer.ByteBuf;
import net.dv8tion.jda.api.utils.JsonEngineType;
import net.dv8tion.jda.internal.utils.json.Jackson3Engine;
import net.dv8tion.jda.internal.utils.json.JsonEngine;
import net.dv8tion.jda.internal.utils.json.NanojsonEngine;
import org.slf4j.Logger;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.MapType;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

public class SerializationUtil {
    private static final Logger log = JDALogger.getLog(SerializationUtil.class);
    private static volatile JsonEngine ENGINE = resolveEngine(System.getProperty("net.dv8tion.jda.json.engine"));

    public static JsonEngine resolveEngine(String forced) {
        if (forced != null && !forced.isBlank()) {
            JsonEngineType type = JsonEngineType.fromKey(forced.trim());
            if (type != null) {
                if (type.isSupported()) {
                    log.info("Using {} for JSON serialization (configured via system property)", type.getKey());
                    return type.createEngine();
                } else {
                    throw new IllegalStateException("JSON engine '" + type
                            + "' explicitly requested but not available on classpath (missing dependency: "
                            + type.getDependencyExample() + ")");
                }
            } else {
                log.warn(
                        "Unknown JSON engine requested via system property: {}. Falling back to default (nanojson).",
                        forced);
            }
        }

        // Default from the beginning is nanojson
        log.debug("Using nanojson for JSON serialization (default)");
        return new NanojsonEngine();
    }

    /**
     * Changes the active JSON engine to the specified {@link JsonEngineType}.
     *
     * @param  type
     *         The {@link JsonEngineType} to use
     *
     * @throws IllegalArgumentException
     *         If null is provided
     * @throws IllegalStateException
     *         If the requested engine is not supported on this classpath
     */
    public static void setEngine(@Nonnull JsonEngineType type) {
        Checks.notNull(type, "JsonEngineType");
        setEngine(type.createEngine());
    }

    /**
     * Changes the active JSON engine to the specified {@link JsonEngine}.
     *
     * @param  engine
     *         The {@link JsonEngine} instance to use
     *
     * @throws IllegalArgumentException
     *         If null is provided
     */
    public static void setEngine(@Nonnull JsonEngine engine) {
        Checks.notNull(engine, "JsonEngine");
        log.info("Switching JSON engine to {}", engine.getName());
        ENGINE = engine;
    }

    @SuppressWarnings("UnsafeReflectiveConstructionCast")
    private static JsonEngine instantiateEngine(String className) throws Exception {
        Class<? extends JsonEngine> clazz = Class.forName(className).asSubclass(JsonEngine.class);
        return clazz.getDeclaredConstructor().newInstance();
    }

    // Returns the active JsonEngine used by this serializer.
    @Nonnull
    public static JsonEngine getEngine() {
        return ENGINE;
    }

    // Returns the human-readable name of the active JSON engine.
    @Nonnull
    public static String getEngineName() {
        return ENGINE.getName();
    }

    @Nonnull
    public static byte[] toJson(@Nonnull Object data) {
        Checks.notNull(data, "Data");
        return ENGINE.toJson(data);
    }

    @Nonnull
    public static String toJsonString(@Nonnull Object data, boolean pretty) {
        Checks.notNull(data, "Data");
        return ENGINE.toJsonString(data, pretty);
    }

    public static void writeJson(@Nonnull OutputStream out, @Nonnull Object data) {
        Checks.notNull(out, "OutputStream");
        Checks.notNull(data, "Data");
        ENGINE.writeJson(out, data);
    }

    public static void writeJson(@Nonnull ByteBuf target, @Nonnull Object data) {
        Checks.notNull(target, "ByteBuf");
        Checks.notNull(data, "Data");
        ENGINE.writeJson(target, data);
    }

    @Nonnull
    public static <T> T fromJson(@Nonnull Class<T> clazz, @Nonnull byte[] data) {
        Checks.notNull(clazz, "Class");
        Checks.notNull(data, "Data");
        return ENGINE.fromJson(clazz, data);
    }

    @SuppressWarnings("unchecked")
    private static void deduplicateMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            switch (entry.getValue()) {
                case String s -> entry.setValue(s.intern());
                case Map<?, ?> m -> deduplicateMap((Map<String, Object>) m);
                case List<?> l -> deduplicateList((List<Object>) l);
                case null, default -> {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void deduplicateList(List<Object> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            switch (list.get(i)) {
                case String s -> list.set(i, s.intern());
                case Map<?, ?> m -> deduplicateMap((Map<String, Object>) m);
                case List<?> l -> deduplicateList((List<Object>) l);
                case null, default -> {}
            }
        }
    }

    @Nonnull
    public static Map<String, Object> fromJsonMap(@Nonnull ByteBuf data, boolean deduplicateStrings) {
        Checks.notNull(data, "ByteBuf");
        Map<String, Object> map = ENGINE.fromJsonMap(data);
        if (deduplicateStrings) {
            deduplicateMap(map);
        }
        return map;
    }

    @Nonnull
    public static Map<String, Object> fromJsonMap(@Nonnull String json, boolean deduplicateStrings) {
        Checks.notNull(json, "JSON String");
        Map<String, Object> map = ENGINE.fromJsonMap(json);
        if (deduplicateStrings) {
            deduplicateMap(map);
        }
        return map;
    }

    @Nonnull
    public static Map<String, Object> fromJsonMap(@Nonnull InputStream stream, boolean deduplicateStrings) {
        Checks.notNull(stream, "InputStream");
        Map<String, Object> map = ENGINE.fromJsonMap(stream);
        if (deduplicateStrings) {
            deduplicateMap(map);
        }
        return map;
    }

    @Nonnull
    public static Map<String, Object> fromJsonMap(@Nonnull Reader reader, boolean deduplicateStrings) {
        Checks.notNull(reader, "Reader");
        Map<String, Object> map = ENGINE.fromJsonMap(reader);
        if (deduplicateStrings) {
            deduplicateMap(map);
        }
        return map;
    }

    @Nonnull
    public static List<Object> fromJsonList(@Nonnull ByteBuf data, boolean deduplicateStrings) {
        Checks.notNull(data, "ByteBuf");
        List<Object> list = ENGINE.fromJsonList(data);
        if (deduplicateStrings) {
            deduplicateList(list);
        }
        return list;
    }

    @Nonnull
    public static List<Object> fromJsonList(@Nonnull String json, boolean deduplicateStrings) {
        Checks.notNull(json, "JSON String");
        List<Object> list = ENGINE.fromJsonList(json);
        if (deduplicateStrings) {
            deduplicateList(list);
        }
        return list;
    }

    @Nonnull
    public static List<Object> fromJsonList(@Nonnull InputStream stream, boolean deduplicateStrings) {
        Checks.notNull(stream, "InputStream");
        List<Object> list = ENGINE.fromJsonList(stream);
        if (deduplicateStrings) {
            deduplicateList(list);
        }
        return list;
    }

    @Nonnull
    public static List<Object> fromJsonList(@Nonnull Reader reader, boolean deduplicateStrings) {
        Checks.notNull(reader, "Reader");
        List<Object> list = ENGINE.fromJsonList(reader);
        if (deduplicateStrings) {
            deduplicateList(list);
        }
        return list;
    }

    @Nonnull
    public static String toShallowJsonString(@Nonnull Object object) {
        Checks.notNull(object, "Object");
        return ENGINE.toShallowJsonString(object);
    }

    // --- Backwards Compatibility for Jackson 3 Types ---

    private static Jackson3Engine getOrLoadJackson3() {
        if (ENGINE instanceof Jackson3Engine j3) {
            return j3;
        }
        try {
            return (Jackson3Engine) instantiateEngine("net.dv8tion.jda.internal.utils.json.Jackson3Engine");
        } catch (Exception e) {
            throw new UnsupportedOperationException("Jackson 3.x is not available on the classpath", e);
        }
    }

    @Nonnull
    public static MapType getMapType() {
        return getOrLoadJackson3().getMapType();
    }

    @Nonnull
    public static CollectionType getListType() {
        return getOrLoadJackson3().getListType();
    }

    @Nonnull
    public static ObjectWriter getObjectWriter(boolean pretty) {
        return getOrLoadJackson3().getObjectWriter(pretty);
    }

    @Nonnull
    public static <T> T fromJson(@Nonnull JavaType type, @Nonnull byte[] data) {
        return getOrLoadJackson3().fromJson(type, data);
    }

    @Nonnull
    public static <T> T fromJson(@Nonnull JavaType type, @Nonnull ByteBuf data) {
        return getOrLoadJackson3().fromJson(type, data);
    }

    @Nonnull
    public static <T> T fromJson(@Nonnull JavaType type, @Nonnull InputStream data) {
        return getOrLoadJackson3().fromJson(type, data);
    }

    @Nonnull
    public static <T> T fromJson(@Nonnull JavaType type, @Nonnull Reader data) {
        return getOrLoadJackson3().fromJson(type, data);
    }

    @Nonnull
    public static <T> T fromJson(@Nonnull JavaType type, @Nonnull String data) {
        return getOrLoadJackson3().fromJson(type, data);
    }
}
