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
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import net.dv8tion.jda.api.utils.MediaType;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.IOUtil;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class MultipartBody extends RequestBody implements ReferenceCounted, Closeable {
    private static final VarHandle REF_CNT_HANDLE;
    private static final ByteBuf CRLF_BUF =
            Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer("\r\n".getBytes(StandardCharsets.UTF_8)));
    private static final ByteBuf DASHDASH_BUF =
            Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer("--".getBytes(StandardCharsets.UTF_8)));

    static {
        try {
            REF_CNT_HANDLE = MethodHandles.lookup().findVarHandle(MultipartBody.class, "refCnt", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String boundary;
    private final ByteBuf boundaryBuf;
    private final List<Part> parts;
    private volatile int refCnt = 1;

    private MultipartBody(String boundary, List<Part> parts) {
        this.boundary = boundary;
        this.parts = List.copyOf(parts);
        this.boundaryBuf =
                Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(boundary.getBytes(StandardCharsets.UTF_8)));
    }

    private void checkOpen() {
        if (refCnt <= 0) {
            throw new IllegalStateException("MultipartBody has already been closed");
        }
    }

    @Nonnull
    @Override
    public MediaType contentType() {
        return MediaType.FORM;
    }

    @Nonnull
    @Override
    public String contentTypeHeader() {
        return HttpHeaderValues.MULTIPART_FORM_DATA + "; boundary=" + boundary;
    }

    @Nonnull
    public String getBoundary() {
        return boundary;
    }

    @Nonnull
    public List<Part> getParts() {
        return parts;
    }

    @Override
    public long contentLength() throws IOException {
        checkOpen();
        long total = 0;
        for (Part part : parts) {
            long bodyLength = part.body.contentLength();
            if (bodyLength < 0) {
                return -1;
            }
            total += part.headerBuf.readableBytes() + bodyLength + CRLF_BUF.readableBytes();
        }
        total += DASHDASH_BUF.readableBytes()
                + boundaryBuf.readableBytes()
                + DASHDASH_BUF.readableBytes()
                + CRLF_BUF.readableBytes();
        return total;
    }

    @Nonnull
    @Override
    public ByteBuf getByteBuf(@Nonnull ByteBufAllocator allocator) throws IOException {
        Checks.notNull(allocator, "ByteBufAllocator");
        checkOpen();
        int maxComponents = Math.max(16, parts.size() * 3 + 4);
        CompositeByteBuf composite = allocator.compositeBuffer(maxComponents);
        try {
            for (Part part : parts) {
                composite.addComponent(true, part.headerBuf.retainedDuplicate());
                composite.addComponent(true, part.body.getByteBuf(allocator));
                composite.addComponent(true, CRLF_BUF.retainedDuplicate());
            }
            composite.addComponent(true, DASHDASH_BUF.retainedDuplicate());
            composite.addComponent(true, boundaryBuf.retainedDuplicate());
            composite.addComponent(true, DASHDASH_BUF.retainedDuplicate());
            composite.addComponent(true, CRLF_BUF.retainedDuplicate());
            return composite;
        } catch (Throwable t) {
            composite.release();
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException(t);
        }
    }

    @Override
    public void writeTo(@Nonnull OutputStream out) throws IOException {
        Checks.notNull(out, "OutputStream");
        checkOpen();
        for (Part part : parts) {
            ByteBuf hdr = part.headerBuf;
            hdr.getBytes(hdr.readerIndex(), out, hdr.readableBytes());
            part.body.writeTo(out);
            CRLF_BUF.getBytes(CRLF_BUF.readerIndex(), out, CRLF_BUF.readableBytes());
        }
        DASHDASH_BUF.getBytes(DASHDASH_BUF.readerIndex(), out, DASHDASH_BUF.readableBytes());
        boundaryBuf.getBytes(boundaryBuf.readerIndex(), out, boundaryBuf.readableBytes());
        DASHDASH_BUF.getBytes(DASHDASH_BUF.readerIndex(), out, DASHDASH_BUF.readableBytes());
        CRLF_BUF.getBytes(CRLF_BUF.readerIndex(), out, CRLF_BUF.readableBytes());
    }

    @Override
    public void writeTo(@Nonnull ByteBuf buffer) throws IOException {
        Checks.notNull(buffer, "ByteBuf");
        checkOpen();
        for (Part part : parts) {
            buffer.writeBytes(part.headerBuf.duplicate());
            part.body.writeTo(buffer);
            buffer.writeBytes(CRLF_BUF.duplicate());
        }
        buffer.writeBytes(DASHDASH_BUF.duplicate());
        buffer.writeBytes(boundaryBuf.duplicate());
        buffer.writeBytes(DASHDASH_BUF.duplicate());
        buffer.writeBytes(CRLF_BUF.duplicate());
    }

    @Override
    public int refCnt() {
        return refCnt;
    }

    @Nonnull
    @Override
    public MultipartBody retain() {
        return retain(1);
    }

    @Nonnull
    @Override
    public MultipartBody retain(int increment) {
        Checks.check(increment > 0, "increment: %d (expected: > 0)", increment);
        while (true) {
            int current = refCnt;
            if (current <= 0) {
                throw new IllegalReferenceCountException(current, increment);
            }
            if (REF_CNT_HANDLE.compareAndSet(this, current, current + increment)) {
                return this;
            }
        }
    }

    @Nonnull
    @Override
    public MultipartBody touch() {
        return this;
    }

    @Nonnull
    @Override
    public MultipartBody touch(Object hint) {
        return this;
    }

    @Override
    public boolean release() {
        return release(1);
    }

    @Override
    public boolean release(int decrement) {
        Checks.check(decrement > 0, "decrement: %d (expected: > 0)", decrement);
        while (true) {
            int current = refCnt;
            if (current < decrement) {
                return true;
            }
            int next = current - decrement;
            if (REF_CNT_HANDLE.compareAndSet(this, current, next)) {
                if (next == 0) {
                    deallocate();
                    return true;
                }
                return false;
            }
        }
    }

    private void deallocate() {
        for (Part part : parts) {
            if (part.body instanceof ReferenceCounted refCounted) {
                ReferenceCountUtil.safeRelease(refCounted);
            } else if (part.body instanceof AutoCloseable closeable) {
                IOUtil.silentClose(closeable);
            }
        }
    }

    @Override
    public void close() {
        if (refCnt > 0) {
            release();
        }
    }

    public static class Part {
        final ByteBuf headerBuf;
        private final String name;
        private final String filename;
        private final RequestBody body;

        public Part(@Nonnull String name, @Nullable String filename, @Nonnull RequestBody body) {
            Checks.notNull(name, "Part name");
            Checks.notNull(body, "Part body");
            this.name = name;
            this.filename = filename;
            this.body = body;
            this.headerBuf = Unpooled.EMPTY_BUFFER;
        }

        Part(@Nonnull String name, @Nullable String filename, @Nonnull RequestBody body, @Nonnull String boundary) {
            Checks.notNull(name, "Part name");
            Checks.notNull(body, "Part body");
            this.name = name;
            this.filename = filename;
            this.body = body;
            this.headerBuf = buildHeaderBuf(name, filename, body.contentType(), boundary);
        }

        static ByteBuf buildHeaderBuf(
                @Nonnull String name,
                @Nullable String filename,
                @Nullable MediaType bodyType,
                @Nonnull String boundary) {
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append(HttpHeaderNames.CONTENT_DISPOSITION)
                    .append(": ")
                    .append(HttpHeaderValues.FORM_DATA)
                    .append("; name=\"")
                    .append(name)
                    .append('"');
            if (filename != null) {
                sb.append("; filename=\"").append(filename).append('"');
            }
            sb.append("\r\n");
            if (bodyType != null) {
                sb.append(HttpHeaderNames.CONTENT_TYPE)
                        .append(": ")
                        .append(bodyType.getValue())
                        .append("\r\n");
            }
            sb.append("\r\n");
            return Unpooled.unreleasableBuffer(
                    Unpooled.wrappedBuffer(sb.toString().getBytes(StandardCharsets.UTF_8)));
        }

        @Nonnull
        public String getName() {
            return name;
        }

        @Nullable
        public String getFilename() {
            return filename;
        }

        @Nonnull
        public RequestBody getBody() {
            return body;
        }
    }

    public static class Builder {
        private final String boundary;
        private final List<Part> parts = new ArrayList<>();

        public Builder() {
            this(UUID.randomUUID()
                    + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        }

        public Builder(@Nonnull String boundary) {
            Checks.notNull(boundary, "Boundary");
            this.boundary = boundary;
        }

        @Nonnull
        public Builder addFormDataPart(@Nonnull String name, @Nonnull String value) {
            Checks.notNull(value, "Value");
            return addFormDataPart(
                    name,
                    null,
                    new ByteBufRequestBody(Unpooled.wrappedBuffer(value.getBytes(StandardCharsets.UTF_8)), null));
        }

        @Nonnull
        public Builder addFormDataPart(@Nonnull String name, @Nullable String filename, @Nonnull RequestBody body) {
            parts.add(new Part(name, filename, body, boundary));
            return this;
        }

        @Nonnull
        public Builder addPart(@Nonnull Part part) {
            Checks.notNull(part, "Part");
            if (part.headerBuf.readableBytes() == 0) {
                parts.add(new Part(part.name, part.filename, part.body, boundary));
            } else {
                parts.add(part);
            }
            return this;
        }

        @Nonnull
        public MultipartBody build() {
            return new MultipartBody(boundary, parts);
        }
    }
}
