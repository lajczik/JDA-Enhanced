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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.exceptions.HttpException;
import net.dv8tion.jda.api.requests.RestConfig;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.FutureUtil;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A utility class to download files.
 */
public class FileProxy {
    private static volatile HttpClient defaultHttpClient;
    private static volatile Scheduler defaultScheduler;

    private final String url;
    private HttpClient customHttpClient;
    private Scheduler customScheduler;

    /**
     * Constructs a new {@link FileProxy} for the provided URL and associated JDA
     * instance.
     *
     * @param url
     *            The URL to download from
     *
     * @throws IllegalArgumentException
     *                                  If the provided URL is null
     */
    public FileProxy(@Nonnull String url) {
        Checks.notNull(url, "URL");
        this.url = url;
    }

    /**
     * Sets the default OkHttpClient used by {@link FileProxy} and {@link ImageProxy}.
     * <br>This can still be overridden on a per-instance basis with {@link #withClient(HttpClient)}.
     *
     * @param  httpClient
     *         The default {@link HttpClient} to use while making HTTP requests
     *
     * @throws IllegalArgumentException
     *         If the provided {@link HttpClient} is null
     */
    public static void setDefaultHttpClient(@Nonnull HttpClient httpClient) {
        Checks.notNull(httpClient, "Default OkHttpClient");
        FileProxy.defaultHttpClient = httpClient;
    }

    /**
     * Resets the default {@link HttpClient} used by {@link FileProxy} and {@link ImageProxy}
     * if it matches the expected client, or unconditionally if {@code expected} is null.
     *
     * @param expected
     *        The expected {@link HttpClient} to reset, or {@code null} to reset unconditionally
     */
    public static void resetDefaultHttpClient(@Nullable HttpClient expected) {
        if (expected == null || Objects.equals(defaultHttpClient, expected)) {
            defaultHttpClient = null;
        }
    }

    /**
     * Resets the default {@link Scheduler} used by {@link FileProxy} and {@link ImageProxy}
     * if it matches the expected scheduler, or unconditionally if {@code expected} is null.
     *
     * @param expected
     *        The expected {@link Scheduler} to reset, or {@code null} to reset unconditionally
     */
    public static void resetDefaultScheduler(@Nullable Scheduler expected) {
        if (expected == null || Objects.equals(defaultScheduler, expected)) {
            defaultScheduler = null;
        }
    }

    /**
     * Returns the default {@link Scheduler} currently set, or {@code null} if none is set.
     *
     * @return The default scheduler, or null
     */
    @Nullable
    public static Scheduler getDefaultScheduler() {
        return defaultScheduler;
    }

    /**
     * Sets the default {@link Scheduler} used by {@link FileProxy} and {@link ImageProxy}.
     * <br>This can still be overridden on a per-instance basis with {@link #withScheduler(Scheduler)}.
     *
     * @param scheduler The default {@link Scheduler} to use for reactive operations
     * @throws IllegalArgumentException If the provided {@link Scheduler} is null
     */
    public static void setDefaultScheduler(@Nonnull Scheduler scheduler) {
        Checks.notNull(scheduler, "Default Scheduler");
        FileProxy.defaultScheduler = scheduler;
    }

    /**
     * Returns the URL that has been passed to this proxy.
     * <br>
     * This URL is always from Discord.
     *
     * @return The URL of the file.
     */
    @Nonnull
    public String getUrl() {
        return url;
    }

    /**
     * Sets the custom OkHttpClient used by this instance, regardless of if {@link #setDefaultHttpClient(HttpClient)} has been used or not.
     *
     * @param  customHttpClient
     *         The custom {@link HttpClient} to use while making HTTP requests
     *
     * @throws IllegalArgumentException
     *         If the provided {@link HttpClient} is null
     *
     * @return This proxy for chaining convenience.
     */
    @Nonnull
    public FileProxy withClient(@Nonnull HttpClient customHttpClient) {
        Checks.notNull(customHttpClient, "Custom HTTP client");
        this.customHttpClient = customHttpClient;
        return this;
    }

    /**
     * Sets the custom {@link Scheduler} used by this instance, regardless of if {@link #setDefaultScheduler(Scheduler)} has been used or not.
     *
     * @param  customScheduler
     *         The custom {@link Scheduler} to use for reactive operations
     *
     * @throws IllegalArgumentException
     *         If the provided {@link Scheduler} is null
     *
     * @return This proxy for chaining convenience.
     */
    @Nonnull
    public FileProxy withScheduler(@Nonnull Scheduler customScheduler) {
        Checks.notNull(customScheduler, "Custom Scheduler");
        this.customScheduler = customScheduler;
        return this;
    }

    // INTERNAL DOWNLOAD METHODS

    protected HttpClient getHttpClient() {
        // Return custom HTTP client if set
        if (customHttpClient != null) {
            return customHttpClient;
        }

        Checks.notNull(defaultHttpClient, "Default HttpClient");
        return defaultHttpClient;
    }

    @Nonnull
    protected Scheduler getScheduler() {
        if (customScheduler != null) {
            return customScheduler;
        }
        if (defaultScheduler != null) {
            return defaultScheduler;
        }
        return Schedulers.boundedElastic();
    }

    @Nonnull
    @CheckReturnValue
    protected CompletableFuture<InputStream> download(String url) {
        return FutureUtil.thenApplyCancellable(downloadAsByteBuf(url), buf -> new ByteBufInputStream(buf, true));
    }

    @Nonnull
    @CheckReturnValue
    protected CompletableFuture<Icon> downloadAsIcon(String url) {
        return FutureUtil.thenApplyCancellable(downloadAsByteBuf(url), Icon::from);
    }

    @Nonnull
    @CheckReturnValue
    protected CompletableFuture<Path> downloadToPath(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath();
        Checks.check(path != null && !path.isEmpty(), "URL '%s' is invalid", url);

        int lastSlash = path.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        Checks.check(!fileName.isEmpty(), "URL '%s' has no file name", url);

        return downloadToPath(Paths.get(fileName));
    }

    @Nonnull
    @CheckReturnValue
    protected CompletableFuture<Path> downloadToPath(String url, Path path) {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        Checks.check(
                parent != null && Files.exists(parent), "Parent folder of the file '%s' does not exist.", absolute);
        if (Files.exists(absolute)) {
            Checks.check(Files.isRegularFile(absolute), "Path '%s' is not a regular file.", absolute);
            Checks.check(Files.isWritable(absolute), "File at '%s' is not writable.", absolute);
        }

        return FutureUtil.thenApplyCancellable(downloadAsByteBuf(url), buf -> {
            Path tmpPath = null;
            try {
                tmpPath = Files.createTempFile(absolute.getFileName().toString(), ".part");
                try (FileChannel channel = FileChannel.open(
                        tmpPath,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    while (buf.isReadable()) {
                        buf.readBytes(channel, buf.readableBytes());
                    }
                }
                Files.move(tmpPath, absolute, StandardCopyOption.REPLACE_EXISTING);
                return absolute;
            } catch (IOException e) {
                if (tmpPath != null) {
                    try {
                        Files.deleteIfExists(tmpPath);
                    } catch (IOException ignored) {
                    }
                }
                throw new UncheckedIOException(e);
            } finally {
                buf.release();
            }
        });
    }

    // API DOWNLOAD METHOD

    /**
     * Retrieves the {@link ByteBuf} of this file as a Project Reactor {@link Mono}.
     * <br>
     * <b>Note:</b> The returned {@link ByteBuf} is reference-counted and must be
     * released when no longer needed via {@link ByteBuf#release()}.
     *
     * @return {@link Mono} which emits the downloaded {@link ByteBuf}
     */
    @Nonnull
    @CheckReturnValue
    public Mono<ByteBuf> downloadAsMono() {
        return downloadAsMono(url);
    }

    /**
     * Alias for {@link #downloadAsMono()}.
     *
     * @return {@link Mono} which emits the downloaded {@link ByteBuf}
     */
    @Nonnull
    @CheckReturnValue
    public Mono<ByteBuf> asMono() {
        return downloadAsMono(url);
    }

    @Nonnull
    @CheckReturnValue
    protected Mono<ByteBuf> downloadAsMono(String url) {
        return this.getHttpClient()
                .request(HttpMethod.GET)
                .uri(url)
                .send((req, out) -> {
                    req.header(HttpHeaderNames.USER_AGENT, RestConfig.USER_AGENT)
                            .header(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP_DEFLATE);
                    return out;
                })
                .responseSingle((response, byteBufMono) -> {
                    int code = response.status().code();
                    if (code >= 200 && code < 300) {
                        return byteBufMono.retain().defaultIfEmpty(Unpooled.EMPTY_BUFFER);
                    } else {
                        return byteBufMono
                                .asString()
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new HttpException(code + ": "
                                        + response.status().reasonPhrase() + (body.isEmpty() ? "" : " - " + body))));
                    }
                })
                .publishOn(getScheduler());
    }

    /**
     * Retrieves the {@link ByteBuf} of this file.
     * <br>
     * <b>Note:</b> The returned {@link ByteBuf} is reference-counted and must be
     * released when no longer needed via {@link ByteBuf#release()}.
     *
     * @return {@link CompletableFuture} which holds a {@link ByteBuf}
     */
    @Nonnull
    @CheckReturnValue
    public CompletableFuture<ByteBuf> downloadAsByteBuf() {
        return downloadAsByteBuf(url);
    }

    /**
     * Alias for {@link #downloadAsByteBuf()}.
     *
     * @return {@link CompletableFuture} which holds a {@link ByteBuf}
     */
    @Nonnull
    @CheckReturnValue
    public CompletableFuture<ByteBuf> toFuture() {
        return downloadAsByteBuf();
    }

    @Nonnull
    @CheckReturnValue
    protected CompletableFuture<ByteBuf> downloadAsByteBuf(String url) {
        CompletableFuture<ByteBuf> future = new DownloadFuture<>(getScheduler());

        Disposable disposable = downloadAsMono(url).subscribe(future::complete, future::completeExceptionally);

        return FutureUtil.thenApplyCancellable(future, Function.identity(), disposable::dispose);
    }

    /**
     * Retrieves the {@link InputStream} of this file
     *
     * @return {@link CompletableFuture} which holds an {@link InputStream}, the
     *         {@link InputStream} must be closed manually
     */
    @Nonnull
    @CheckReturnValue
    public CompletableFuture<InputStream> download() {
        return download(url);
    }

    /**
     * Downloads the data of this file, and stores it in a file with the same name
     * as the queried file name (this would be the last segment of the URL).
     *
     * @return {@link CompletableFuture} which holds a {@link Path} which
     *         corresponds to the location the file has been downloaded.
     */
    @Nonnull
    @CheckReturnValue
    public CompletableFuture<Path> downloadToPath() {
        return downloadToPath(url);
    }

    /**
     * Downloads the data of this file into the specified file.
     *
     * @param file
     *             The file in which to download the data
     *
     * @return {@link CompletableFuture} which holds a {@link File}, it is the same
     *         as the file passed in the parameters.
     */
    @Nonnull
    @CheckReturnValue
    public CompletableFuture<File> downloadToFile(@Nonnull File file) {
        Checks.notNull(file, "File");

        CompletableFuture<Path> downloadToPathFuture = downloadToPath(url, file.toPath());
        return FutureUtil.thenApplyCancellable(downloadToPathFuture, Path::toFile);
    }

    /**
     * Downloads the data of this file into the specified file.
     *
     * @param path
     *             The file in which to download the image
     *
     * @return {@link CompletableFuture} which holds a {@link Path}, it is the same
     *         as the path passed in the parameters.
     */
    @Nonnull
    @CheckReturnValue
    public CompletableFuture<Path> downloadToPath(@Nonnull Path path) {
        Checks.notNull(path, "Path");
        return downloadToPath(url, path);
    }

    /**
     * Returns a {@link FileUpload} which supplies a data stream of this attachment,
     * with the given file name.
     * <br>
     * The returned {@link FileUpload} can be reused safely, and does not need to be
     * closed.
     *
     * @param name
     *             The name of the to-be-uploaded file
     *
     * @throws IllegalArgumentException If the file name is null or blank
     *
     * @return {@link FileUpload} from this attachment.
     */
    @Nonnull
    public FileUpload downloadAsFileUpload(@Nonnull String name) {
        return FileUpload.fromStreamSupplier(name, () -> {
            return download().join();
        });
    }

    private static class DownloadFuture<T> extends CompletableFuture<T> {
        private final Scheduler scheduler;

        private DownloadFuture(Scheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Nonnull
        @Override
        public Executor defaultExecutor() {
            return scheduler::schedule;
        }

        @Nonnull
        @CheckReturnValue
        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new DownloadFuture<>(scheduler);
        }
    }

    protected static class DownloadTask {
        private final Disposable disposable;
        private final CompletableFuture<InputStream> future;

        public DownloadTask(Disposable disposable, CompletableFuture<InputStream> future) {
            this.disposable = disposable;
            this.future = future;
        }

        protected void cancelCall() {
            if (disposable != null) {
                disposable.dispose();
            }
        }

        @Nonnull
        @CheckReturnValue
        protected CompletableFuture<InputStream> getFuture() {
            return future;
        }
    }
}
