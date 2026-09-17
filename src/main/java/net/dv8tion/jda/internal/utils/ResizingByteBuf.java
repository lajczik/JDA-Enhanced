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
import io.netty.buffer.Unpooled;
import net.dv8tion.jda.api.utils.NettyConfig;

import java.nio.ByteBuffer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ResizingByteBuf implements AutoCloseable {
    private final ByteBufAllocator allocator;
    private volatile ByteBuf buffer;

    public ResizingByteBuf(int initialCapacity) {
        this(null, initialCapacity);
    }

    public ResizingByteBuf(@Nullable ByteBufAllocator allocator, int initialCapacity) {
        this.allocator = allocator != null ? allocator : NettyConfig.getGlobalAllocator();
        this.buffer = this.allocator.directBuffer(initialCapacity);
    }

    public ResizingByteBuf(@Nonnull ByteBuf buffer) {
        this(null, buffer);
    }

    public ResizingByteBuf(@Nullable ByteBufAllocator allocator, @Nonnull ByteBuf buffer) {
        this.allocator = allocator != null
                ? allocator
                : (buffer.alloc() != null ? buffer.alloc() : NettyConfig.getGlobalAllocator());
        this.buffer = buffer;
    }

    @Nonnull
    public ByteBufAllocator getAllocator() {
        return allocator;
    }

    @Nonnull
    public ByteBuf buffer() {
        ByteBuf buf = this.buffer;
        if (buf == null) {
            throw new IllegalStateException("ResizingByteBuf has been released");
        }
        return buf;
    }

    @Nonnull
    public ByteBuffer nioBuffer() {
        return buffer().nioBuffer();
    }

    @Nonnull
    public ByteBuffer nioBuffer(int index, int length) {
        return buffer().nioBuffer(index, length);
    }

    @Nonnull
    public ResizingByteBuf prepareWrite(int capacity) {
        ByteBuf buf = this.buffer;
        if (buf == null || buf.capacity() < capacity) {
            ByteBuf newBuf = allocator.directBuffer(capacity);
            release();
            this.buffer = newBuf;
        } else {
            buf.clear();
        }
        return this;
    }

    @Nonnull
    public ResizingByteBuf ensureWritable(int capacity) {
        ByteBuf buf = this.buffer;
        if (buf == null) {
            this.buffer = allocator.directBuffer(capacity);
        } else if (buf.writableBytes() < capacity) {
            buf.ensureWritable(capacity);
        }
        return this;
    }

    @Nonnull
    @SuppressWarnings("ReferenceEquality")
    public ResizingByteBuf replace(@Nonnull ByteBuf data) {
        if (this.buffer != data) {
            ByteBuf slice = data.retainedSlice();
            release();
            this.buffer = slice;
        }
        return this;
    }

    @Nonnull
    public ResizingByteBuf replace(@Nonnull byte[] data) {
        ByteBuf wrapped = Unpooled.wrappedBuffer(data);
        release();
        this.buffer = wrapped;
        return this;
    }

    @Nonnull
    public ResizingByteBuf replace(@Nonnull byte[] data, int offset, int length) {
        ByteBuf wrapped = Unpooled.wrappedBuffer(data, offset, length);
        release();
        this.buffer = wrapped;
        return this;
    }

    @Nonnull
    public ResizingByteBuf replace(@Nonnull ByteBuffer data) {
        ByteBuf wrapped = Unpooled.wrappedBuffer(data);
        release();
        this.buffer = wrapped;
        return this;
    }

    @Nonnull
    public ResizingByteBuf clear() {
        ByteBuf buf = this.buffer;
        if (buf != null) {
            buf.clear();
        }
        return this;
    }

    public void release() {
        ByteBuf buf = this.buffer;
        if (buf != null) {
            this.buffer = null;
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    @Override
    public void close() {
        release();
    }
}
