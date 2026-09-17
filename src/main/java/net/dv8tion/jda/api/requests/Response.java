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

package net.dv8tion.jda.api.requests;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.ReferenceCountUtil;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.IOFunction;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.requests.Requester;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import net.dv8tion.jda.internal.utils.EntityString;
import net.dv8tion.jda.internal.utils.IOUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Internal class used to represent HTTP responses or request failures.
 *
 * <p>
 * Response bodies are stored as a {@link ByteBuf} for zero-copy access.
 * Reference counting: the buffer is retained by this Response and released in
 * {@link #close()}.
 */
public class Response implements Closeable {
    public static final int ERROR_CODE = -1;
    public static final String ERROR_MESSAGE = "ERROR";
    public static final IOFunction<BufferedReader, DataObject> JSON_SERIALIZE_OBJECT = DataObject::fromJson;
    public static final IOFunction<BufferedReader, DataArray> JSON_SERIALIZE_ARRAY = DataArray::fromJson;

    public final int code;
    public final String message;
    public final long retryAfter;
    /**
     * The response body, retained by this Response. Released in {@link #close()}.
     * May be null for error responses.
     */
    private ByteBuf byteBuf;

    private final HttpHeaders headers;
    private final String url;
    private final Set<String> cfRays;
    private String fallbackString;
    private Object object;
    private boolean attemptedParsing = false;
    private Exception exception;

    public Response(@Nonnull Exception exception, @Nonnull Set<String> cfRays) {
        this(ERROR_CODE, ERROR_MESSAGE, -1, (ByteBuf) null, null, null, cfRays);
        this.exception = exception;
    }

    public Response(long retryAfter, @Nonnull Set<String> cfRays) {
        this(429, "TOO MANY REQUESTS", retryAfter, (ByteBuf) null, null, null, cfRays);
    }

    /**
     * Primary constructor used by
     * {@link Requester}.
     * The provided {@code body} buffer is retained by this Response.
     */
    public Response(
            int code,
            @Nonnull String message,
            long retryAfter,
            @Nullable ByteBuf body,
            @Nullable HttpHeaders headers,
            @Nullable String url,
            @Nonnull Set<String> cfRays) {
        this.code = code;
        this.message = message;
        this.retryAfter = retryAfter;
        this.byteBuf = body;
        this.headers = headers;
        this.url = url;
        this.cfRays = cfRays;
        this.exception = null;
    }

    /**
     * @deprecated The internal HTTP client always provides a {@link ByteBuf}.
     *             This overload is kept for binary compatibility with external
     *             code.
     */
    @Deprecated
    public Response(
            int code,
            @Nonnull String message,
            long retryAfter,
            @Nullable InputStream body,
            @Nullable HttpHeaders headers,
            @Nullable String url,
            @Nonnull Set<String> cfRays) {
        this.code = code;
        this.message = message;
        this.retryAfter = retryAfter;
        this.headers = headers;
        this.url = url;
        this.cfRays = cfRays;
        this.exception = null;
        if (body != null) {
            ByteBuf buf;
            try {
                byte[] bytes = body.readAllBytes();
                buf = Unpooled.wrappedBuffer(bytes);
            } catch (IOException e) {
                buf = Unpooled.EMPTY_BUFFER;
            } finally {
                IOUtil.silentClose(body);
            }
            this.byteBuf = buf;
        } else {
            this.byteBuf = null;
        }
    }

    @Nonnull
    public DataArray getArray() {
        if (attemptedParsing) {
            if (object instanceof DataArray) {
                return (DataArray) object;
            }
            throw new IllegalStateException("Attempted to parse body as DataArray, but was previously parsed as "
                    + (object != null ? object.getClass().getSimpleName() : "null"));
        }
        if (byteBuf != null && byteBuf.isReadable()) {
            attemptedParsing = true;
            try {
                DataArray array = DataArray.fromJson(byteBuf.slice());
                this.object = array;
                RestActionImpl.LOG.trace(
                        "Parsed response body for response on url {}\n{}", url != null ? url : "unknown", this.object);
                return array;
            } catch (Exception e) {
                try {
                    this.fallbackString = byteBuf.toString(StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw new IllegalStateException("An error occurred while parsing the response for a RestAction", e);
            }
        }
        throw new IllegalStateException("Response has no body to parse as DataArray");
    }

    @Nonnull
    public Optional<DataArray> optArray() {
        if (attemptedParsing) {
            if (object instanceof DataArray) {
                return Optional.of((DataArray) object);
            }
            return Optional.empty();
        }
        if (byteBuf != null && byteBuf.isReadable()) {
            try {
                return Optional.of(getArray());
            } catch (Exception e) {
                if (e.getCause() instanceof ParsingException || e instanceof ParsingException) {
                    return Optional.empty();
                }
                throw e;
            }
        }
        return Optional.empty();
    }

    @Nonnull
    public DataObject getObject() {
        if (attemptedParsing) {
            if (object instanceof DataObject) {
                return (DataObject) object;
            }
            throw new IllegalStateException("Attempted to parse body as DataObject, but was previously parsed as "
                    + (object != null ? object.getClass().getSimpleName() : "null"));
        }
        if (byteBuf != null && byteBuf.isReadable()) {
            attemptedParsing = true;
            try {
                DataObject obj = DataObject.fromJson(byteBuf.slice());
                this.object = obj;
                RestActionImpl.LOG.trace(
                        "Parsed response body for response on url {}\n{}", url != null ? url : "unknown", this.object);
                return obj;
            } catch (Exception e) {
                try {
                    this.fallbackString = byteBuf.toString(StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                throw new IllegalStateException("An error occurred while parsing the response for a RestAction", e);
            }
        }
        throw new IllegalStateException("Response has no body to parse as DataObject");
    }

    @Nonnull
    public Optional<DataObject> optObject() {
        if (attemptedParsing) {
            if (object instanceof DataObject) {
                return Optional.of((DataObject) object);
            }
            return Optional.empty();
        }
        if (byteBuf != null && byteBuf.isReadable()) {
            try {
                return Optional.of(getObject());
            } catch (Exception e) {
                if (e.getCause() instanceof ParsingException || e instanceof ParsingException) {
                    return Optional.empty();
                }
                throw e;
            }
        }
        return Optional.empty();
    }

    @Nonnull
    public String getString() {
        if (fallbackString != null) {
            return fallbackString;
        }
        if (byteBuf != null && byteBuf.refCnt() > 0) {
            fallbackString = byteBuf.toString(StandardCharsets.UTF_8);
            return fallbackString;
        }
        return "N/A";
    }

    @Nonnull
    public <T> T get(@Nonnull Class<T> clazz, @Nonnull IOFunction<BufferedReader, T> parser) {
        return parseBody(clazz, parser).orElseThrow(IllegalStateException::new);
    }

    @Nullable
    public HttpHeaders getHeaders() {
        return this.headers;
    }

    @Nullable
    public String getHeader(@Nonnull CharSequence name) {
        return this.headers != null ? this.headers.get(name) : null;
    }

    @Nullable
    public String getUrl() {
        return this.url;
    }

    /**
     * Returns the raw response body as a {@link ByteBuf}.
     * The returned buffer is owned by this Response and will be released in
     * {@link #close()}.
     * Do <b>not</b> release it externally; call {@link ByteBuf#slice()} or
     * {@link ByteBuf#duplicate()} if needed.
     */
    @Nullable
    public ByteBuf getByteBuf() {
        return this.byteBuf;
    }

    /**
     * Returns the response body as an {@link InputStream} for external API
     * consumers.
     * The caller is responsible for closing the returned stream.
     *
     * <p>
     * Internally, prefer using {@link #getByteBuf()} directly.
     */
    @Nullable
    public InputStream getBody() {
        if (byteBuf != null && byteBuf.refCnt() > 0) {
            return new ByteBufInputStream(byteBuf.slice(), false);
        }
        return null;
    }

    @Nonnull
    public Set<String> getCFRays() {
        return cfRays != null ? cfRays : Set.of();
    }

    @Nullable
    public Exception getException() {
        return exception;
    }

    public boolean isError() {
        return this.code == Response.ERROR_CODE;
    }

    public boolean isOk() {
        return this.code > 199 && this.code < 300;
    }

    public boolean isRateLimit() {
        return this.code == 429;
    }

    @Override
    public String toString() {
        EntityString entityString = new EntityString(exception == null ? "HTTPResponse" : "HTTPException");
        if (exception == null) {
            entityString.addMetadata("code", code);
            if (object != null) {
                entityString.addMetadata("object", object.toString());
            }
        } else {
            entityString.addMetadata("exceptionMessage", exception.getMessage());
        }

        return entityString.toString();
    }

    /**
     * Releases the retained {@link ByteBuf}. Must be called exactly once after this
     * Response is done.
     */
    @Override
    public void close() {
        if (byteBuf != null) {
            if (byteBuf.refCnt() > 0) {
                ReferenceCountUtil.safeRelease(byteBuf);
            }
            byteBuf = null;
        }
    }

    private String readString(BufferedReader reader) {
        return reader.lines().collect(Collectors.joining("\n"));
    }

    private <T> Optional<T> parseBody(Class<T> clazz, IOFunction<BufferedReader, T> parser) {
        return parseBody(false, clazz, parser);
    }

    @SuppressWarnings("ConstantConditions")
    private <T> Optional<T> parseBody(boolean opt, Class<T> clazz, IOFunction<BufferedReader, T> parser) {
        if (attemptedParsing) {
            if (object != null && clazz.isAssignableFrom(object.getClass())) {
                return Optional.of(clazz.cast(object));
            }
            return Optional.empty();
        }

        attemptedParsing = true;
        if (byteBuf == null || !byteBuf.isReadable()) {
            return Optional.empty();
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new ByteBufInputStream(byteBuf.slice(), false), StandardCharsets.UTF_8));
            reader.mark(1024);
            T t = parser.apply(reader);
            this.object = t;
            RestActionImpl.LOG.trace(
                    "Parsed response body for response on url {}\n{}", url != null ? url : "unknown", this.object);
            return Optional.ofNullable(t);
        } catch (Exception e) {
            try {
                reader.reset();
                this.fallbackString = readString(reader);
                reader.close();
            } catch (NullPointerException | IOException ignored) {
            }
            if (opt && e instanceof ParsingException) {
                return Optional.empty();
            } else {
                throw new IllegalStateException("An error occurred while parsing the response for a RestAction", e);
            }
        }
    }
}
