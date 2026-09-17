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

package net.dv8tion.jda.api.utils.data;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.dv8tion.jda.api.exceptions.DataObjectParsingException;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.MiscUtil;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.api.utils.data.etf.ExTermDecoder;
import net.dv8tion.jda.api.utils.data.etf.ExTermEncoder;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.Helpers;
import net.dv8tion.jda.internal.utils.SerializationUtil;
import org.jetbrains.annotations.Contract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents a map of values used in communication with the Discord API.
 *
 * <p>
 * Throws {@link NullPointerException},
 * if a parameter annotated with {@link Nonnull} is provided
 * with {@code null}.
 *
 * <p>
 * This class is not Thread-Safe.
 */
public class DataObject implements SerializableData {
    private static final Logger log = LoggerFactory.getLogger(DataObject.class);

    protected final Map<String, Object> data;

    protected DataObject(@Nonnull Map<String, Object> data) {
        this.data = data;
    }

    /**
     * Creates a new empty DataObject, ready to be populated with values.
     *
     * @return An empty DataObject instance
     *
     * @see #put(String, Object)
     */
    @Nonnull
    public static DataObject empty() {
        return new DataObject(new Object2ObjectOpenHashMap<>());
    }

    /**
     * Creates a new empty DataObject with initial capacity, ready to be populated with values.
     *
     * @param  initialCapacity
     *         The initial capacity of the map
     *
     * @return An empty DataObject instance
     *
     * @see #put(String, Object)
     */
    @Nonnull
    public static DataObject empty(int initialCapacity) {
        return new DataObject(new Object2ObjectOpenHashMap<>(initialCapacity));
    }

    /**
     * Parses a JSON payload directly from a Netty {@link ByteBuf} into a DataObject
     * instance.
     *
     * @param data
     *             The Netty {@link ByteBuf} containing correctly formatted JSON
     *             payload to parse
     *
     * @throws ParsingException
     *                                                         If the provided json
     *                                                         is incorrectly
     *                                                         formatted
     *
     * @return A DataObject instance for the provided payload
     */
    @Nonnull
    public static DataObject fromJson(@Nonnull ByteBuf data) {
        return fromJson(data, true);
    }

    @Nonnull
    public static DataObject fromJson(@Nonnull ByteBuf data, boolean deduplicateStrings) {
        return new DataObject(SerializationUtil.fromJsonMap(data, deduplicateStrings));
    }

    /**
     * Parses a JSON payload into a DataObject instance.
     *
     * @param json
     *             The correctly formatted JSON payload to parse
     *
     * @throws ParsingException
     *                                                         If the provided json
     *                                                         is incorrectly
     *                                                         formatted
     *
     * @return A DataObject instance for the provided payload
     */
    @Nonnull
    public static DataObject fromJson(@Nonnull String json) {
        return fromJson(json, true);
    }

    @Nonnull
    public static DataObject fromJson(@Nonnull String json, boolean deduplicateStrings) {
        return new DataObject(SerializationUtil.fromJsonMap(json, deduplicateStrings));
    }

    /**
     * Parses a JSON payload into a DataObject instance.
     *
     * @param stream
     *               The correctly formatted JSON payload to parse
     *
     * @throws ParsingException
     *                                                         If the provided json
     *                                                         is incorrectly
     *                                                         formatted or an I/O
     *                                                         error occurred
     *
     * @return A DataObject instance for the provided payload
     */
    @Nonnull
    public static DataObject fromJson(@Nonnull InputStream stream) {
        return fromJson(stream, true);
    }

    @Nonnull
    public static DataObject fromJson(@Nonnull InputStream stream, boolean deduplicateStrings) {
        return new DataObject(SerializationUtil.fromJsonMap(stream, deduplicateStrings));
    }

    /**
     * Parses a JSON payload into a DataObject instance.
     *
     * @param stream
     *               The correctly formatted JSON payload to parse
     *
     * @throws ParsingException
     *                                                         If the provided json
     *                                                         is incorrectly
     *                                                         formatted or an I/O
     *                                                         error occurred
     *
     * @return A DataObject instance for the provided payload
     */
    @Nonnull
    public static DataObject fromJson(@Nonnull Reader stream) {
        return fromJson(stream, true);
    }

    @Nonnull
    public static DataObject fromJson(@Nonnull Reader stream, boolean deduplicateStrings) {
        return new DataObject(SerializationUtil.fromJsonMap(stream, deduplicateStrings));
    }

    /**
     * Parses using {@link ExTermDecoder}.
     * The provided data must start with the correct version header (131).
     *
     * @param data
     *             The {@link ByteBuf} containing encoded ETF data
     *
     * @throws IllegalArgumentException
     *                                                         If the provided data
     *                                                         is null
     * @throws ParsingException
     *                                                         If the provided ETF
     *                                                         payload is
     *                                                         incorrectly formatted
     *                                                         or an I/O error
     *                                                         occurred
     *
     * @return A DataObject instance for the provided payload
     */
    @Nonnull
    public static DataObject fromETF(@Nonnull ByteBuf data) {
        return fromETF(data, true);
    }

    @Nonnull
    public static DataObject fromETF(@Nonnull ByteBuf data, boolean deduplicateStrings) {
        Checks.notNull(data, "Data");
        try {
            return new DataObject(ExTermDecoder.unpackMap(data, deduplicateStrings));
        } catch (Exception ex) {
            log.error("Failed to parse ETF data", ex);
            throw new ParsingException(ex);
        }
    }

    /**
     * Parses using {@link ExTermDecoder}.
     * The provided data must start with the correct version header (131).
     *
     * @param buffer
     *               The {@link ByteBuffer} containing encoded ETF data
     *
     * @throws IllegalArgumentException
     *                                                         If the provided
     *                                                         buffer is null
     * @throws ParsingException
     *                                                         If the provided ETF
     *                                                         payload is
     *                                                         incorrectly formatted
     *                                                         or an I/O error
     *                                                         occurred
     *
     * @return A DataObject instance for the provided payload
     */
    @Nonnull
    public static DataObject fromETF(@Nonnull ByteBuffer buffer) {
        return fromETF(buffer, true);
    }

    @Nonnull
    public static DataObject fromETF(@Nonnull ByteBuffer buffer, boolean deduplicateStrings) {
        Checks.notNull(buffer, "Buffer");
        try {
            Map<String, Object> map = ExTermDecoder.unpackMap(buffer, deduplicateStrings);
            return new DataObject(map);
        } catch (Exception ex) {
            log.error("Failed to parse ETF data", ex);
            throw new ParsingException(ex);
        }
    }

    /**
     * Parses using {@link ExTermDecoder}.
     * The provided data must start with the correct version header (131).
     *
     * @param data
     *             The data to decode
     *
     * @throws IllegalArgumentException
     *                                                         If the provided data
     *                                                         is null
     * @throws ParsingException
     *                                                         If the provided ETF
     *                                                         payload is
     *                                                         incorrectly formatted
     *                                                         or an I/O error
     *                                                         occurred
     *
     * @return A DataObject instance for the provided payload
     */
    @Nonnull
    public static DataObject fromETF(@Nonnull byte[] data) {
        return fromETF(data, true);
    }

    @Nonnull
    public static DataObject fromETF(@Nonnull byte[] data, boolean deduplicateStrings) {
        Checks.notNull(data, "Data");
        return fromETF(ByteBuffer.wrap(data), deduplicateStrings);
    }

    /**
     * Whether the specified key is present.
     *
     * @param key
     *            The key to check
     *
     * @return True, if the specified key is present
     */
    public boolean hasKey(@Nonnull String key) {
        return data.containsKey(key);
    }

    /**
     * Whether the specified key is missing or null
     *
     * @param key
     *            The key to check
     *
     * @return True, if the specified key is null or missing
     */
    public boolean isNull(@Nonnull String key) {
        return data.get(key) == null;
    }

    /**
     * Whether the specified key is of the specified type.
     *
     * @param key
     *             The key to check
     * @param type
     *             The type to check
     *
     * @return True, if the type check is successful
     *
     * @see DataType#isType(Object)
     *      DataType.isType(Object)
     */
    public boolean isType(@Nonnull String key, @Nonnull DataType type) {
        return type.isType(data.get(key));
    }

    /**
     * Resolves a DataObject to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the type is
     *                                                         incorrect or no value
     *                                                         is present for the
     *                                                         specified key
     *
     * @return The resolved instance of DataObject for the key
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public DataObject getObject(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case null -> throw valueError(key, "DataObject");
            case Map<?, ?> map -> new DataObject((Map<String, Object>) map);
            case SerializableData sd -> sd.toData();
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Map: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves a DataObject to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the type is
     *                                                         incorrect
     *
     * @return The resolved instance of DataObject for the key, wrapped in
     *         {@link Optional}
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public Optional<DataObject> optObject(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case Map<?, ?> map -> Optional.of(new DataObject((Map<String, Object>) map));
            case SerializableData sd -> Optional.of(sd.toData());
            case null, default -> Optional.empty();
        };
    }

    /**
     * Resolves a DataArray to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the type is
     *                                                         incorrect or no value
     *                                                         is present for the
     *                                                         specified key
     *
     * @return The resolved instance of DataArray for the key
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public DataArray getArray(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case null -> throw valueError(key, "DataArray");
            case List<?> list -> new DataArray((List<Object>) list);
            case SerializableArray sa -> sa.toDataArray();
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type List: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves a DataArray to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the type is
     *                                                         incorrect
     *
     * @return The resolved instance of DataArray for the key, wrapped in
     *         {@link Optional}
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public Optional<DataArray> optArray(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case List<?> list -> Optional.of(new DataArray((List<Object>) list));
            case SerializableArray sa -> Optional.of(sa.toDataArray());
            case null, default -> Optional.empty();
        };
    }

    /**
     * Resolves any type to the provided key.
     *
     * @param key
     *            The key to check for a value
     *
     * @return {@link Optional} with a possible value
     */
    @Nonnull
    public Optional<Object> opt(@Nonnull String key) {
        return Optional.ofNullable(data.get(key));
    }

    /**
     * Resolves any type to the provided key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is
     *                                                         missing or null
     *
     * @return The value of any type
     *
     * @see #opt(String)
     */
    @Nonnull
    public Object get(@Nonnull String key) {
        Object value = data.get(key);
        if (value == null) {
            throw valueError(key, "any");
        }
        return value;
    }

    /**
     * Resolves a {@link String} to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is
     *                                                         missing or null
     *
     * @return The String value
     */
    @Nonnull
    public String getString(@Nonnull String key) {
        Object value = data.get(key);
        if (value == null) {
            throw valueError(key, "String");
        }
        return value.toString();
    }

    /**
     * Resolves a {@link String} to a key.
     *
     * @param key
     *                     The key to check for a value
     * @param defaultValue
     *                     Alternative value to use when no value or null value is
     *                     associated with the key
     *
     * @return The String value, or null if provided with null defaultValue
     */
    @Contract("_, !null -> !null")
    public String getString(@Nonnull String key, @Nullable String defaultValue) {
        Object value = data.get(key);
        return value == null ? defaultValue : value.toString();
    }

    /**
     * Resolves a {@link Boolean} to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return True, if the value is present and set to true. False if the value is
     *         missing or set to false.
     */
    public boolean getBoolean(@Nonnull String key) {
        return getBoolean(key, false);
    }

    /**
     * Resolves a {@link Boolean} to a key.
     *
     * @param key
     *                     The key to check for a value
     * @param defaultValue
     *                     Alternative value to use when no value or null value is
     *                     associated with the key
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return True, if the value is present and set to true. False if the value is
     *         set to false. defaultValue if it is missing.
     */
    public boolean getBoolean(@Nonnull String key, boolean defaultValue) {
        Object value = data.get(key);
        return switch (value) {
            case Boolean b -> b;
            case String s -> Boolean.parseBoolean(s);
            case null, default -> defaultValue;
        };
    }

    /**
     * Resolves a long to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is
     *                                                         missing, null, or of
     *                                                         the wrong type
     *
     * @return The long value for the key
     */
    public long getLong(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case null -> throw valueError(key, "long");
            case Number n -> n.longValue();
            case String s -> MiscUtil.parseLong(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Long: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves a long to a key.
     *
     * @param key
     *                     The key to check for a value
     * @param defaultValue
     *                     Alternative value to use when no value or null value is
     *                     associated with the key
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The long value for the key
     */
    public long getLong(@Nonnull String key, long defaultValue) {
        Object value = data.get(key);
        return switch (value) {
            case null -> defaultValue;
            case Number n -> n.longValue();
            case String s -> Long.parseLong(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Long: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves an unsigned long to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is
     *                                                         missing, null, or of
     *                                                         the wrong type
     *
     * @return The unsigned long value for the key
     */
    public long getUnsignedLong(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case null -> throw valueError(key, "unsigned long");
            case Number n -> n.longValue();
            case String s -> Long.parseUnsignedLong(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Long: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves an unsigned long to a key.
     *
     * @param key
     *                     The key to check for a value
     * @param defaultValue
     *                     Alternative value to use when no value or null value is
     *                     associated with the key
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The unsigned long value for the key
     */
    public long getUnsignedLong(@Nonnull String key, long defaultValue) {
        Object value = data.get(key);
        return switch (value) {
            case null -> defaultValue;
            case Number n -> n.longValue();
            case String s -> Long.parseUnsignedLong(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Long: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves an int to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is
     *                                                         missing, null, or of
     *                                                         the wrong type
     *
     * @return The int value for the key
     */
    public int getInt(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case null -> throw valueError(key, "int");
            case Number n -> n.intValue();
            case String s -> Integer.parseInt(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Integer: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves an int to a key.
     *
     * @param key
     *                     The key to check for a value
     * @param defaultValue
     *                     Alternative value to use when no value or null value is
     *                     associated with the key
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The int value for the key
     */
    public int getInt(@Nonnull String key, int defaultValue) {
        Object value = data.get(key);
        return switch (value) {
            case null -> defaultValue;
            case Number n -> n.intValue();
            case String s -> Integer.parseInt(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Integer: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves an unsigned int to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is
     *                                                         missing, null, or of
     *                                                         the wrong type
     *
     * @return The unsigned int value for the key
     */
    public int getUnsignedInt(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case null -> throw valueError(key, "unsigned int");
            case Number n -> n.intValue();
            case String s -> Integer.parseUnsignedInt(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Integer: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves an unsigned int to a key.
     *
     * @param key
     *                     The key to check for a value
     * @param defaultValue
     *                     Alternative value to use when no value or null value is
     *                     associated with the key
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The unsigned int value for the key
     */
    public int getUnsignedInt(@Nonnull String key, int defaultValue) {
        Object value = data.get(key);
        return switch (value) {
            case null -> defaultValue;
            case Number n -> n.intValue();
            case String s -> Integer.parseUnsignedInt(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Integer: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves a double to a key.
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is
     *                                                         missing, null, or of
     *                                                         the wrong type
     *
     * @return The double value for the key
     */
    public double getDouble(@Nonnull String key) {
        Object value = data.get(key);
        return switch (value) {
            case null -> throw valueError(key, "double");
            case Number n -> n.doubleValue();
            case String s -> Double.parseDouble(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Double: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves a double to a key.
     *
     * @param key
     *                     The key to check for a value
     * @param defaultValue
     *                     Alternative value to use when no value or null value is
     *                     associated with the key
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The double value for the key
     */
    public double getDouble(@Nonnull String key, double defaultValue) {
        Object value = data.get(key);
        return switch (value) {
            case null -> defaultValue;
            case Number n -> n.doubleValue();
            case String s -> Double.parseDouble(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type Double: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves an {@link OffsetDateTime} to a key.
     * <br>
     * <b>Note:</b> This method should be used on ISO8601 timestamps
     *
     * @param key
     *            The key to check for a value
     *
     * @throws ParsingException
     *                                                         If the value is
     *                                                         missing, null, or not
     *                                                         a valid ISO8601
     *                                                         timestamp
     *
     * @return Possibly-null {@link OffsetDateTime} object representing the
     *         timestamp
     */
    @Nonnull
    public OffsetDateTime getOffsetDateTime(@Nonnull String key) {
        OffsetDateTime value = getOffsetDateTime(key, null);
        if (value == null) {
            throw valueError(key, "OffsetDateTime");
        }
        return value;
    }

    /**
     * Resolves an {@link OffsetDateTime} to a key.
     * <br>
     * <b>Note:</b> This method should only be used on ISO8601 timestamps
     *
     * @param key
     *                     The key to check for a value
     * @param defaultValue
     *                     Alternative value to use when no value or null value is
     *                     associated with the key
     *
     * @throws ParsingException
     *                                                         If the value is not a
     *                                                         valid ISO8601
     *                                                         timestamp
     *
     * @return Possibly-null {@link OffsetDateTime} object representing the
     *         timestamp
     */
    @Contract("_, !null -> !null")
    public OffsetDateTime getOffsetDateTime(@Nonnull String key, @Nullable OffsetDateTime defaultValue) {
        Object value = data.get(key);
        return switch (value) {
            case null -> defaultValue;
            case OffsetDateTime offsetDateTime -> offsetDateTime;
            case CharSequence charSequence -> {
                try {
                    yield OffsetDateTime.parse(charSequence);
                } catch (DateTimeParseException e) {
                    throw new ParsingException(
                            Helpers.format(
                                    "Cannot parse value for %s into an OffsetDateTime object. Try double checking that %s is a valid"
                                            + " ISO8601 timestmap",
                                    key, e.getParsedString()),
                            e);
                }
            }
            default -> {
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for %s into type OffsetDateTime: %s instance of %s",
                        key, value, value.getClass().getSimpleName()));
            }
        };
    }

    /**
     * Removes the value associated with the specified key.
     * If no value is associated with the key, this does nothing.
     *
     * @param key
     *            The key to unlink
     *
     * @return A DataObject with the removed key
     */
    @Nonnull
    public DataObject remove(@Nonnull String key) {
        data.remove(key);
        return this;
    }

    /**
     * Upserts a null value for the provided key.
     *
     * @param key
     *            The key to upsert
     *
     * @return A DataObject with the updated value
     */
    @Nonnull
    public DataObject putNull(@Nonnull String key) {
        data.put(key, null);
        return this;
    }

    /**
     * Upserts a new value for the provided key.
     *
     * @param key
     *              The key to upsert
     * @param value
     *              The new value
     *
     * @return A DataObject with the updated value
     */
    @Nonnull
    public DataObject put(@Nonnull String key, @Nullable Object value) {
        switch (value) {
            case SerializableData serializable -> data.put(key, serializable.toData().data);
            case SerializableArray serializable -> data.put(key, serializable.toDataArray().data);
            case null, default -> data.put(key, value);
        }
        return this;
    }

    /**
     * Renames an existing field to the new name.
     * <br>
     * This is a shorthand to {@link #remove(String) remove} under the old key and
     * then {@link #put(String, Object) put} under the new key.
     *
     * <p>
     * If there is nothing mapped to the old key, this does nothing.
     *
     * @param key
     *               The old key
     * @param newKey
     *               The new key
     *
     * @throws IllegalArgumentException
     *                                  If null is provided
     *
     * @return A DataObject with the updated value
     */
    @Nonnull
    public DataObject rename(@Nonnull String key, @Nonnull String newKey) {
        Checks.notNull(key, "Key");
        Checks.notNull(newKey, "Key");
        if (!this.data.containsKey(key)) {
            return this;
        }
        this.data.put(newKey, this.data.remove(key));
        return this;
    }

    /**
     * {@link Collection} of all values in this DataObject.
     *
     * @return {@link Collection} for all values
     */
    @Nonnull
    public Collection<Object> values() {
        return data.values();
    }

    /**
     * {@link Set} of all keys in this DataObject.
     *
     * @return {@link Set} of keys
     */
    @Nonnull
    public Set<String> keys() {
        return data.keySet();
    }

    /**
     * Serialize this object as JSON.
     *
     * @return byte array containing the JSON representation of this object
     */
    @Nonnull
    public byte[] toJson() {
        return SerializationUtil.toJson(data);
    }

    /**
     * Serializes this object as ETF MAP term.
     *
     * @return byte array containing the encoded ETF term
     */
    @Nonnull
    public byte[] toETF() {
        ByteBuf buffer = NettyConfig.getGlobalAllocator().heapBuffer();
        try {
            buffer.writeByte(131);
            ExTermEncoder.pack(buffer, data);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Override
    public String toString() {
        return SerializationUtil.toJsonString(data, false);
    }

    @Nonnull
    public String toPrettyString() {
        return SerializationUtil.toJsonString(data, true);
    }

    @Nonnull
    public String toShallowString() {
        try {
            return SerializationUtil.toShallowJsonString(this.data);
        } catch (Exception e) {
            throw new ParsingException(e);
        }
    }

    /**
     * Resolves the array at the specified key to a primitive {@link LongList}.
     *
     * @param key
     *            The key to resolve
     *
     * @return A {@link LongList} of snowflake IDs, or empty list if the key is
     *         missing or null
     */
    @Nonnull
    public LongList getLongList(@Nonnull String key) {
        return optArray(key).map(DataArray::toLongList).orElse(LongLists.EMPTY_LIST);
    }

    /**
     * Resolves the array at the specified key to a primitive {@link LongSet}.
     *
     * @param key
     *            The key to resolve
     *
     * @return A {@link LongSet} of snowflake IDs, or empty set if the key is
     *         missing or null
     */
    @Nonnull
    public LongSet getLongSet(@Nonnull String key) {
        return optArray(key).map(DataArray::toLongSet).orElse(LongSets.EMPTY_SET);
    }

    /**
     * Converts this DataObject to a {@link Map}
     *
     * @return The resulting map
     */
    @Nonnull
    public Map<String, Object> toMap() {
        return data;
    }

    @Nonnull
    @Override
    public DataObject toData() {
        return this;
    }

    private ParsingException valueError(String key, String expectedType) {
        if (!hasKey(key)) {
            return new DataObjectParsingException(
                    this, "Missing value for key '" + key + "' with expected type " + expectedType);
        }
        return new DataObjectParsingException(
                this, "Unable to resolve value with key '" + key + "' to type " + expectedType + ": " + data.get(key));
    }

    @Nullable
    private <T> T get(@Nonnull Class<T> type, @Nonnull String key) {
        return get(type, key, null, null);
    }

    @Nullable
    private <T> T get(
            @Nonnull Class<T> type,
            @Nonnull String key,
            @Nullable Function<String, T> stringParse,
            @Nullable Function<Number, T> numberParse) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (type == String.class) {
            return type.cast(value.toString());
        }
        // attempt type coercion
        if (value instanceof Number && numberParse != null) {
            return numberParse.apply((Number) value);
        } else if (value instanceof String && stringParse != null) {
            return stringParse.apply((String) value);
        }

        throw new ParsingException(Helpers.format(
                "Cannot parse value for %s into type %s: %s instance of %s",
                key, type.getSimpleName(), value, value.getClass().getSimpleName()));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof DataObject)) {
            return false;
        }
        return ((DataObject) obj).toMap().equals(this.toMap());
    }

    @Override
    public int hashCode() {
        return toMap().hashCode();
    }
}
