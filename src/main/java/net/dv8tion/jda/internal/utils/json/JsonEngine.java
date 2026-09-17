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
import net.dv8tion.jda.api.exceptions.ParsingException;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Internal interface for pluggable JSON serialization and deserialization engines.
 */
public interface JsonEngine {
    /**
     * The human-readable name of this JSON engine (e.g. "Jackson 3.x", "Jackson 2.x", "nanojson").
     *
     * @return The engine name
     */
    @Nonnull
    String getName();

    /**
     * Serializes an object to a UTF-8 JSON byte array.
     *
     * @param  data
     *         The object to serialize
     *
     * @throws ParsingException
     *         If serialization fails
     *
     * @return The serialized bytes
     */
    @Nonnull
    byte[] toJson(@Nonnull Object data);

    /**
     * Serializes an object to a JSON string.
     *
     * @param  data
     *         The object to serialize
     * @param  pretty
     *         Whether the output should be indented and formatted with sorted keys
     *
     * @throws ParsingException
     *         If serialization fails
     *
     * @return The JSON string
     */
    @Nonnull
    String toJsonString(@Nonnull Object data, boolean pretty);

    /**
     * Writes an object as JSON to the specified output stream.
     *
     * @param  out
     *         The output stream to write to
     * @param  data
     *         The object to serialize
     *
     * @throws ParsingException
     *         If writing fails
     */
    void writeJson(@Nonnull OutputStream out, @Nonnull Object data);

    /**
     * Writes an object as JSON to the specified ByteBuf.
     *
     * @param  target
     *         The ByteBuf to write into
     * @param  data
     *         The object to serialize
     *
     * @throws ParsingException
     *         If writing fails
     */
    void writeJson(@Nonnull ByteBuf target, @Nonnull Object data);

    /**
     * Parses JSON byte array into a Map.
     *
     * @param  data
     *         The JSON bytes
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting Map
     */
    @Nonnull
    Map<String, Object> fromJsonMap(@Nonnull byte[] data);

    /**
     * Parses JSON ByteBuf into a Map.
     *
     * @param  data
     *         The JSON ByteBuf
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting Map
     */
    @Nonnull
    Map<String, Object> fromJsonMap(@Nonnull ByteBuf data);

    /**
     * Parses JSON InputStream into a Map.
     *
     * @param  stream
     *         The JSON input stream
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting Map
     */
    @Nonnull
    Map<String, Object> fromJsonMap(@Nonnull InputStream stream);

    /**
     * Parses JSON Reader into a Map.
     *
     * @param  reader
     *         The JSON reader
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting Map
     */
    @Nonnull
    Map<String, Object> fromJsonMap(@Nonnull Reader reader);

    /**
     * Parses JSON string into a Map.
     *
     * @param  json
     *         The JSON string
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting Map
     */
    @Nonnull
    Map<String, Object> fromJsonMap(@Nonnull String json);

    /**
     * Parses JSON byte array into a List.
     *
     * @param  data
     *         The JSON bytes
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting List
     */
    @Nonnull
    List<Object> fromJsonList(@Nonnull byte[] data);

    /**
     * Parses JSON ByteBuf into a List.
     *
     * @param  data
     *         The JSON ByteBuf
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting List
     */
    @Nonnull
    List<Object> fromJsonList(@Nonnull ByteBuf data);

    /**
     * Parses JSON InputStream into a List.
     *
     * @param  stream
     *         The JSON input stream
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting List
     */
    @Nonnull
    List<Object> fromJsonList(@Nonnull InputStream stream);

    /**
     * Parses JSON Reader into a List.
     *
     * @param  reader
     *         The JSON reader
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting List
     */
    @Nonnull
    List<Object> fromJsonList(@Nonnull Reader reader);

    /**
     * Parses JSON string into a List.
     *
     * @param  json
     *         The JSON string
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The resulting List
     */
    @Nonnull
    List<Object> fromJsonList(@Nonnull String json);

    /**
     * Parses JSON byte array into the target class.
     *
     * @param  clazz
     *         The target class
     * @param  data
     *         The JSON byte array
     *
     * @throws ParsingException
     *         If parsing fails
     *
     * @return The deserialized instance
     */
    @Nonnull
    <T> T fromJson(@Nonnull Class<T> clazz, @Nonnull byte[] data);

    /**
     * Truncates nested objects/arrays to a single level for logging / diagnostic string representation.
     *
     * @param  object
     *         The object to format
     *
     * @throws ParsingException
     *         If serialization fails
     *
     * @return The shallow JSON string with sorted keys
     */
    @Nonnull
    String toShallowJsonString(@Nonnull Object object);
}
