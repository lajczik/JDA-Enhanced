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
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.MediaType;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.SerializationUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Request body for JSON payloads, serialized using {@link SerializationUtil}.
 */
public class JsonRequestBody extends RequestBody {
    private final Object data;

    public JsonRequestBody(@Nonnull Object data) {
        Checks.notNull(data, "Data");
        this.data = data;
    }

    @Nonnull
    public Object getData() {
        return data;
    }

    @Nullable
    @Override
    public MediaType contentType() {
        return MediaType.JSON;
    }

    @Override
    public long contentLength() throws IOException {
        ByteBuf buf = getByteBuf();
        try {
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    @Override
    public void writeTo(@Nonnull OutputStream out) {
        Checks.notNull(out, "OutputStream");
        SerializationUtil.writeJson(out, data);
    }

    @Override
    public void writeTo(@Nonnull ByteBuf target) throws ParsingException {
        Checks.notNull(target, "ByteBuf");
        SerializationUtil.writeJson(target, data);
    }

    @Nonnull
    @Override
    public ByteBuf getByteBuf(@Nonnull ByteBufAllocator allocator) throws ParsingException {
        Checks.notNull(allocator, "ByteBufAllocator");
        ByteBuf buf = allocator.buffer();
        try {
            SerializationUtil.writeJson(buf, data);
            return buf;
        } catch (Exception e) {
            buf.release();
            throw new ParsingException("Failed to serialize JSON body", e);
        }
    }

    @Nonnull
    @Override
    public byte[] toBytes() throws ParsingException {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            SerializationUtil.writeJson(stream, data);
            return stream.toByteArray();
        } catch (Exception e) {
            throw new ParsingException("Failed to serialize JSON body", e);
        }
    }

    @Nonnull
    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteBufInputStream(getByteBuf(), true);
    }
}
