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
import io.netty.buffer.ByteBufAllocator;
import net.dv8tion.jda.api.utils.MediaType;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.internal.utils.requestbody.ByteBufRequestBody;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;

public class IOUtil {
    private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    public static void silentClose(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
        }
    }

    public static void silentClose(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException ignored) {
        }
    }

    public static String addQuery(String base, Object... params) {
        if (params == null || params.length == 0) {
            return base;
        }
        StringBuilder builder = new StringBuilder(base);
        int queryStart = base.indexOf('?');
        if (queryStart == -1) {
            builder.append('?');
        } else if (queryStart < base.length() - 1 && !base.endsWith("&") && !base.endsWith("?")) {
            builder.append('&');
        }

        for (int i = 0; i < params.length; i += 2) {
            builder.append(params[i])
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(params[i + 1]), StandardCharsets.UTF_8))
                    .append('&');
        }
        builder.setLength(builder.length() - 1);
        return builder.toString();
    }

    /**
     * Provided as a simple way to fully read an InputStream into a byte[].
     *
     * <p>
     * This method will block until the InputStream has been fully read, so if you
     * provide an InputStream that is
     * non-finite, you're gonna have a bad time.
     *
     * @param stream
     *               The Stream to be read.
     *
     * @throws IOException
     *                     If the first byte cannot be read for any reason other
     *                     than the end of the file,
     *                     if the input stream has been closed, or if some other I/O
     *                     error occurs.
     *
     * @return A byte[] containing all of the data provided by the InputStream
     */
    public static byte[] readFully(InputStream stream) throws IOException {
        Checks.notNull(stream, "InputStream");
        return stream.readAllBytes();
    }

    /**
     * Creates a new request body that transmits the provided
     * {@link InputStream}.
     * Uses {@link NettyConfig#getGlobalAllocator()} as the allocator.
     *
     * @param contentType
     *                    The {@link MediaType MediaType} of the data
     * @param stream
     *                    The {@link InputStream} to be
     *                    transmitted
     *
     * @return ByteBufRequestBody capable of transmitting the provided InputStream
     *         of data
     */
    public static ByteBufRequestBody createRequestBody(MediaType contentType, InputStream stream) {
        return createRequestBody(contentType, stream, NettyConfig.getGlobalAllocator());
    }

    /**
     * Creates a new request body that transmits the provided
     * {@link InputStream}, using the specified allocator.
     *
     * @param contentType
     *                    The {@link MediaType MediaType} of the data
     * @param stream
     *                    The {@link InputStream} to be
     *                    transmitted
     * @param allocator
     *                    The {@link ByteBufAllocator} to use for buffer allocation
     *
     * @return ByteBufRequestBody capable of transmitting the provided InputStream
     *         of data
     */
    public static ByteBufRequestBody createRequestBody(
            MediaType contentType, InputStream stream, ByteBufAllocator allocator) {
        try {
            ByteBuf buffer = readIntoByteBuf(stream, allocator);
            return new ByteBufRequestBody(buffer, contentType);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read InputStream into ByteBuf", e);
        }
    }

    @Nonnull
    @CheckReturnValue
    public static ByteBuf readIntoByteBuf(@Nonnull InputStream stream, @Nonnull ByteBufAllocator allocator)
            throws IOException {
        Checks.notNull(stream, "InputStream");
        Checks.notNull(allocator, "ByteBufAllocator");
        if (stream instanceof FileInputStream fis) {
            FileChannel channel = fis.getChannel();
            long size = channel.size();
            if (size <= Integer.MAX_VALUE) {
                int length = (int) size;
                ByteBuf buf = allocator.buffer(length);
                try {
                    buf.writeBytes(channel, 0, length);
                    return buf;
                } catch (Throwable t) {
                    buf.release();
                    throw t;
                } finally {
                    silentClose(stream);
                }
            }
        }
        int available = 0;
        try {
            available = stream.available();
        } catch (IOException ignored) {
        }
        int initialCapacity = Math.max(8192, available);
        ByteBuf buf = allocator.buffer(initialCapacity);
        try {
            while (buf.writeBytes(stream, 8192) > 0) {}
            return buf;
        } catch (Throwable t) {
            buf.release();
            throw t;
        } finally {
            silentClose(stream);
        }
    }

    @Nonnull
    @CheckReturnValue
    public static ByteBuf readIntoByteBuf(
            @Nonnull Path path, @Nonnull ByteBufAllocator allocator, @Nonnull OpenOption... options)
            throws IOException {
        Checks.notNull(path, "Path");
        Checks.notNull(allocator, "ByteBufAllocator");
        Checks.notNull(options, "OpenOptions");
        long size = Files.size(path);
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("File size exceeds maximum supported ByteBuf capacity: " + size);
        }
        int length = (int) size;
        try (FileChannel channel = FileChannel.open(path, options)) {
            ByteBuf buf = allocator.buffer(length);
            try {
                buf.writeBytes(channel, 0, length);
                return buf;
            } catch (Throwable t) {
                buf.release();
                throw t;
            }
        }
    }

    public static void setIntBigEndian(byte[] arr, int offset, int it) {
        INT_VIEW.set(arr, offset, it);
    }

    @Nonnull
    @CheckReturnValue
    public static ByteBuffer allocateLike(@Nonnull ByteBuffer original, int length) {
        return original.isDirect() ? ByteBuffer.allocateDirect(length) : ByteBuffer.allocate(length);
    }

    @Nonnull
    @CheckReturnValue
    public static ByteBuffer replace(@Nonnull ByteBuffer destination, @Nonnull ByteBuffer source) {
        if (destination.capacity() < source.remaining()) {
            destination = allocateLike(destination, (int) (1.25 * source.remaining()));
        }

        destination.clear();
        destination.put(source);
        destination.flip();
        return destination;
    }
}
