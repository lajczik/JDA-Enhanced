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

package net.dv8tion.jda.api.utils.data.etf;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.InflaterOutputStream;

import javax.annotation.Nonnull;

import static net.dv8tion.jda.api.utils.data.etf.ExTermTag.*;

/**
 * Decodes an ETF encoded payload to a java object representation.
 *
 * @see #unpack(ByteBuf)
 * @see #unpackMap(ByteBuf)
 * @see #unpackList(ByteBuf)
 * @see #unpack(ByteBuffer)
 * @see #unpackMap(ByteBuffer)
 * @see #unpackList(ByteBuffer)
 */
public class ExTermDecoder {
    private static final int STRING_CACHE_SIZE = 2048;
    private static final int STRING_CACHE_MASK = STRING_CACHE_SIZE - 1;

    private static class CachedEntry {
        final byte[] bytes;
        final String value;

        CachedEntry(byte[] bytes, String value) {
            this.bytes = bytes;
            this.value = value;
        }
    }

    private static final CachedEntry[] STRING_CACHE = new CachedEntry[STRING_CACHE_SIZE];

    private static int computeByteBufHash(ByteBuf buffer, int readerIndex, int length) {
        int h = 1;
        for (int i = 0; i < length; i++) {
            h = 31 * h + buffer.getByte(readerIndex + i);
        }
        return h;
    }

    private static boolean equalsBytes(ByteBuf buffer, int readerIndex, byte[] cachedBytes) {
        for (int i = 0; i < cachedBytes.length; i++) {
            if (buffer.getByte(readerIndex + i) != cachedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unpacks the provided term into a java object.
     *
     * @param  buffer
     *         The {@link ByteBuf} containing the encoded term
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with the version byte {@code 131} or contains an unsupported tag
     *
     * @return The java object
     */
    @Nonnull
    public static Object unpack(@Nonnull ByteBuf buffer) {
        return unpack(buffer, true);
    }

    /**
     * Unpacks the provided term into a java object.
     *
     * @param  buffer
     *         The {@link ByteBuf} containing the encoded term
     * @param  deduplicateStrings
     *         Whether to intern/deduplicate parsed strings
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with the version byte {@code 131} or contains an unsupported tag
     *
     * @return The java object
     */
    @Nonnull
    public static Object unpack(@Nonnull ByteBuf buffer, boolean deduplicateStrings) {
        if (buffer.readByte() != -125) {
            throw new IllegalArgumentException("Failed header check");
        }

        return unpack0(buffer, deduplicateStrings);
    }

    /**
     * Unpacks the provided term into a java {@link Map}.
     *
     * @param  buffer
     *         The {@link ByteBuf} containing the encoded term
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with a Map term, does not have the right version byte, or the format includes an unsupported tag
     *
     * @return The parsed {@link Map} instance
     */
    @Nonnull
    public static Map<String, Object> unpackMap(@Nonnull ByteBuf buffer) {
        return unpackMap(buffer, true);
    }

    /**
     * Unpacks the provided term into a java {@link Map}.
     *
     * @param  buffer
     *         The {@link ByteBuf} containing the encoded term
     * @param  deduplicateStrings
     *         Whether to intern/deduplicate parsed strings
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with a Map term, does not have the right version byte, or the format includes an unsupported tag
     *
     * @return The parsed {@link Map} instance
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public static Map<String, Object> unpackMap(@Nonnull ByteBuf buffer, boolean deduplicateStrings) {
        byte tag = buffer.getByte(buffer.readerIndex() + 1);
        if (tag != MAP) {
            throw new IllegalArgumentException("Cannot unpack map from tag " + tag);
        }
        return (Map<String, Object>) unpack(buffer, deduplicateStrings);
    }

    /**
     * Unpacks the provided term into a java {@link List}.
     *
     * @param  buffer
     *         The {@link ByteBuf} containing the encoded term
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with a List or NIL term, does not have the right version byte, or the format includes an unsupported tag
     *
     * @return The parsed {@link List} instance
     */
    @Nonnull
    public static List<Object> unpackList(@Nonnull ByteBuf buffer) {
        return unpackList(buffer, true);
    }

    /**
     * Unpacks the provided term into a java {@link List}.
     *
     * @param  buffer
     *         The {@link ByteBuf} containing the encoded term
     * @param  deduplicateStrings
     *         Whether to intern/deduplicate parsed strings
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with a List or NIL term, does not have the right version byte, or the format includes an unsupported tag
     *
     * @return The parsed {@link List} instance
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public static List<Object> unpackList(@Nonnull ByteBuf buffer, boolean deduplicateStrings) {
        byte tag = buffer.getByte(buffer.readerIndex() + 1);
        if (tag != LIST) {
            throw new IllegalArgumentException("Cannot unpack list from tag " + tag);
        }

        return (List<Object>) unpack(buffer, deduplicateStrings);
    }

    /**
     * Unpacks the provided term into a java object.
     *
     * @param  buffer
     *         The {@link ByteBuffer} containing the encoded term
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with the version byte {@code 131} or contains an unsupported tag
     *
     * @return The java object
     */
    @Nonnull
    public static Object unpack(@Nonnull ByteBuffer buffer) {
        return unpack(Unpooled.wrappedBuffer(buffer), true);
    }

    /**
     * Unpacks the provided term into a java object.
     *
     * @param  buffer
     *         The {@link ByteBuffer} containing the encoded term
     * @param  deduplicateStrings
     *         Whether to intern/deduplicate parsed strings
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with the version byte {@code 131} or contains an unsupported tag
     *
     * @return The java object
     */
    @Nonnull
    public static Object unpack(@Nonnull ByteBuffer buffer, boolean deduplicateStrings) {
        return unpack(Unpooled.wrappedBuffer(buffer), deduplicateStrings);
    }

    /**
     * Unpacks the provided term into a java {@link Map}.
     *
     * @param  buffer
     *         The {@link ByteBuffer} containing the encoded term
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with a Map term, does not have the right version byte, or the format includes an unsupported tag
     *
     * @return The parsed {@link Map} instance
     */
    @Nonnull
    public static Map<String, Object> unpackMap(@Nonnull ByteBuffer buffer) {
        return unpackMap(Unpooled.wrappedBuffer(buffer), true);
    }

    /**
     * Unpacks the provided term into a java {@link Map}.
     *
     * @param  buffer
     *         The {@link ByteBuffer} containing the encoded term
     * @param  deduplicateStrings
     *         Whether to intern/deduplicate parsed strings
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with a Map term, does not have the right version byte, or the format includes an unsupported tag
     *
     * @return The parsed {@link Map} instance
     */
    @Nonnull
    public static Map<String, Object> unpackMap(@Nonnull ByteBuffer buffer, boolean deduplicateStrings) {
        return unpackMap(Unpooled.wrappedBuffer(buffer), deduplicateStrings);
    }

    /**
     * Unpacks the provided term into a java {@link List}.
     *
     * @param  buffer
     *         The {@link ByteBuffer} containing the encoded term
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with a List or NIL term, does not have the right version byte, or the format includes an unsupported tag
     *
     * @return The parsed {@link List} instance
     */
    @Nonnull
    public static List<Object> unpackList(@Nonnull ByteBuffer buffer) {
        return unpackList(Unpooled.wrappedBuffer(buffer), true);
    }

    /**
     * Unpacks the provided term into a java {@link List}.
     *
     * @param  buffer
     *         The {@link ByteBuffer} containing the encoded term
     * @param  deduplicateStrings
     *         Whether to intern/deduplicate parsed strings
     *
     * @throws IllegalArgumentException
     *         If the buffer does not start with a List or NIL term, does not have the right version byte, or the format includes an unsupported tag
     *
     * @return The parsed {@link List} instance
     */
    @Nonnull
    public static List<Object> unpackList(@Nonnull ByteBuffer buffer, boolean deduplicateStrings) {
        return unpackList(Unpooled.wrappedBuffer(buffer), deduplicateStrings);
    }

    private static Object unpack0(@Nonnull ByteBuf buffer, boolean deduplicateStrings) {
        int tag = buffer.readByte();
        return switch (tag) {
            case COMPRESSED -> unpackCompressed(buffer, deduplicateStrings);
            case SMALL_INT -> unpackSmallInt(buffer);
            case SMALL_BIGINT -> unpackSmallBigint(buffer);
            case INT -> unpackInt(buffer);
            case FLOAT -> unpackOldFloat(buffer);
            case NEW_FLOAT -> unpackFloat(buffer);
            case SMALL_ATOM_UTF8 -> unpackSmallAtom(buffer, StandardCharsets.UTF_8, deduplicateStrings);
            case SMALL_ATOM -> unpackSmallAtom(buffer, StandardCharsets.ISO_8859_1, deduplicateStrings);
            case ATOM_UTF8 -> unpackAtom(buffer, StandardCharsets.UTF_8, deduplicateStrings);
            case ATOM -> unpackAtom(buffer, StandardCharsets.ISO_8859_1, deduplicateStrings);
            case MAP -> unpackMap0(buffer, deduplicateStrings);
            case LIST -> unpackList0(buffer, deduplicateStrings);
            case NIL -> List.of();
            case STRING -> unpackString(buffer);
            case BINARY -> unpackBinary(buffer, deduplicateStrings);
            default -> throw new IllegalArgumentException("Unknown tag " + tag);
        };
    }

    private static Object unpackCompressed(@Nonnull ByteBuf buffer, boolean deduplicateStrings) {
        int size = buffer.readInt();
        ByteBuf decompressed = buffer.alloc().buffer(size);
        try (InflaterOutputStream inflater = new InflaterOutputStream(new ByteBufOutputStream(decompressed))) {
            buffer.readBytes(inflater, buffer.readableBytes());
        } catch (IOException e) {
            decompressed.release();
            throw new UncheckedIOException(e);
        }

        try {
            return unpack0(decompressed, deduplicateStrings);
        } finally {
            decompressed.release();
        }
    }

    private static double unpackOldFloat(@Nonnull ByteBuf buffer) {
        String bytes = buffer.readCharSequence(31, StandardCharsets.ISO_8859_1).toString();
        return Double.parseDouble(bytes);
    }

    private static double unpackFloat(@Nonnull ByteBuf buffer) {
        return buffer.readDouble();
    }

    private static long unpackSmallBigint(@Nonnull ByteBuf buffer) {
        int arity = buffer.readUnsignedByte();
        int sign = buffer.readUnsignedByte();
        if (arity == 8) {
            long sum = buffer.readLongLE();
            return sign == 0 ? sum : -sum;
        }
        if (arity == 4) {
            long sum = buffer.readUnsignedIntLE();
            return sign == 0 ? sum : -sum;
        }
        long sum = 0;
        long offset = 0;
        while (arity-- > 0) {
            sum += ((long) buffer.readUnsignedByte()) << offset;
            offset += 8;
        }

        return sign == 0 ? sum : -sum;
    }

    private static int unpackSmallInt(@Nonnull ByteBuf buffer) {
        return buffer.readUnsignedByte();
    }

    private static int unpackInt(@Nonnull ByteBuf buffer) {
        return buffer.readInt();
    }

    private static List<Object> unpackString(@Nonnull ByteBuf buffer) {
        int length = buffer.readUnsignedShort();
        List<Object> bytes = new ArrayList<>(length);
        while (length-- > 0) {
            bytes.add(buffer.readByte());
        }
        return bytes;
    }

    private static String unpackBinary(@Nonnull ByteBuf buffer, boolean deduplicateStrings) {
        int length = buffer.readInt();
        int readerIndex = buffer.readerIndex();
        if (deduplicateStrings && length <= 64) {
            int hash = computeByteBufHash(buffer, readerIndex, length);
            int slot = (hash ^ (hash >>> 16)) & STRING_CACHE_MASK;
            CachedEntry entry = STRING_CACHE[slot];
            if (entry != null && entry.bytes.length == length && equalsBytes(buffer, readerIndex, entry.bytes)) {
                buffer.skipBytes(length);
                return entry.value;
            }
            String str = buffer.readCharSequence(length, StandardCharsets.UTF_8)
                    .toString()
                    .intern();
            byte[] keyBytes = new byte[length];
            buffer.getBytes(readerIndex, keyBytes);
            STRING_CACHE[slot] = new CachedEntry(keyBytes, str);
            return str;
        }
        String str = buffer.readCharSequence(length, StandardCharsets.UTF_8).toString();
        return deduplicateStrings ? str.intern() : str;
    }

    private static Object unpackSmallAtom(
            @Nonnull ByteBuf buffer, @Nonnull Charset charset, boolean deduplicateStrings) {
        int length = buffer.readUnsignedByte();
        return unpackAtom(buffer, charset, length, deduplicateStrings);
    }

    private static Object unpackAtom(@Nonnull ByteBuf buffer, @Nonnull Charset charset, boolean deduplicateStrings) {
        int length = buffer.readUnsignedShort();
        return unpackAtom(buffer, charset, length, deduplicateStrings);
    }

    private static Object unpackAtom(
            @Nonnull ByteBuf buffer, @Nonnull Charset charset, int length, boolean deduplicateStrings) {
        int readerIndex = buffer.readerIndex();
        if (length == 3) {
            if (buffer.getByte(readerIndex) == 'n'
                    && buffer.getByte(readerIndex + 1) == 'i'
                    && buffer.getByte(readerIndex + 2) == 'l') {
                buffer.skipBytes(3);
                return null;
            }
        } else if (length == 4) {
            if (buffer.getByte(readerIndex) == 't'
                    && buffer.getByte(readerIndex + 1) == 'r'
                    && buffer.getByte(readerIndex + 2) == 'u'
                    && buffer.getByte(readerIndex + 3) == 'e') {
                buffer.skipBytes(4);
                return true;
            }
        } else if (length == 5) {
            if (buffer.getByte(readerIndex) == 'f'
                    && buffer.getByte(readerIndex + 1) == 'a'
                    && buffer.getByte(readerIndex + 2) == 'l'
                    && buffer.getByte(readerIndex + 3) == 's'
                    && buffer.getByte(readerIndex + 4) == 'e') {
                buffer.skipBytes(5);
                return false;
            }
        }

        if (deduplicateStrings && length <= 128) {
            int hash = computeByteBufHash(buffer, readerIndex, length);
            int slot = (hash ^ (hash >>> 16)) & STRING_CACHE_MASK;
            CachedEntry entry = STRING_CACHE[slot];
            if (entry != null && entry.bytes.length == length && equalsBytes(buffer, readerIndex, entry.bytes)) {
                buffer.skipBytes(length);
                return entry.value;
            }
            String value = buffer.readCharSequence(length, charset).toString().intern();
            byte[] keyBytes = new byte[length];
            buffer.getBytes(readerIndex, keyBytes);
            STRING_CACHE[slot] = new CachedEntry(keyBytes, value);
            return value;
        }

        String value = buffer.readCharSequence(length, charset).toString();
        return deduplicateStrings ? value.intern() : value;
    }

    private static List<Object> unpackList0(@Nonnull ByteBuf buffer, boolean deduplicateStrings) {
        int length = buffer.readInt();
        List<Object> list = new ArrayList<>(length);
        while (length-- > 0) {
            list.add(unpack0(buffer, deduplicateStrings));
        }
        Object tail = unpack0(buffer, deduplicateStrings);
        if (!Objects.equals(tail, List.of())) {
            throw new IllegalArgumentException("Unexpected tail " + tail);
        }
        return list;
    }

    private static Map<String, Object> unpackMap0(@Nonnull ByteBuf buffer, boolean deduplicateStrings) {
        int arity = buffer.readInt();
        Map<String, Object> map = HashMap.newHashMap(arity);
        while (arity-- > 0) {
            Object rawKey = unpack0(buffer, deduplicateStrings);
            String key = rawKey instanceof String s ? s : String.valueOf(rawKey);
            if (deduplicateStrings && !(rawKey instanceof String)) {
                key = key.intern();
            }
            Object value = unpack0(buffer, deduplicateStrings);
            map.put(key, value);
        }
        return map;
    }
}
