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

package net.dv8tion.jda.internal.utils.requestbody;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import net.dv8tion.jda.api.utils.MediaType;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.internal.utils.Checks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class RequestBody {

    @Nullable
    public abstract MediaType contentType();

    @Nullable
    public String contentTypeHeader() {
        MediaType type = contentType();
        return type != null ? type.getValue() : null;
    }

    public long contentLength() throws IOException {
        return -1L;
    }

    public void writeTo(@Nonnull OutputStream out) throws IOException {
        Checks.notNull(out, "OutputStream");
        long length = contentLength();
        ByteBuf buf = length >= 0
                ? NettyConfig.getGlobalAllocator().buffer((int) length)
                : NettyConfig.getGlobalAllocator().buffer();
        try {
            writeTo(buf);
            buf.getBytes(buf.readerIndex(), out, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    public void writeTo(@Nonnull ByteBuf out) throws IOException {
        Checks.notNull(out, "ByteBuf");
        writeTo((OutputStream) new ByteBufOutputStream(out));
    }

    @Nonnull
    public ByteBuf getByteBuf() throws IOException {
        return getByteBuf(NettyConfig.getGlobalAllocator());
    }

    @Nonnull
    public ByteBuf getByteBuf(@Nonnull ByteBufAllocator allocator) throws IOException {
        Checks.notNull(allocator, "ByteBufAllocator");
        long length = contentLength();
        ByteBuf buffer = length >= 0 ? allocator.buffer((int) length) : allocator.buffer();
        try {
            writeTo(buffer);
            return buffer;
        } catch (Throwable t) {
            buffer.release();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException(t);
        }
    }

    @Nonnull
    public byte[] toBytes() throws IOException {
        ByteBuf buffer = getByteBuf();
        try {
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Nonnull
    public InputStream getInputStream() throws IOException {
        return new ByteBufInputStream(getByteBuf(NettyConfig.getGlobalAllocator()), true);
    }
}
