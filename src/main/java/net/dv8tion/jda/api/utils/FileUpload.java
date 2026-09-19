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

package net.dv8tion.jda.api.utils;

import io.netty.buffer.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.EntityString;
import net.dv8tion.jda.internal.utils.IOUtil;
import net.dv8tion.jda.internal.utils.requestbody.*;
import org.jetbrains.annotations.Contract;

import java.io.*;
import java.lang.ref.Cleaner;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Supplier;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents a file that is intended to be uploaded to Discord for arbitrary requests.
 * <br>This is used to upload data to discord for various purposes.
 *
 * <p><b>Resource Management:</b>
 * <br>This class implements {@link AutoCloseable}. It is recommended to use try-with-resources
 * or call {@link #close()} when finished with this upload to eagerly release underlying buffers
 * and close any open streams.
 * <br>If an instance is not explicitly closed, its resources will be automatically reclaimed
 * by the Garbage Collector once all references to it have been dropped.
 */
public class FileUpload implements AutoCloseable, AttachedFile {
    private static final Cleaner CLEANER = Cleaner.create();
    private final CleanupAction cleanup;
    private final Cleaner.Cleanable cleanable;
    private final InputStream resource;
    private final Supplier<? extends InputStream> resourceSupplier;
    private String name;
    private TypedBody<?> body;
    private String description;
    private MediaType mediaType = MediaType.OCTET;
    private byte[] waveform;
    private double durationSeconds;
    private boolean spoiler;
    private boolean singleUse = false;
    private volatile boolean closed = false;

    protected FileUpload(InputStream resource, String name) {
        this.resource = resource;
        this.resourceSupplier = null;
        this.name = name;
        this.spoiler = name != null && name.startsWith("SPOILER_");
        this.cleanup = new CleanupAction(resource);
        this.cleanable = CLEANER.register(this, cleanup);
    }

    protected FileUpload(Supplier<? extends InputStream> resourceSupplier, String name) {
        this.resourceSupplier = resourceSupplier;
        this.resource = null;
        this.name = name;
        this.spoiler = name != null && name.startsWith("SPOILER_");
        this.cleanup = new CleanupAction(null);
        this.cleanable = CLEANER.register(this, cleanup);
    }

    /**
     * Creates a FileUpload that sources its data from the supplier.
     * <br>The supplier <em>must</em> return a new stream on every call.
     *
     * <p>The streams are expected to always be at the beginning, when they are taken from the supplier.
     * If the supplier returned the same stream instance, the reader would start at the wrong position when re-attempting a request.
     *
     * <p>When this supplier factory is used, {@link #getData()} will return a new instance on each call.
     * It is the responsibility of the caller to close that stream.
     *
     * @param  name
     *         The file name
     * @param  supplier
     *         The resource supplier, which returns a new stream on each call
     *
     * @throws IllegalArgumentException
     *         If null is provided or the name is blank
     *
     * @return {@link FileUpload}
     */
    @Nonnull
    @CheckReturnValue
    public static FileUpload fromStreamSupplier(
            @Nonnull String name, @Nonnull Supplier<? extends InputStream> supplier) {
        Checks.notNull(supplier, "Supplier");
        Checks.notBlank(name, "Name");
        return new FileUpload(supplier, name);
    }

    /**
     * Create a new {@link FileUpload} for an input stream.
     * <br>This is used to upload data to discord for various purposes.
     *
     * <p>This class implements {@link Closeable}.
     * You can use {@link FileUpload#close()} to close the stream manually.
     *
     * @param  data
     *         The {@link InputStream} to upload
     * @param  name
     *         The representative name to use for the file
     *
     * @throws IllegalArgumentException
     *         If null is provided or the name is empty
     *
     * @return {@link FileUpload}
     *
     * @see    FileInputStream FileInputStream
     */
    @Nonnull
    @CheckReturnValue
    public static FileUpload fromData(@Nonnull InputStream data, @Nonnull String name) {
        Checks.notNull(data, "Data");
        Checks.notBlank(name, "Name");
        return new FileUpload(data, name);
    }

    /**
     * Create a new {@link FileUpload} for a byte array.
     * <br>This is used to upload data to discord for various purposes.
     *
     * @param  data
     *         The {@code byte[]} to upload
     * @param  name
     *         The representative name to use for the file
     *
     * @throws IllegalArgumentException
     *         If null is provided or the name is empty
     *
     * @return {@link FileUpload}
     */
    @Nonnull
    @CheckReturnValue
    public static FileUpload fromData(@Nonnull byte[] data, @Nonnull String name) {
        Checks.notNull(data, "Data");
        Checks.notNull(name, "Name");
        return fromData(Unpooled.wrappedBuffer(data), name);
    }

    /**
     * Create a new {@link FileUpload} for a Netty {@link ByteBuf}.
     * <br>This is used to upload data to discord with zero-copy buffer transfer.
     *
     * @param  data
     *         The {@link ByteBuf} to upload
     * @param  name
     *         The representative name to use for the file
     *
     * @throws IllegalArgumentException
     *         If null is provided or the name is empty
     *
     * @return {@link FileUpload}
     */
    @Nonnull
    @CheckReturnValue
    public static FileUpload fromData(@Nonnull ByteBuf data, @Nonnull String name) {
        Checks.notNull(data, "Data");
        Checks.notBlank(name, "Name");
        FileUpload upload = new FileUpload(new ByteBufInputStream(data.duplicate(), false), name);
        upload.body = new ByteBufRequestBody(data, MediaType.OCTET);
        upload.cleanup.setBody(upload.body);
        return upload;
    }

    /**
     * Create a new {@link FileUpload} for a local file.
     * <br>This is used to upload data to discord for various purposes.
     *
     * <p>This opens a {@link FileInputStream}, which will be closed on consumption by the request.
     * You can use {@link FileUpload#close()} to close the stream manually.
     *
     * @param  file
     *         The {@link File} to upload
     * @param  name
     *         The representative name to use for the file
     *
     * @throws IllegalArgumentException
     *         If null is provided or the name is empty
     * @throws UncheckedIOException
     *         If an IOException is thrown while opening the file
     *
     * @return {@link FileUpload}
     *
     * @see    FileInputStream FileInputStream
     */
    @Nonnull
    @CheckReturnValue
    public static FileUpload fromData(@Nonnull File file, @Nonnull String name) {
        Checks.notNull(file, "File");
        Checks.notBlank(name, "Name");
        return fromData(file.toPath(), name);
    }

    /**
     * Create a new {@link FileUpload} for a local file.
     * <br>This is used to upload data to discord for various purposes.
     *
     * <p>This will use the {@link File#getName() file name} as the file upload name.
     *
     * @param  file
     *         The {@link File} to upload
     *
     * @throws IllegalArgumentException
     *         If null is provided
     * @throws UncheckedIOException
     *         If an IOException is thrown while opening the file
     *
     * @return {@link FileUpload}
     *
     * @see    #fromData(File, String)
     */
    @Nonnull
    @CheckReturnValue
    public static FileUpload fromData(@Nonnull File file) {
        Checks.notNull(file, "File");
        return fromData(file.toPath(), file.getName());
    }

    /**
     * Create a new {@link FileUpload} for a local file.
     * <br>This is used to upload data to discord for various purposes.
     *
     * @param  path
     *         The {@link Path} of the file to upload
     * @param  name
     *         The representative name to use for the file
     * @param  options
     *         Options specifying how the file is opened
     *
     * @throws IllegalArgumentException
     *         If null is provided or the name is empty
     * @throws UncheckedIOException
     *         If an IOException occurs while opening the file
     *
     * @return {@link FileUpload}
     */
    @Nonnull
    @CheckReturnValue
    public static FileUpload fromData(@Nonnull Path path, @Nonnull String name, @Nonnull OpenOption... options) {
        Checks.notNull(path, "Path");
        Checks.notBlank(name, "Name");
        Checks.notNull(options, "OpenOptions");
        try {
            ByteBuf buffer = IOUtil.readIntoByteBuf(path, NettyConfig.getGlobalAllocator(), options);
            return fromData(buffer, name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Create a new {@link FileUpload} for a local file.
     * <br>This is used to upload data to discord for various purposes.
     *
     * <p>This will use the {@link Path#getFileName() file name} as the file upload name.
     *
     * @param  path
     *         The {@link Path} of the file to upload
     * @param  options
     *         Options specifying how the file is opened
     *
     * @throws IllegalArgumentException
     *         If null is provided
     * @throws UncheckedIOException
     *         If an IOException occurs while opening the file
     *
     * @return {@link FileUpload}
     *
     * @see    #fromData(Path, String, OpenOption...)
     */
    @Nonnull
    @CheckReturnValue
    public static FileUpload fromData(@Nonnull Path path, @Nonnull OpenOption... options) {
        Checks.notNull(path, "Path");
        Path fileName = path.getFileName();
        Checks.notNull(fileName, "Path.getFileName");
        return fromData(path, fileName.toString(), options);
    }

    /**
     * Mark this file as a spoiler.
     * <br>This will cause the file to be rendered as a spoiler attachment in the client.
     *
     * @return The updated FileUpload instance
     */
    @Nonnull
    @Contract(" -> this")
    public FileUpload asSpoiler() {
        return asSpoiler(true);
    }

    /**
     * Set whether this file should be marked as a spoiler.
     * <br>If {@code true}, this will cause the file to be rendered as a spoiler attachment in the client.
     *
     * @return The updated FileUpload instance
     */
    @Nonnull
    @Contract("_->this")
    public FileUpload asSpoiler(boolean isSpoiler) {
        if (this.spoiler == isSpoiler) {
            return this;
        }
        this.spoiler = isSpoiler;
        return this;
    }

    /**
     * Mark this file upload as single-use, causing it to be closed automatically after being consumed by a request.
     * <br>By default, file uploads are <b>not</b> closed automatically after use and can be reused across multiple requests.
     *
     * @return The updated FileUpload instance
     */
    @Nonnull
    @Contract(" -> this")
    public FileUpload asSingleUse() {
        return asSingleUse(true);
    }

    /**
     * Set whether this file upload should be closed automatically after being consumed by a request.
     * <br>By default, this is {@code false} and file uploads can be reused across multiple requests.
     *
     * @param  singleUse
     *         {@code true} if this file upload should close automatically after use
     *
     * @return The updated FileUpload instance
     */
    @Nonnull
    @Contract("_->this")
    public FileUpload asSingleUse(boolean singleUse) {
        this.singleUse = singleUse;
        return this;
    }

    /**
     * Whether this file upload is configured to be closed automatically after use.
     *
     * @return True, if this file upload is single-use
     */
    public boolean isSingleUse() {
        return singleUse;
    }

    /**
     * Mark this file upload to be closed automatically after being consumed by a request.
     *
     * @return The updated FileUpload instance
     *
     * @see    #asSingleUse()
     */
    @Nonnull
    @Contract(" -> this")
    public FileUpload closeOnUse() {
        return asSingleUse(true);
    }

    /**
     * Whether this file upload is configured to be closed automatically after use.
     *
     * @return True, if this file upload closes on use
     * @see #isSingleUse()
     */
    public boolean isCloseOnUse() {
        return singleUse;
    }

    /**
     * Set whether this file upload should be closed automatically after being consumed by a request.
     *
     * @param  closeOnUse
     *         {@code true} if this file upload should close automatically after use
     *
     * @return The updated FileUpload instance
     *
     * @see    #asSingleUse(boolean)
     */
    @Nonnull
    @Contract("_->this")
    public FileUpload setCloseOnUse(boolean closeOnUse) {
        return asSingleUse(closeOnUse);
    }

    /**
     * Whether this file upload has been closed.
     *
     * @return True, if this file upload has already been closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Turns this attachment into a voice message with the provided waveform.
     *
     * @param  mediaType
     *         The audio type for the attached audio file. Should be {@code audio/ogg} or similar.
     * @param  waveform
     *         The waveform of the audio, which is a low frequency sampling up to 256 bytes.
     * @param  duration
     *         The actual duration of the audio data.
     *
     * @throws IllegalArgumentException
     *         If null is provided or the waveform is not between 1 and 256 bytes long.
     *
     * @return The same FileUpload instance configured as a voice message attachment
     */
    @Nonnull
    @Contract("_,_,_->this")
    public FileUpload asVoiceMessage(
            @Nonnull MediaType mediaType, @Nonnull byte[] waveform, @Nonnull Duration duration) {
        Checks.notNull(duration, "Duration");
        return this.asVoiceMessage(mediaType, waveform, duration.toNanos() / 1_000_000_000.0);
    }

    /**
     * Turns this attachment into a voice message with the provided waveform.
     *
     * @param  mediaType
     *         The audio type for the attached audio file. Should be {@code audio/ogg} or similar.
     * @param  waveform
     *         The waveform of the audio, which is a low frequency sampling up to 256 bytes.
     * @param  durationSeconds
     *         The actual duration of the audio data in seconds.
     *
     * @throws IllegalArgumentException
     *         If null is provided or the waveform is not between 1 and 256 bytes long.
     *
     * @return The same FileUpload instance configured as a voice message attachment
     */
    @Nonnull
    @Contract("_,_,_->this")
    public FileUpload asVoiceMessage(@Nonnull MediaType mediaType, @Nonnull byte[] waveform, double durationSeconds) {
        Checks.notNull(mediaType, "Media type");
        Checks.notNull(waveform, "Waveform");
        Checks.check(waveform.length > 0 && waveform.length <= 256, "Waveform must be between 1 and 256 bytes long");
        Checks.check(Double.isFinite(durationSeconds), "Duration must be a finite number");
        Checks.check(durationSeconds > 0, "Duration must be positive");
        this.waveform = waveform;
        this.durationSeconds = durationSeconds;
        this.mediaType = mediaType;
        return this;
    }

    /**
     * Whether this attachment is a valid voice message attachment.
     *
     * @return True, if this is a voice message attachment.
     */
    public boolean isVoiceMessage() {
        return this.mediaType.type().equals("audio")
                && this.durationSeconds > 0.0
                && this.waveform != null
                && this.waveform.length > 0;
    }

    /**
     * The filename for the file.
     *
     * @return The filename
     */
    @Nonnull
    public String getName() {
        return name;
    }

    /**
     * Changes the name of this file.
     *
     * @param  name
     *         The new filename
     *
     * @throws IllegalArgumentException
     *         If the name is null, blank, or empty
     *
     * @return The updated FileUpload instance
     */
    @Nonnull
    @Contract("_->this")
    public FileUpload setName(@Nonnull String name) {
        Checks.notBlank(name, "Name");
        this.name = name;
        return this;
    }

    /**
     * The description for the file.
     *
     * @return The description
     */
    @Nullable
    public String getDescription() {
        return description;
    }

    /**
     * Set the file description used as ALT text for screenreaders.
     *
     * @param  description
     *         The alt text describing this file attachment (up to {@value MAX_DESCRIPTION_LENGTH} characters)
     *
     * @throws IllegalArgumentException
     *         If the description is longer than {@value MAX_DESCRIPTION_LENGTH} characters
     *
     * @return The same FileUpload instance with the new description
     */
    @Nonnull
    @Contract("_->this")
    public FileUpload setDescription(@Nullable String description) {
        if (description != null) {
            Checks.notLonger(description = description.trim(), MAX_DESCRIPTION_LENGTH, "Description");
        }
        this.description = description;
        return this;
    }

    /**
     * The {@link InputStream} representing the data to upload as a file.
     *
     * @return The {@link InputStream}
     */
    @Nonnull
    public InputStream getData() {
        if (resource != null) {
            return resource;
        } else {
            return resourceSupplier.get();
        }
    }

    /**
     * Creates a re-usable instance of {@link RequestBody} with the specified content-type.
     *
     * <p>This body will automatically close the {@link #getData() resource} when the request is done.
     * However, since the body buffers the data, it can be used multiple times regardless.
     *
     * @param  type
     *         The content-type to use for the body (e.g. {@code "application/octet-stream"})
     *
     * @throws IllegalArgumentException
     *         If the content-type is null
     *
     * @return {@link RequestBody}
     */
    @Nonnull
    public synchronized RequestBody getRequestBody(@Nonnull MediaType type) {
        Checks.notNull(type, "Type");
        if (closed) {
            throw new IllegalStateException("FileUpload has already been closed");
        }
        if (body != null) { // This allows FileUpload to be used more than once!
            return body.withType(type);
        }
        if (resource == null) {
            body = new DataSupplierBody(type, resourceSupplier);
        } else {
            body = IOUtil.createRequestBody(type, resource, UnpooledByteBufAllocator.DEFAULT);
        }
        cleanup.setBody(body);
        return body;
    }

    public synchronized void addPart(
            @Nonnull MultipartBody.Builder builder, @Nonnull String partName, @Nonnull MediaType type) {
        Checks.notNull(builder, "Builder");
        Checks.notNull(partName, "Part name");
        Checks.notNull(type, "Type");
        RequestBody requestBody = getRequestBody(type);
        if (requestBody instanceof ReferenceCounted refCounted) {
            refCounted.retain();
        }
        if (singleUse) {
            builder.addFormDataPart(partName, name, new SingleUseRequestBody(requestBody, this));
        } else {
            builder.addFormDataPart(partName, name, requestBody);
        }
    }

    @Override
    public synchronized void addPart(@Nonnull MultipartBody.Builder builder, int index) {
        addPart(builder, "files[" + index + "]", mediaType);
    }

    @Nonnull
    @Override
    public DataObject toAttachmentData(int index) {
        DataObject attachment = DataObject.empty()
                .put("id", index)
                .put("description", description == null ? "" : description)
                .put("content_type", mediaType.toString())
                .put("is_spoiler", spoiler)
                .put("filename", name);
        if (waveform != null && durationSeconds > 0) {
            attachment.put("waveform", Base64.getEncoder().encodeToString(waveform));
            attachment.put("duration_secs", durationSeconds);
        }
        return attachment;
    }

    @Override
    public synchronized void close() {
        try {
            forceClose();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void forceClose() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (resource != null) {
                resource.close();
            }
        } finally {
            this.body = null;
            cleanable.clean();
        }
    }

    @Override
    public String toString() {
        return new EntityString("AttachedFile").setType("Data").setName(name).toString();
    }

    private static class CleanupAction implements Runnable {
        private final InputStream resource;
        private volatile TypedBody<?> body;

        CleanupAction(InputStream resource) {
            this.resource = resource;
        }

        void setBody(TypedBody<?> body) {
            this.body = body;
        }

        @Override
        public void run() {
            if (resource != null) {
                IOUtil.silentClose(resource);
            }
            TypedBody<?> b = this.body;
            this.body = null;
            if (b instanceof ReferenceCounted refCounted) {
                ReferenceCountUtil.safeRelease(refCounted);
            } else if (b instanceof AutoCloseable closeable) {
                IOUtil.silentClose(closeable);
            }
        }
    }

    private static class SingleUseRequestBody extends RequestBody implements ReferenceCounted, AutoCloseable {
        private final RequestBody delegate;
        private final FileUpload upload;

        SingleUseRequestBody(RequestBody delegate, FileUpload upload) {
            this.delegate = delegate;
            this.upload = upload;
        }

        @Nullable
        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Nullable
        @Override
        public String contentTypeHeader() {
            return delegate.contentTypeHeader();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public void writeTo(@Nonnull OutputStream out) throws IOException {
            delegate.writeTo(out);
        }

        @Override
        public void writeTo(@Nonnull ByteBuf out) throws IOException {
            delegate.writeTo(out);
        }

        @Nonnull
        @Override
        public ByteBuf getByteBuf(@Nonnull ByteBufAllocator allocator) throws IOException {
            return delegate.getByteBuf(allocator);
        }

        @Nonnull
        @Override
        public byte[] toBytes() throws IOException {
            return delegate.toBytes();
        }

        @Nonnull
        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public int refCnt() {
            if (delegate instanceof ReferenceCounted refCounted) {
                return refCounted.refCnt();
            }
            return 1;
        }

        @Nonnull
        @Override
        public ReferenceCounted retain() {
            if (delegate instanceof ReferenceCounted refCounted) {
                refCounted.retain();
            }
            return this;
        }

        @Nonnull
        @Override
        public ReferenceCounted retain(int increment) {
            if (delegate instanceof ReferenceCounted refCounted) {
                refCounted.retain(increment);
            }
            return this;
        }

        @Nonnull
        @Override
        public ReferenceCounted touch() {
            if (delegate instanceof ReferenceCounted refCounted) {
                refCounted.touch();
            }
            return this;
        }

        @Nonnull
        @Override
        public ReferenceCounted touch(@Nullable Object hint) {
            if (delegate instanceof ReferenceCounted refCounted) {
                refCounted.touch(hint);
            }
            return this;
        }

        @Override
        public boolean release() {
            return release(1);
        }

        @Override
        public boolean release(int decrement) {
            try {
                if (delegate instanceof ReferenceCounted refCounted) {
                    return refCounted.release(decrement);
                }
                return true;
            } finally {
                upload.close();
            }
        }

        @Override
        public void close() {
            try {
                if (delegate instanceof AutoCloseable closeable) {
                    IOUtil.silentClose(closeable);
                }
            } finally {
                upload.close();
            }
        }
    }
}
