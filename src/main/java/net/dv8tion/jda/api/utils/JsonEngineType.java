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

package net.dv8tion.jda.api.utils;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.internal.utils.json.JsonEngine;
import net.dv8tion.jda.internal.utils.json.NanojsonEngine;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Types of JSON serialization and deserialization engines supported by JDA.
 *
 * @see JDABuilder#setJsonEngine(JsonEngineType)
 * @see DefaultShardManagerBuilder#setJsonEngine(JsonEngineType)
 */
public enum JsonEngineType {
    /**
     * The default, lightweight JSON engine using {@code com.grack:nanojson}.
     * <br>This is bundled with JDA and always available.
     */
    NANOJSON(
            "nanojson",
            "com.grack.nanojson.JsonParser",
            "net.dv8tion.jda.internal.utils.json.NanojsonEngine",
            "com.grack:nanojson"),

    /**
     * Jackson 3.x engine adapter using {@code tools.jackson}.
     */
    JACKSON3(
            "jackson3",
            "tools.jackson.databind.ObjectMapper",
            "net.dv8tion.jda.internal.utils.json.Jackson3Engine",
            "tools.jackson.core:jackson-databind"),

    /**
     * Jackson 2.x engine adapter using {@code com.fasterxml.jackson}.
     */
    JACKSON2(
            "jackson2",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "net.dv8tion.jda.internal.utils.json.Jackson2Engine",
            "com.fasterxml.jackson.core:jackson-databind");

    private final String key;
    private final String probeClassName;
    private final String engineClassName;
    private final String dependencyExample;

    JsonEngineType(
            @Nonnull String key,
            @Nonnull String probeClassName,
            @Nonnull String engineClassName,
            @Nonnull String dependencyExample) {
        this.key = key;
        this.probeClassName = probeClassName;
        this.engineClassName = engineClassName;
        this.dependencyExample = dependencyExample;
    }

    /**
     * Resolves a {@link JsonEngineType} from a configuration key or name.
     *
     * @param name The engine name or key (case-insensitive)
     * @return The corresponding {@link JsonEngineType}, or null if unknown
     */
    @Nullable
    public static JsonEngineType fromKey(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (JsonEngineType type : values()) {
            if (type.key.equalsIgnoreCase(name) || type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        if (name.equalsIgnoreCase("jackson3.x")) {
            return JACKSON3;
        }
        if (name.equalsIgnoreCase("jackson2.x")) {
            return JACKSON2;
        }
        return null;
    }

    /**
     * The configuration key for this engine (e.g. used in system property {@code net.dv8tion.jda.json.engine}).
     *
     * @return The configuration key
     */
    @Nonnull
    public String getKey() {
        return key;
    }

    /**
     * An example dependency coordinate required for this JSON engine (e.g. {@code tools.jackson.core:jackson-databind}).
     *
     * @return The dependency example coordinate
     */
    @Nonnull
    public String getDependencyExample() {
        return dependencyExample;
    }

    /**
     * An example dependency coordinate required for this JSON engine (alias for {@link #getDependencyExample()}).
     *
     * @return The dependency example coordinate
     */
    @Nonnull
    public String getDependency() {
        return dependencyExample;
    }

    /**
     * An example dependency coordinate required for this JSON engine (alias for {@link #getDependencyExample()}).
     *
     * @return The dependency example coordinate
     */
    @Nonnull
    public String getSuggestedDependency() {
        return dependencyExample;
    }

    /**
     * Whether this JSON engine is supported on the current classpath.
     *
     * @return True if supported, false otherwise
     */
    public boolean isSupported() {
        try {
            Class.forName(probeClassName, false, JsonEngineType.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Creates a new instance of this JSON engine.
     *
     * @throws IllegalStateException
     *         If this engine is not supported on the classpath
     *
     * @return A new {@link JsonEngine} instance
     */
    @Nonnull
    @SuppressWarnings("UnsafeReflectiveConstructionCast")
    public JsonEngine createEngine() {
        if (!isSupported()) {
            throw new IllegalStateException("JSON engine '" + this
                    + "' is not supported on this classpath (missing dependency: " + dependencyExample + ")");
        }
        if (this == NANOJSON) {
            return new NanojsonEngine();
        }
        try {
            Class<? extends JsonEngine> clazz = Class.forName(engineClassName).asSubclass(JsonEngine.class);
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate JSON engine for " + this, e);
        }
    }
}
