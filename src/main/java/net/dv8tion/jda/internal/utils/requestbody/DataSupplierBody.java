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
import net.dv8tion.jda.api.utils.MediaType;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.IOUtil;

import java.io.*;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class DataSupplierBody extends TypedBody<DataSupplierBody> {
    private final Supplier<? extends InputStream> streamSupply;

    public DataSupplierBody(@Nullable MediaType type, @Nonnull Supplier<? extends InputStream> streamSupply) {
        super(type);
        Checks.notNull(streamSupply, "Supplier");
        this.streamSupply = streamSupply;
    }

    @Nonnull
    @Override
    public DataSupplierBody withType(@Nonnull MediaType newType) {
        Checks.notNull(newType, "MediaType");
        if (newType.equals(this.type)) {
            return this;
        }
        return new DataSupplierBody(newType, streamSupply);
    }

    @Nonnull
    @Override
    public InputStream getInputStream() throws IOException {
        InputStream stream = streamSupply.get();
        if (stream == null) {
            throw new IOException("Stream supplier returned null");
        }
        return stream;
    }

    @Override
    public void writeTo(@Nonnull OutputStream out) throws IOException {
        Checks.notNull(out, "OutputStream");
        try (InputStream stream = this.getInputStream()) {
            stream.transferTo(out);
        }
    }

    @Override
    public void writeTo(@Nonnull ByteBuf target) throws IOException {
        Checks.notNull(target, "ByteBuf");
        try (InputStream stream = this.getInputStream()) {
            while (target.writeBytes(stream, 8192) > 0) {}
        }
    }

    @Nonnull
    @Override
    public ByteBuf getByteBuf(@Nonnull ByteBufAllocator allocator) throws IOException {
        Checks.notNull(allocator, "ByteBufAllocator");
        return IOUtil.readIntoByteBuf(this.getInputStream(), allocator);
    }

    @Nonnull
    @Override
    public byte[] toBytes() throws IOException {
        try (InputStream is = this.getInputStream()) {
            return is.readAllBytes();
        }
    }
}
