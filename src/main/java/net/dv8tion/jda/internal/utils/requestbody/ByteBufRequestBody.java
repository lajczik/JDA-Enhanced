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
import io.netty.buffer.ByteBufUtil;
import io.netty.util.ReferenceCounted;
import net.dv8tion.jda.api.utils.MediaType;
import net.dv8tion.jda.internal.utils.Checks;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ByteBufRequestBody extends TypedBody<ByteBufRequestBody> implements ReferenceCounted, Closeable {
    private final ByteBuf buffer;

    public ByteBufRequestBody(@Nonnull ByteBuf buffer, @Nullable MediaType contentType) {
        super(contentType);
        Checks.notNull(buffer, "ByteBuf");
        this.buffer = buffer;
    }

    private ByteBuf checkOpen() {
        if (this.buffer.refCnt() <= 0) {
            throw new IllegalStateException("ByteBufRequestBody has already been closed");
        }
        return this.buffer;
    }

    @Nonnull
    @Override
    public ByteBufRequestBody withType(@Nonnull MediaType newType) {
        Checks.notNull(newType, "MediaType");
        if (newType.equals(this.type)) {
            return this;
        }
        return new ByteBufRequestBody(checkOpen().retainedSlice(), newType);
    }

    @Override
    public long contentLength() {
        return checkOpen().readableBytes();
    }

    @Override
    public void writeTo(@Nonnull OutputStream out) throws IOException {
        Checks.notNull(out, "OutputStream");
        ByteBuf buf = checkOpen();
        buf.getBytes(buf.readerIndex(), out, buf.readableBytes());
    }

    @Override
    public void writeTo(@Nonnull ByteBuf target) {
        Checks.notNull(target, "ByteBuf");
        target.writeBytes(checkOpen().slice());
    }

    @Nonnull
    @Override
    public ByteBuf getByteBuf(@Nonnull ByteBufAllocator allocator) {
        return checkOpen().retainedSlice();
    }

    @Nonnull
    @Override
    public byte[] toBytes() {
        return ByteBufUtil.getBytes(checkOpen());
    }

    @Nonnull
    @Override
    public InputStream getInputStream() {
        return new ByteBufInputStream(checkOpen().duplicate(), false);
    }

    @Override
    public int refCnt() {
        return buffer.refCnt();
    }

    @Nonnull
    @Override
    public ByteBufRequestBody retain() {
        buffer.retain();
        return this;
    }

    @Nonnull
    @Override
    public ByteBufRequestBody retain(int increment) {
        buffer.retain(increment);
        return this;
    }

    @Nonnull
    @Override
    public ByteBufRequestBody touch() {
        buffer.touch();
        return this;
    }

    @Nonnull
    @Override
    public ByteBufRequestBody touch(Object hint) {
        buffer.touch(hint);
        return this;
    }

    @Override
    public boolean release() {
        if (buffer.refCnt() > 0) {
            return buffer.release();
        }
        return true;
    }

    @Override
    public boolean release(int decrement) {
        if (buffer.refCnt() >= decrement) {
            return buffer.release(decrement);
        }
        return true;
    }

    @Override
    public void close() {
        if (buffer.refCnt() > 0) {
            buffer.release();
        }
    }
}
