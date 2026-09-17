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
import io.netty.buffer.ByteBufUtil;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.SerializableData;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static net.dv8tion.jda.api.utils.data.etf.ExTermTag.*;

/**
 * Encodes an object into a binary ETF representation.
 *
 * @see #pack(Object)
 * @see #pack(ByteBuf, Object)
 */
public class ExTermEncoder {
    /**
     * Encodes the provided object into an ETF buffer.
     *
     * <p><b>The mapping is as follows:</b><br>
     * <ul>
     *     <li>{@code String -> Binary}</li>
     *     <li>{@code Map -> Map}</li>
     *     <li>{@code Collection -> List | NIL}</li>
     *     <li>{@code Byte -> Small Int}</li>
     *     <li>{@code Integer, Short -> Int | Small Int}</li>
     *     <li>{@code Long -> Small BigInt | Int | Small Int}</li>
     *     <li>{@code Float, Double -> New Float}</li>
     *     <li>{@code Boolean -> Atom(Boolean)}</li>
     *     <li>{@code null -> Atom("nil")}</li>
     * </ul>
     *
     * @param  data
     *         The object to encode
     *
     * @throws UnsupportedOperationException
     *         If there is no type mapping for the provided object
     *
     * @return {@link ByteBuf} with the encoded ETF term
     */
    @Nonnull
    public static ByteBuf pack(@Nullable Object data) {
        // Use pooled heap buffer: toETF() callers extract byte[] immediately, so heapBuffer
        // avoids extra copy while still benefiting from the pool.
        ByteBuf buffer = NettyConfig.getGlobalAllocator().heapBuffer(1024);
        buffer.writeByte(131);
        pack(buffer, data);
        return buffer;
    }

    /**
     * Encodes the provided object directly into a Netty {@link ByteBuf}.
     *
     * @param  buffer
     *         The {@link ByteBuf} to write into
     * @param  value
     *         The object to encode
     *
     * @throws UnsupportedOperationException
     *         If there is no type mapping for the provided object
     *
     * @return The same {@link ByteBuf} for chaining
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public static ByteBuf pack(@Nonnull ByteBuf buffer, @Nullable Object value) {
        switch (value) {
            case String s -> packBinary(buffer, s);
            case Map<?, ?> m -> packMap(buffer, (Map<String, Object>) m);
            case SerializableData sd -> packMap(buffer, sd.toData().toMap());
            case Collection<?> col -> packList(buffer, (Collection<Object>) col);
            case DataArray arr -> packList(buffer, arr.toList());
            case Byte b -> packSmallInt(buffer, b);
            case Short s -> packInt(buffer, s.intValue());
            case Integer i -> packInt(buffer, i);
            case Long l -> packLong(buffer, l);
            case Float f -> packFloat(buffer, f.doubleValue());
            case Double d -> packFloat(buffer, d);
            case Boolean b -> packAtom(buffer, String.valueOf(b));
            case null -> packAtom(buffer, "nil");
            case long[] arr -> packArray(buffer, arr);
            case int[] arr -> packArray(buffer, arr);
            case short[] arr -> packArray(buffer, arr);
            case byte[] arr -> packArray(buffer, arr);
            case Object[] arr -> packList(buffer, Arrays.asList(arr));
            default ->
                throw new UnsupportedOperationException(
                        "Cannot pack value of type " + value.getClass().getName());
        }
        return buffer;
    }

    @Nonnull
    private static ByteBuf packMap(@Nonnull ByteBuf buffer, @Nonnull Map<String, Object> data) {
        buffer.writeByte(MAP);
        buffer.writeInt(data.size());
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            packBinary(buffer, entry.getKey());
            pack(buffer, entry.getValue());
        }
        return buffer;
    }

    @Nonnull
    private static ByteBuf packList(@Nonnull ByteBuf buffer, @Nonnull Collection<Object> data) {
        if (data.isEmpty()) {
            return packNil(buffer);
        }
        buffer.writeByte(LIST);
        buffer.writeInt(data.size());
        for (Object element : data) {
            pack(buffer, element);
        }
        return packNil(buffer);
    }

    @Nonnull
    private static ByteBuf packNil(@Nonnull ByteBuf buffer) {
        buffer.writeByte(NIL);
        return buffer;
    }

    @Nonnull
    private static ByteBuf packBinary(@Nonnull ByteBuf buffer, @Nonnull String value) {
        buffer.writeByte(BINARY);
        int utf8Len = ByteBufUtil.utf8Bytes(value);
        buffer.writeInt(utf8Len);
        ByteBufUtil.writeUtf8(buffer, value);
        return buffer;
    }

    @Nonnull
    private static ByteBuf packSmallInt(@Nonnull ByteBuf buffer, byte value) {
        buffer.writeByte(SMALL_INT);
        buffer.writeByte(value);
        return buffer;
    }

    @Nonnull
    private static ByteBuf packInt(@Nonnull ByteBuf buffer, int value) {
        if (countBytes(value) <= 1 && value >= 0) {
            return packSmallInt(buffer, (byte) value);
        }
        buffer.writeByte(INT);
        buffer.writeInt(value);
        return buffer;
    }

    @Nonnull
    private static ByteBuf packLong(@Nonnull ByteBuf buffer, long value) {
        byte bytes = countBytes(value);
        if (bytes <= 1) {
            return packSmallInt(buffer, (byte) value);
        }
        if (bytes <= 4 && value >= 0) {
            buffer.writeByte(INT);
            buffer.writeInt((int) value);
            return buffer;
        }
        buffer.writeByte(SMALL_BIGINT);
        buffer.writeByte(bytes);
        buffer.writeByte(0); // unsigned positive
        while (value > 0) {
            buffer.writeByte((byte) value);
            value >>>= 8;
        }
        return buffer;
    }

    @Nonnull
    private static ByteBuf packFloat(@Nonnull ByteBuf buffer, double value) {
        buffer.writeByte(NEW_FLOAT);
        buffer.writeDouble(value);
        return buffer;
    }

    @Nonnull
    private static ByteBuf packAtom(@Nonnull ByteBuf buffer, String value) {
        buffer.writeByte(ATOM);
        buffer.writeShort(value.length());
        buffer.writeCharSequence(value, StandardCharsets.ISO_8859_1);
        return buffer;
    }

    @Nonnull
    private static ByteBuf packArray(@Nonnull ByteBuf buffer, @Nonnull long[] array) {
        if (array.length == 0) {
            return packNil(buffer);
        }
        buffer.writeByte(LIST);
        buffer.writeInt(array.length);
        for (long it : array) {
            packLong(buffer, it);
        }
        return packNil(buffer);
    }

    @Nonnull
    private static ByteBuf packArray(@Nonnull ByteBuf buffer, @Nonnull int[] array) {
        if (array.length == 0) {
            return packNil(buffer);
        }
        buffer.writeByte(LIST);
        buffer.writeInt(array.length);
        for (int it : array) {
            packInt(buffer, it);
        }
        return packNil(buffer);
    }

    @Nonnull
    private static ByteBuf packArray(@Nonnull ByteBuf buffer, @Nonnull short[] array) {
        if (array.length == 0) {
            return packNil(buffer);
        }
        buffer.writeByte(LIST);
        buffer.writeInt(array.length);
        for (short it : array) {
            packInt(buffer, it);
        }
        return packNil(buffer);
    }

    @Nonnull
    private static ByteBuf packArray(@Nonnull ByteBuf buffer, @Nonnull byte[] array) {
        if (array.length == 0) {
            return packNil(buffer);
        }
        buffer.writeByte(LIST);
        buffer.writeInt(array.length);
        for (byte it : array) {
            packSmallInt(buffer, it);
        }
        return packNil(buffer);
    }

    private static byte countBytes(long value) {
        int leadingZeros = Long.numberOfLeadingZeros(value);
        return (byte) Math.ceil((64 - leadingZeros) / 8.);
    }
}
