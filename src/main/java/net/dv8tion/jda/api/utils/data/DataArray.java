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
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.dv8tion.jda.api.exceptions.DataArrayParsingException;
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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents a list of values used in communication with the Discord API.
 *
 * <p>
 * Throws {@link IndexOutOfBoundsException}
 * if provided with index out of bounds.
 *
 * <p>
 * This class is not Thread-Safe
 */
public class DataArray implements Iterable<Object>, SerializableArray {
    private static final Logger log = LoggerFactory.getLogger(DataObject.class);

    protected final List<Object> data;

    protected DataArray(@Nonnull List<Object> data) {
        this.data = data;
    }

    /**
     * Creates a new empty DataArray, ready to be populated with values.
     *
     * @return An empty DataArray instance
     *
     * @see #add(Object)
     */
    @Nonnull
    public static DataArray empty() {
        return new DataArray(new ObjectArrayList<>());
    }

    /**
     * Creates a new empty DataArray with initial capacity, ready to be populated
     * with values.
     *
     * @param initialCapacity
     *                        The initial capacity of the array
     *
     * @return An empty DataArray instance
     *
     * @see #add(Object)
     */
    @Nonnull
    public static DataArray empty(int initialCapacity) {
        return new DataArray(new ObjectArrayList<>(initialCapacity));
    }

    /**
     * Creates a new DataArray and populates it with the contents
     * of the provided collection.
     *
     * @param col
     *            The {@link Collection}
     *
     * @return A new DataArray populated with the contents of the collection
     */
    @Nonnull
    public static DataArray fromCollection(@Nonnull Collection<?> col) {
        Checks.notNull(col, "Collection");
        return empty(col.size()).addAll(col);
    }

    /**
     * Parses a JSON Array directly from a Netty {@link ByteBuf} into a DataArray
     * instance.
     *
     * @param data
     *             The Netty {@link ByteBuf} containing correctly formatted JSON
     *             Array
     *
     * @throws ParsingException
     *                                                         If the provided JSON
     *                                                         is incorrectly
     *                                                         formatted
     *
     * @return A new DataArray instance for the provided array
     */
    @Nonnull
    public static DataArray fromJson(@Nonnull ByteBuf data) {
        return fromJson(data, true);
    }

    @Nonnull
    public static DataArray fromJson(@Nonnull ByteBuf data, boolean deduplicateStrings) {
        return new DataArray(SerializationUtil.fromJsonList(data, deduplicateStrings));
    }

    /**
     * Parses a JSON Array into a DataArray instance.
     *
     * @param json
     *             The correctly formatted JSON Array
     *
     * @throws ParsingException
     *                                                         If the provided JSON
     *                                                         is incorrectly
     *                                                         formatted
     *
     * @return A new DataArray instance for the provided array
     */
    @Nonnull
    public static DataArray fromJson(@Nonnull String json) {
        return fromJson(json, true);
    }

    @Nonnull
    public static DataArray fromJson(@Nonnull String json, boolean deduplicateStrings) {
        return new DataArray(SerializationUtil.fromJsonList(json, deduplicateStrings));
    }

    /**
     * Parses a JSON Array into a DataArray instance.
     *
     * @param json
     *             The correctly formatted JSON Array
     *
     * @throws ParsingException
     *                                                         If the provided JSON
     *                                                         is incorrectly
     *                                                         formatted or an I/O
     *                                                         error occurred
     *
     * @return A new DataArray instance for the provided array
     */
    @Nonnull
    public static DataArray fromJson(@Nonnull InputStream json) {
        return fromJson(json, true);
    }

    @Nonnull
    public static DataArray fromJson(@Nonnull InputStream json, boolean deduplicateStrings) {
        return new DataArray(SerializationUtil.fromJsonList(json, deduplicateStrings));
    }

    /**
     * Parses a JSON Array into a DataArray instance.
     *
     * @param json
     *             The correctly formatted JSON Array
     *
     * @throws ParsingException
     *                                                         If the provided JSON
     *                                                         is incorrectly
     *                                                         formatted or an I/O
     *                                                         error occurred
     *
     * @return A new DataArray instance for the provided array
     */
    @Nonnull
    public static DataArray fromJson(@Nonnull Reader json) {
        return fromJson(json, true);
    }

    @Nonnull
    public static DataArray fromJson(@Nonnull Reader json, boolean deduplicateStrings) {
        return new DataArray(SerializationUtil.fromJsonList(json, deduplicateStrings));
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
     * @return A DataArray instance for the provided payload
     */
    @Nonnull
    public static DataArray fromETF(@Nonnull ByteBuf data) {
        return fromETF(data, true);
    }

    @Nonnull
    public static DataArray fromETF(@Nonnull ByteBuf data, boolean deduplicateStrings) {
        Checks.notNull(data, "Data");
        try {
            return new DataArray(ExTermDecoder.unpackList(data, deduplicateStrings));
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
     * @return A DataArray instance for the provided payload
     */
    @Nonnull
    public static DataArray fromETF(@Nonnull ByteBuffer buffer) {
        return fromETF(buffer, true);
    }

    @Nonnull
    public static DataArray fromETF(@Nonnull ByteBuffer buffer, boolean deduplicateStrings) {
        Checks.notNull(buffer, "Buffer");
        try {
            List<Object> list = ExTermDecoder.unpackList(buffer, deduplicateStrings);
            return new DataArray(list);
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
     * @return A DataArray instance for the provided payload
     */
    @Nonnull
    public static DataArray fromETF(@Nonnull byte[] data) {
        return fromETF(data, true);
    }

    @Nonnull
    public static DataArray fromETF(@Nonnull byte[] data, boolean deduplicateStrings) {
        Checks.notNull(data, "Data");
        return fromETF(ByteBuffer.wrap(data), deduplicateStrings);
    }

    /**
     * Whether the value at the specified index is null.
     *
     * @param index
     *              The index to check
     *
     * @return True, if the value at the index is null
     */
    public boolean isNull(int index) {
        return index >= length() || data.get(index) == null;
    }

    /**
     * Whether the value at the specified index is of the specified type.
     *
     * @param index
     *              The index to check
     * @param type
     *              The type to check
     *
     * @return True, if the type check is successful
     *
     * @see DataType#isType(Object)
     *      DataType.isType(Object)
     */
    public boolean isType(int index, @Nonnull DataType type) {
        return type.isType(data.get(index));
    }

    /**
     * The length of the array.
     *
     * @return The length of the array
     */
    public int length() {
        return data.size();
    }

    /**
     * Whether this array is empty
     *
     * @return True, if this array is empty
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * Resolves the raw value at the specified index.
     *
     * @param index
     *              The index to resolve
     *
     * @throws IndexOutOfBoundsException
     *                                             If the index is out of bounds
     *
     * @return The raw value
     */
    @Nullable
    public Object get(int index) {
        return data.get(index);
    }

    /**
     * Resolves the value at the specified index to a DataObject
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type or
     *                                                         missing
     *
     * @return The resolved DataObject
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public DataObject getObject(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> throw valueError(index, "DataObject");
            case Map<?, ?> map -> new DataObject((Map<String, Object>) map);
            case SerializableData serializableData -> serializableData.toData();
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Map: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to a DataObject, wrapped in
     * {@link Optional}.
     *
     * @param index
     *              The index to resolve
     *
     * @return The resolved instance of DataObject for the index, wrapped in
     *         {@link Optional}
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public Optional<DataObject> optObject(int index) {
        if (index < 0 || index >= data.size()) {
            return Optional.empty();
        }
        Object value = data.get(index);
        return switch (value) {
            case Map<?, ?> map -> Optional.of(new DataObject((Map<String, Object>) map));
            case SerializableData sd -> Optional.of(sd.toData());
            case null, default -> Optional.empty();
        };
    }

    /**
     * Resolves the value at the specified index to a DataArray
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type or
     *                                                         null
     *
     * @return The resolved DataArray
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public DataArray getArray(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> throw valueError(index, "DataArray");
            case List<?> list -> new DataArray((List<Object>) list);
            case SerializableArray serializableArray -> serializableArray.toDataArray();
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type List: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to a DataArray, wrapped in
     * {@link Optional}.
     *
     * @param index
     *              The index to resolve
     *
     * @return The resolved instance of DataArray for the index, wrapped in
     *         {@link Optional}
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public Optional<DataArray> optArray(int index) {
        if (index < 0 || index >= data.size()) {
            return Optional.empty();
        }
        Object value = data.get(index);
        return switch (value) {
            case List<?> list -> Optional.of(new DataArray((List<Object>) list));
            case SerializableArray sa -> Optional.of(sa.toDataArray());
            case null, default -> Optional.empty();
        };
    }

    /**
     * Resolves the value at the specified index to a String.
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type or
     *                                                         null
     *
     * @return The resolved String
     */
    @Nonnull
    public String getString(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = data.get(index);
        if (value == null) {
            throw valueError(index, "String");
        }
        return value.toString();
    }

    /**
     * Resolves the value at the specified index to a String.
     *
     * @param index
     *                     The index to resolve
     * @param defaultValue
     *                     Alternative value to use when the value associated with
     *                     the index is null
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved String
     */
    @Contract("_, !null -> !null")
    public String getString(int index, @Nullable String defaultValue) {
        if (index < 0 || index >= data.size()) {
            return defaultValue;
        }
        Object value = data.get(index);
        return value == null ? defaultValue : value.toString();
    }

    /**
     * Resolves the value at the specified index to a boolean.
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return True, if the value is present and set to true. Otherwise false.
     */
    public boolean getBoolean(int index) {
        return getBoolean(index, false);
    }

    /**
     * Resolves the value at the specified index to a boolean.
     *
     * @param index
     *                     The index to resolve
     * @param defaultValue
     *                     Alternative value to use when the value associated with
     *                     the index is null
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return True, if the value is present and set to true. False, if it is set to
     *         false. Otherwise defaultValue.
     */
    public boolean getBoolean(int index, boolean defaultValue) {
        if (index < 0 || index >= data.size()) {
            return defaultValue;
        }
        Object value = data.get(index);
        return switch (value) {
            case Boolean b -> b;
            case String s -> Boolean.parseBoolean(s);
            case null, default -> defaultValue;
        };
    }

    /**
     * Resolves the value at the specified index to an int.
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved int value
     */
    public int getInt(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> throw valueError(index, "int");
            case Number number -> number.intValue();
            case String s -> Integer.parseInt(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Integer: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to an int.
     *
     * @param index
     *                     The index to resolve
     * @param defaultValue
     *                     Alternative value to use when the value associated with
     *                     the index is null
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved int value
     */
    public int getInt(int index, int defaultValue) {
        if (index < 0 || index >= data.size()) {
            return defaultValue;
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> defaultValue;
            case Number number -> number.intValue();
            case String s -> Integer.parseInt(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Integer: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to an unsigned int.
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved unsigned int value
     */
    public int getUnsignedInt(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> throw valueError(index, "unsigned int");
            case Number number -> number.intValue();
            case String s -> Integer.parseUnsignedInt(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Integer: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to an unsigned int.
     *
     * @param index
     *                     The index to resolve
     * @param defaultValue
     *                     Alternative value to use when the value associated with
     *                     the index is null
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved unsigned int value
     */
    public int getUnsignedInt(int index, int defaultValue) {
        if (index < 0 || index >= data.size()) {
            return defaultValue;
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> defaultValue;
            case Number number -> number.intValue();
            case String s -> Integer.parseUnsignedInt(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Integer: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to a long.
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved long value
     */
    public long getLong(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> throw valueError(index, "long");
            case Number number -> number.longValue();
            case String s -> MiscUtil.parseLong(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Long: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to a long.
     *
     * @param index
     *                     The index to resolve
     * @param defaultValue
     *                     Alternative value to use when the value associated with
     *                     the index is null
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved long value
     */
    public long getLong(int index, long defaultValue) {
        if (index < 0 || index >= data.size()) {
            return defaultValue;
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> defaultValue;
            case Number number -> number.longValue();
            case String s -> Long.parseLong(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Long: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to an unsigned long.
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved unsigned long value
     */
    public long getUnsignedLong(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> throw valueError(index, "unsigned long");
            case Number number -> number.longValue();
            case String s -> Long.parseUnsignedLong(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Long: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to an {@link OffsetDateTime}.
     * <br>
     * <b>Note:</b> This method should be used on ISO8601 timestamps
     *
     * @param index
     *              The index to resolve
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
    public OffsetDateTime getOffsetDateTime(int index) {
        OffsetDateTime value = getOffsetDateTime(index, null);
        if (value == null) {
            throw valueError(index, "OffsetDateTime");
        }
        return value;
    }

    /**
     * Resolves the value at the specified index to an {@link OffsetDateTime}.
     * <br>
     * <b>Note:</b> This method should only be used on ISO8601 timestamps
     *
     * @param index
     *                     The index to resolve
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
    public OffsetDateTime getOffsetDateTime(int index, @Nullable OffsetDateTime defaultValue) {
        if (index < 0 || index >= data.size()) {
            return defaultValue;
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> defaultValue;
            case OffsetDateTime offsetDateTime -> offsetDateTime;
            case CharSequence charSequence -> {
                try {
                    yield OffsetDateTime.parse(charSequence);
                } catch (DateTimeParseException e) {
                    throw new ParsingException(
                            Helpers.format(
                                    "Cannot parse value for index %d into an OffsetDateTime object. Try double checking that %s is a valid ISO8601 timestamp",
                                    index, e.getParsedString()),
                            e);
                }
            }
            default -> {
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type OffsetDateTime: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
            }
        };
    }

    /**
     * Resolves the value at the specified index to an unsigned long.
     *
     * @param index
     *                     The index to resolve
     * @param defaultValue
     *                     Alternative value to use when the value associated with
     *                     the index is null
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved unsigned long value
     */
    public long getUnsignedLong(int index, long defaultValue) {
        if (index < 0 || index >= data.size()) {
            return defaultValue;
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> defaultValue;
            case Number number -> number.longValue();
            case String s -> Long.parseUnsignedLong(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Long: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to a double.
     *
     * @param index
     *              The index to resolve
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved double value
     */
    public double getDouble(int index) {
        if (index < 0 || index >= data.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> throw valueError(index, "double");
            case Number number -> number.doubleValue();
            case String s -> Double.parseDouble(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Double: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Resolves the value at the specified index to a double.
     *
     * @param index
     *                     The index to resolve
     * @param defaultValue
     *                     Alternative value to use when the value associated with
     *                     the index is null
     *
     * @throws ParsingException
     *                                                         If the value is of
     *                                                         the wrong type
     *
     * @return The resolved double value
     */
    public double getDouble(int index, double defaultValue) {
        if (index < 0 || index >= data.size()) {
            return defaultValue;
        }
        Object value = data.get(index);
        return switch (value) {
            case null -> defaultValue;
            case Number number -> number.doubleValue();
            case String s -> Double.parseDouble(s);
            default ->
                throw new ParsingException(Helpers.format(
                        "Cannot parse value for index %d into type Double: %s instance of %s",
                        index, value, value.getClass().getSimpleName()));
        };
    }

    /**
     * Appends the provided value to the end of the array.
     *
     * @param value
     *              The value to append
     *
     * @return A DataArray with the value inserted at the end
     */
    @Nonnull
    public DataArray add(@Nullable Object value) {
        switch (value) {
            case SerializableData serializable -> data.add(serializable.toData().data);
            case SerializableArray serializable -> data.add(serializable.toDataArray().data);
            case null, default -> data.add(value);
        }
        return this;
    }

    /**
     * Appends the provided values to the end of the array.
     *
     * @param values
     *               The values to append
     *
     * @return A DataArray with the values inserted at the end
     */
    @Nonnull
    public DataArray addAll(@Nonnull Collection<?> values) {
        values.forEach(this::add);
        return this;
    }

    /**
     * Appends the provided values to the end of the array.
     *
     * @param array
     *              The values to append
     *
     * @return A DataArray with the values inserted at the end
     */
    @Nonnull
    public DataArray addAll(@Nonnull DataArray array) {
        return addAll(array.data);
    }

    /**
     * Inserts the specified value at the provided index.
     *
     * @param index
     *              The target index
     * @param value
     *              The value to insert
     *
     * @return A DataArray with the value inserted at the specified index
     */
    @Nonnull
    public DataArray insert(int index, @Nullable Object value) {
        switch (value) {
            case SerializableData serializable -> data.add(index, serializable.toData().data);
            case SerializableArray serializable -> data.add(index, serializable.toDataArray().data);
            case null, default -> data.add(index, value);
        }
        return this;
    }

    /**
     * Removes the value at the specified index.
     *
     * @param index
     *              The target index to remove
     *
     * @return A DataArray with the value removed
     */
    @Nonnull
    public DataArray remove(int index) {
        data.remove(index);
        return this;
    }

    /**
     * Removes the specified value.
     *
     * @param value
     *              The value to remove
     *
     * @return A DataArray with the value removed
     */
    @Nonnull
    public DataArray remove(@Nullable Object value) {
        data.remove(value);
        return this;
    }

    /**
     * Serializes this object as JSON.
     *
     * @return byte array containing the JSON representation of this object
     */
    @Nonnull
    public byte[] toJson() {
        return SerializationUtil.toJson(data);
    }

    /**
     * Serializes this object as ETF LIST term.
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
     * Converts this DataArray to a {@link List}.
     *
     * @return The resulting list
     */
    @Nonnull
    public List<Object> toList() {
        return data;
    }

    private ParsingException valueError(int index, String expectedType) {
        return new DataArrayParsingException(
                this, "Unable to resolve value at " + index + " to type " + expectedType + ": " + data.get(index));
    }

    @Nullable
    private <T> T get(@Nonnull Class<T> type, int index) {
        return get(type, index, null, null);
    }

    @Nullable
    private <T> T get(
            @Nonnull Class<T> type,
            int index,
            @Nullable Function<String, T> stringMapper,
            @Nullable Function<Number, T> numberMapper) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        Object value = index < data.size() ? data.get(index) : null;
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
        if (stringMapper != null && value instanceof String) {
            return stringMapper.apply((String) value);
        } else if (numberMapper != null && value instanceof Number) {
            return numberMapper.apply((Number) value);
        }

        throw new ParsingException(Helpers.format(
                "Cannot parse value for index %d into type %s: %s instance of %s",
                index, type.getSimpleName(), value, value.getClass().getSimpleName()));
    }

    @Nonnull
    @Override
    public Iterator<Object> iterator() {
        return data.iterator();
    }

    @Nonnull
    public <T> Stream<T> stream(@Nonnull BiFunction<? super DataArray, Integer, ? extends T> mapper) {
        return IntStream.range(0, length()).mapToObj(index -> mapper.apply(this, index));
    }

    /**
     * Converts this array of snowflake IDs (strings or numbers) into a primitive
     * {@link LongList}.
     *
     * @return A {@link LongList} containing the parsed unsigned long values
     */
    @Nonnull
    public LongList toLongList() {
        LongArrayList list = new LongArrayList(data.size());
        for (int i = 0; i < data.size(); i++) {
            list.add(getUnsignedLong(i));
        }
        return list;
    }

    /**
     * Converts this array of snowflake IDs (strings or numbers) into a primitive
     * {@link LongSet}.
     *
     * @return A {@link LongSet} containing the parsed unsigned long values
     */
    @Nonnull
    public LongSet toLongSet() {
        LongOpenHashSet set = new LongOpenHashSet(data.size());
        for (int i = 0; i < data.size(); i++) {
            set.add(getUnsignedLong(i));
        }
        return set;
    }

    @Nonnull
    @Override
    public DataArray toDataArray() {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DataArray objects)) {
            return false;
        }
        return Objects.equals(data, objects.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data);
    }
}
