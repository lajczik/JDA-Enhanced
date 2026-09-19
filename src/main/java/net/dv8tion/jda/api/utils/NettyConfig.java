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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.NettyUtils;
import net.dv8tion.jda.internal.utils.concurrent.AudioThread;
import net.dv8tion.jda.internal.utils.concurrent.CountingThreadFactory;
import net.dv8tion.jda.internal.utils.concurrent.HttpClientThread;
import net.dv8tion.jda.internal.utils.concurrent.WebSocketThread;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configuration options for Netty networking, WebSocket connections, and
 * Reactor HTTP client.
 *
 * <p>
 * Networking components such as event loop groups, transport type, thread
 * counts, and the HTTP client
 * are fixed for the lifetime of this configuration. Other parameters like
 * timeouts, buffer allocator, and payload limits
 * can be updated dynamically at runtime via their respective setter methods.
 *
 * @see JDABuilder#setNettyConfig(NettyConfig)
 * @see DefaultShardManagerBuilder#setNettyConfig(NettyConfig)
 */
public class NettyConfig implements AutoCloseable {
    /**
     * The default thread count for WebSocket EventLoopGroup (based on available
     * processors).
     */
    public static final int DEFAULT_WEBSOCKET_EVENT_LOOP_THREADS =
            Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 3);

    /**
     * The default thread count for HttpClient EventLoopGroup (max 8 threads).
     */
    public static final int DEFAULT_HTTP_CLIENT_EVENT_LOOP_THREADS =
            Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 8);

    /**
     * The default thread count for Audio EventLoopGroup (0 indicates sharing with the WebSocket EventLoopGroup).
     */
    public static final int DEFAULT_AUDIO_EVENT_LOOP_THREADS = 0;

    /**
     * The default socket connect timeout in milliseconds (10 seconds).
     */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10000;

    /**
     * The default maximum WebSocket frame payload length in bytes (64 MB).
     */
    public static final int DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65536 * 1024;

    /**
     * The default HTTP aggregator max content length in bytes (64 KB).
     */
    public static final int DEFAULT_HTTP_AGGREGATOR_MAX_CONTENT_LENGTH = 65536;

    /**
     * The default maximum idle time for pooled HTTP connections (60 seconds).
     */
    public static final Duration DEFAULT_MAX_CONNECTION_IDLE_TIME = Duration.ofSeconds(60);

    /**
     * The default maximum life time for pooled HTTP connections (5 minutes).
     */
    public static final Duration DEFAULT_MAX_CONNECTION_LIFE_TIME = Duration.ofMinutes(5);

    /**
     * The default setting for HTTP client response compression ({@code true}).
     */
    public static final boolean DEFAULT_HTTP_COMPRESSION = true;

    /**
     * Alias for {@link #DEFAULT_HTTP_COMPRESSION}.
     */
    public static final boolean DEFAULT_HTTP_CLIENT_COMPRESSION = true;

    /**
     * The default {@link ByteBufAllocator} used across JDA for high throughput and low GC pressure.
     */
    public static final ByteBufAllocator DEFAULT_ALLOCATOR = PooledByteBufAllocator.DEFAULT;

    private static volatile ByteBufAllocator globalAllocator = DEFAULT_ALLOCATOR;

    private final boolean useNativeTransport;
    private final int websocketEventLoopThreads;
    private final int httpClientEventLoopThreads;
    private final int audioEventLoopThreads;
    private final EventLoopGroup websocketLoopGroup;
    private final EventLoopGroup httpClientLoopGroup;
    private final EventLoopGroup audioLoopGroup;
    private final LoopResources loopResources;
    private final ConnectionProvider connectionProvider;
    private volatile HttpClient httpClient;

    private volatile ByteBufAllocator allocator;
    private boolean tcpNoDelay;
    private int connectTimeoutMillis;
    private int maxFramePayloadLength;
    private int httpAggregatorMaxContentLength;
    private boolean httpCompression;

    /**
     * Creates a new {@link NettyConfig} initialized with default configuration
     * values.
     */
    public NettyConfig() {
        this(DEFAULT_HTTP_COMPRESSION);
    }

    /**
     * Creates a new {@link NettyConfig} initialized with default configuration
     * values and specified HTTP client compression setting.
     *
     * @param httpCompression
     *                        Whether HTTP client compression (GZIP / Brotli) is enabled
     */
    public NettyConfig(boolean httpCompression) {
        this(
                PooledByteBufAllocator.DEFAULT,
                true,
                true,
                DEFAULT_WEBSOCKET_EVENT_LOOP_THREADS,
                DEFAULT_HTTP_CLIENT_EVENT_LOOP_THREADS,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_MAX_FRAME_PAYLOAD_LENGTH,
                DEFAULT_HTTP_AGGREGATOR_MAX_CONTENT_LENGTH,
                null,
                null,
                null,
                null,
                null,
                httpCompression);
    }

    /**
     * Constructs a new {@link NettyConfig} with custom parameters, sharing the WebSocket event loop group for audio.
     *
     * @param allocator
     *                                       The {@link ByteBufAllocator} to use, or
     *                                       {@code null} to use
     *                                       {@link PooledByteBufAllocator#DEFAULT}
     * @param useNativeTransport
     *                                       Whether to use native transport
     *                                       (Epoll/KQueue) if available
     * @param tcpNoDelay
     *                                       Whether TCP_NODELAY is enabled
     * @param websocketEventLoopThreads
     *                                       The number of threads in the WebSocket
     *                                       event loop group (must be positive)
     * @param httpClientEventLoopThreads
     *                                       The number of threads in the HTTP
     *                                       client event loop group (must be
     *                                       positive)
     * @param connectTimeoutMillis
     *                                       The socket connection timeout in
     *                                       milliseconds (must be positive)
     * @param maxFramePayloadLength
     *                                       The maximum WebSocket frame payload
     *                                       length in bytes (must be positive)
     * @param httpAggregatorMaxContentLength
     *                                       The maximum HTTP object aggregator
     *                                       content length in bytes (must be
     *                                       positive)
     * @param websocketLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       WebSocket connections, or {@code null}
     *                                       to create a default one
     * @param httpClientLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       HTTP client connections, or
     *                                       {@code null} to create a default one
     * @param loopResources
     *                                       Custom {@link LoopResources} for
     *                                       Reactor Netty HTTP client, or
     *                                       {@code null}
     * @param connectionProvider
     *                                       Custom {@link ConnectionProvider} for
     *                                       Reactor Netty HTTP client connection
     *                                       pool, or {@code null}
     * @param httpClient
     *                                       A pre-configured {@link HttpClient}, or
     *                                       {@code null} to build a default one
     */
    public NettyConfig(
            @Nullable ByteBufAllocator allocator,
            boolean useNativeTransport,
            boolean tcpNoDelay,
            int websocketEventLoopThreads,
            int httpClientEventLoopThreads,
            int connectTimeoutMillis,
            int maxFramePayloadLength,
            int httpAggregatorMaxContentLength,
            @Nullable EventLoopGroup websocketLoopGroup,
            @Nullable EventLoopGroup httpClientLoopGroup,
            @Nullable LoopResources loopResources,
            @Nullable ConnectionProvider connectionProvider,
            @Nullable HttpClient httpClient) {
        this(
                allocator,
                useNativeTransport,
                tcpNoDelay,
                websocketEventLoopThreads,
                httpClientEventLoopThreads,
                DEFAULT_AUDIO_EVENT_LOOP_THREADS,
                connectTimeoutMillis,
                maxFramePayloadLength,
                httpAggregatorMaxContentLength,
                websocketLoopGroup,
                httpClientLoopGroup,
                null,
                loopResources,
                connectionProvider,
                httpClient,
                DEFAULT_HTTP_COMPRESSION);
    }

    /**
     * Constructs a new {@link NettyConfig} with custom parameters, sharing the WebSocket event loop group for audio.
     *
     * @param allocator
     *                                       The {@link ByteBufAllocator} to use, or
     *                                       {@code null} to use
     *                                       {@link PooledByteBufAllocator#DEFAULT}
     * @param useNativeTransport
     *                                       Whether to use native transport
     *                                       (Epoll/KQueue) if available
     * @param tcpNoDelay
     *                                       Whether TCP_NODELAY is enabled
     * @param websocketEventLoopThreads
     *                                       The number of threads in the WebSocket
     *                                       event loop group (must be positive)
     * @param httpClientEventLoopThreads
     *                                       The number of threads in the HTTP
     *                                       client event loop group (must be
     *                                       positive)
     * @param connectTimeoutMillis
     *                                       The socket connection timeout in
     *                                       milliseconds (must be positive)
     * @param maxFramePayloadLength
     *                                       The maximum WebSocket frame payload
     *                                       length in bytes (must be positive)
     * @param httpAggregatorMaxContentLength
     *                                       The maximum HTTP object aggregator
     *                                       content length in bytes (must be
     *                                       positive)
     * @param websocketLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       WebSocket connections, or {@code null}
     *                                       to create a default one
     * @param httpClientLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       HTTP client connections, or
     *                                       {@code null} to create a default one
     * @param loopResources
     *                                       Custom {@link LoopResources} for
     *                                       Reactor Netty HTTP client, or
     *                                       {@code null}
     * @param connectionProvider
     *                                       Custom {@link ConnectionProvider} for
     *                                       Reactor Netty HTTP client connection
     *                                       pool, or {@code null}
     * @param httpClient
     *                                       A pre-configured {@link HttpClient}, or
     *                                       {@code null} to build a default one
     * @param httpCompression
     *                                       Whether HTTP client compression (GZIP / Brotli) is enabled
     */
    public NettyConfig(
            @Nullable ByteBufAllocator allocator,
            boolean useNativeTransport,
            boolean tcpNoDelay,
            int websocketEventLoopThreads,
            int httpClientEventLoopThreads,
            int connectTimeoutMillis,
            int maxFramePayloadLength,
            int httpAggregatorMaxContentLength,
            @Nullable EventLoopGroup websocketLoopGroup,
            @Nullable EventLoopGroup httpClientLoopGroup,
            @Nullable LoopResources loopResources,
            @Nullable ConnectionProvider connectionProvider,
            @Nullable HttpClient httpClient,
            boolean httpCompression) {
        this(
                allocator,
                useNativeTransport,
                tcpNoDelay,
                websocketEventLoopThreads,
                httpClientEventLoopThreads,
                DEFAULT_AUDIO_EVENT_LOOP_THREADS,
                connectTimeoutMillis,
                maxFramePayloadLength,
                httpAggregatorMaxContentLength,
                websocketLoopGroup,
                httpClientLoopGroup,
                null,
                loopResources,
                connectionProvider,
                httpClient,
                httpCompression);
    }

    /**
     * Constructs a new {@link NettyConfig} with full custom parameters including audio event loop configuration.
     *
     * <p><b>Music Bot Recommendation:</b>
     * If your bot connects to many voice channels concurrently (such as a music bot), it is strongly recommended
     * to either increase {@code websocketEventLoopThreads} or configure dedicated {@code audioEventLoopThreads}
     * / a dedicated {@code audioLoopGroup}. By default (when {@code audioLoopGroup == null} and {@code audioEventLoopThreads <= 0}),
     * audio UDP packets and voice signaling multiplex over {@link #getWebsocketLoopGroup()}. Isolating audio onto a separate
     * EventLoopGroup prevents voice packets (20ms frames) from experiencing jitter or latency when processing heavy
     * Gateway traffic.
     *
     * @param allocator
     *                                       The {@link ByteBufAllocator} to use, or
     *                                       {@code null} to use
     *                                       {@link PooledByteBufAllocator#DEFAULT}
     * @param useNativeTransport
     *                                       Whether to use native transport
     *                                       (Epoll/KQueue) if available
     * @param tcpNoDelay
     *                                       Whether TCP_NODELAY is enabled
     * @param websocketEventLoopThreads
     *                                       The number of threads in the WebSocket
     *                                       event loop group (must be positive)
     * @param httpClientEventLoopThreads
     *                                       The number of threads in the HTTP
     *                                       client event loop group (must be
     *                                       positive)
     * @param audioEventLoopThreads
     *                                       The number of threads in the Audio
     *                                       event loop group, or {@code 0} to share
     *                                       the WebSocket event loop group
     * @param connectTimeoutMillis
     *                                       The socket connection timeout in
     *                                       milliseconds (must be positive)
     * @param maxFramePayloadLength
     *                                       The maximum WebSocket frame payload
     *                                       length in bytes (must be positive)
     * @param httpAggregatorMaxContentLength
     *                                       The maximum HTTP object aggregator
     *                                       content length in bytes (must be
     *                                       positive)
     * @param websocketLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       WebSocket connections, or {@code null}
     *                                       to create a default one
     * @param httpClientLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       HTTP client connections, or
     *                                       {@code null} to create a default one
     * @param audioLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       audio connections (WebSocket and UDP),
     *                                       or {@code null} to share the WebSocket
     *                                       event loop group (or create one if {@code audioEventLoopThreads > 0})
     * @param loopResources
     *                                       Custom {@link LoopResources} for
     *                                       Reactor Netty HTTP client, or
     *                                       {@code null}
     * @param connectionProvider
     *                                       Custom {@link ConnectionProvider} for
     *                                       Reactor Netty HTTP client connection
     *                                       pool, or {@code null}
     * @param httpClient
     *                                       A pre-configured {@link HttpClient}, or
     *                                       {@code null} to build a default one
     */
    public NettyConfig(
            @Nullable ByteBufAllocator allocator,
            boolean useNativeTransport,
            boolean tcpNoDelay,
            int websocketEventLoopThreads,
            int httpClientEventLoopThreads,
            int audioEventLoopThreads,
            int connectTimeoutMillis,
            int maxFramePayloadLength,
            int httpAggregatorMaxContentLength,
            @Nullable EventLoopGroup websocketLoopGroup,
            @Nullable EventLoopGroup httpClientLoopGroup,
            @Nullable EventLoopGroup audioLoopGroup,
            @Nullable LoopResources loopResources,
            @Nullable ConnectionProvider connectionProvider,
            @Nullable HttpClient httpClient) {
        this(
                allocator,
                useNativeTransport,
                tcpNoDelay,
                websocketEventLoopThreads,
                httpClientEventLoopThreads,
                audioEventLoopThreads,
                connectTimeoutMillis,
                maxFramePayloadLength,
                httpAggregatorMaxContentLength,
                websocketLoopGroup,
                httpClientLoopGroup,
                audioLoopGroup,
                loopResources,
                connectionProvider,
                httpClient,
                DEFAULT_HTTP_COMPRESSION);
    }

    /**
     * Constructs a new {@link NettyConfig} with full custom parameters including audio event loop configuration
     * and HTTP client compression.
     *
     * <p><b>Music Bot Recommendation:</b>
     * If your bot connects to many voice channels concurrently (such as a music bot), it is strongly recommended
     * to either increase {@code websocketEventLoopThreads} or configure dedicated {@code audioEventLoopThreads}
     * / a dedicated {@code audioLoopGroup}. By default (when {@code audioLoopGroup == null} and {@code audioEventLoopThreads <= 0}),
     * audio UDP packets and voice signaling multiplex over {@link #getWebsocketLoopGroup()}. Isolating audio onto a separate
     * EventLoopGroup prevents voice packets (20ms frames) from experiencing jitter or latency when processing heavy
     * Gateway traffic.
     *
     * @param allocator
     *                                       The {@link ByteBufAllocator} to use, or
     *                                       {@code null} to use
     *                                       {@link PooledByteBufAllocator#DEFAULT}
     * @param useNativeTransport
     *                                       Whether to use native transport
     *                                       (Epoll/KQueue) if available
     * @param tcpNoDelay
     *                                       Whether TCP_NODELAY is enabled
     * @param websocketEventLoopThreads
     *                                       The number of threads in the WebSocket
     *                                       event loop group (must be positive)
     * @param httpClientEventLoopThreads
     *                                       The number of threads in the HTTP
     *                                       client event loop group (must be
     *                                       positive)
     * @param audioEventLoopThreads
     *                                       The number of threads in the Audio
     *                                       event loop group, or {@code 0} to share
     *                                       the WebSocket event loop group
     * @param connectTimeoutMillis
     *                                       The socket connection timeout in
     *                                       milliseconds (must be positive)
     * @param maxFramePayloadLength
     *                                       The maximum WebSocket frame payload
     *                                       length in bytes (must be positive)
     * @param httpAggregatorMaxContentLength
     *                                       The maximum HTTP object aggregator
     *                                       content length in bytes (must be
     *                                       positive)
     * @param websocketLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       WebSocket connections, or {@code null}
     *                                       to create a default one
     * @param httpClientLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       HTTP client connections, or
     *                                       {@code null} to create a default one
     * @param audioLoopGroup
     *                                       The {@link EventLoopGroup} to use for
     *                                       audio connections (WebSocket and UDP),
     *                                       or {@code null} to share the WebSocket
     *                                       event loop group (or create one if {@code audioEventLoopThreads > 0})
     * @param loopResources
     *                                       Custom {@link LoopResources} for
     *                                       Reactor Netty HTTP client, or
     *                                       {@code null}
     * @param connectionProvider
     *                                       Custom {@link ConnectionProvider} for
     *                                       Reactor Netty HTTP client connection
     *                                       pool, or {@code null}
     * @param httpClient
     *                                       A pre-configured {@link HttpClient}, or
     *                                       {@code null} to build a default one
     * @param httpCompression
     *                                       Whether HTTP client compression (GZIP / Brotli) is enabled
     */
    public NettyConfig(
            @Nullable ByteBufAllocator allocator,
            boolean useNativeTransport,
            boolean tcpNoDelay,
            int websocketEventLoopThreads,
            int httpClientEventLoopThreads,
            int audioEventLoopThreads,
            int connectTimeoutMillis,
            int maxFramePayloadLength,
            int httpAggregatorMaxContentLength,
            @Nullable EventLoopGroup websocketLoopGroup,
            @Nullable EventLoopGroup httpClientLoopGroup,
            @Nullable EventLoopGroup audioLoopGroup,
            @Nullable LoopResources loopResources,
            @Nullable ConnectionProvider connectionProvider,
            @Nullable HttpClient httpClient,
            boolean httpCompression) {
        this.allocator = allocator != null ? allocator : PooledByteBufAllocator.DEFAULT;
        this.useNativeTransport = useNativeTransport;
        this.tcpNoDelay = tcpNoDelay;
        this.httpCompression = httpCompression;
        Checks.positive(websocketEventLoopThreads, "WebSocket event loop threads");
        this.websocketEventLoopThreads = websocketEventLoopThreads;
        Checks.positive(httpClientEventLoopThreads, "HttpClient event loop threads");
        this.httpClientEventLoopThreads = httpClientEventLoopThreads;
        Checks.notNegative(audioEventLoopThreads, "Audio event loop threads");
        this.audioEventLoopThreads = audioEventLoopThreads;
        Checks.positive(connectTimeoutMillis, "Connect timeout");
        this.connectTimeoutMillis = connectTimeoutMillis;
        Checks.positive(maxFramePayloadLength, "Max frame payload length");
        this.maxFramePayloadLength = maxFramePayloadLength;
        Checks.positive(httpAggregatorMaxContentLength, "HTTP aggregator max content length");
        this.httpAggregatorMaxContentLength = httpAggregatorMaxContentLength;

        this.websocketLoopGroup = websocketLoopGroup != null
                ? websocketLoopGroup
                : NettyUtils.createEventLoopGroup(
                        this.websocketEventLoopThreads,
                        new CountingThreadFactory(() -> "JDA", "WebSocket", WebSocketThread::new),
                        this.useNativeTransport);
        this.httpClientLoopGroup = httpClientLoopGroup != null
                ? httpClientLoopGroup
                : NettyUtils.createEventLoopGroup(
                        this.httpClientEventLoopThreads,
                        new CountingThreadFactory(() -> "JDA", "HttpClient", HttpClientThread::new),
                        this.useNativeTransport);

        if (audioLoopGroup != null) {
            this.audioLoopGroup = audioLoopGroup;
        } else if (this.audioEventLoopThreads > 0) {
            this.audioLoopGroup = NettyUtils.createEventLoopGroup(
                    this.audioEventLoopThreads,
                    new CountingThreadFactory(() -> "JDA", "Audio", AudioThread::new),
                    this.useNativeTransport);
        } else {
            this.audioLoopGroup = this.websocketLoopGroup;
        }

        this.loopResources = loopResources;
        this.connectionProvider = connectionProvider;
        this.httpClient = httpClient != null ? httpClient : buildHttpClient(this, this.httpClientLoopGroup);
    }

    private static HttpClient buildHttpClient(NettyConfig config, EventLoopGroup loopGroup) {
        ConnectionProvider provider = config.connectionProvider;
        if (provider == null) {
            provider = ConnectionProvider.builder("JDA-Http")
                    .maxConnections(512)
                    .pendingAcquireMaxCount(-1)
                    .pendingAcquireTimeout(Duration.ofSeconds(45))
                    .maxIdleTime(DEFAULT_MAX_CONNECTION_IDLE_TIME)
                    .maxLifeTime(DEFAULT_MAX_CONNECTION_LIFE_TIME)
                    .evictInBackground(Duration.ofSeconds(60), Schedulers.single())
                    .build();
        }

        HttpClient client = HttpClient.create(provider);
        if (config.httpCompression) {
            client = client.compress(true);
        }

        return client.responseTimeout(Duration.ofSeconds(20))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis)
                .option(ChannelOption.ALLOCATOR, config.allocator)
                .option(ChannelOption.TCP_NODELAY, config.tcpNoDelay)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .runOn(loopGroup);
    }

    /**
     * Gets a new default {@link NettyConfig} instance.
     *
     * @return A new default NettyConfig
     */
    @Nonnull
    public static NettyConfig getDefault() {
        return new NettyConfig();
    }

    /**
     * Gets a new default {@link NettyConfig} instance with the specified HTTP client compression setting.
     *
     * @param  httpCompression
     *         Whether HTTP client compression (GZIP / Brotli) is enabled
     *
     * @return A new NettyConfig
     */
    @Nonnull
    public static NettyConfig getDefault(boolean httpCompression) {
        return new NettyConfig(httpCompression);
    }

    /**
     * Creates a {@link NettyConfig} optimized for low-memory environments (such as
     * micro-containers or Raspberry Pi).
     * <br>
     * Uses unpooled heap buffers and smaller payload buffers.
     *
     * @return A NettyConfig optimized for minimal memory footprint
     */
    @Nonnull
    public static NettyConfig lowMemory() {
        return lowMemory(DEFAULT_HTTP_COMPRESSION);
    }

    /**
     * Creates a {@link NettyConfig} optimized for low-memory environments (such as
     * micro-containers or Raspberry Pi) with specified HTTP client compression setting.
     * <br>
     * Uses unpooled heap buffers and smaller payload buffers.
     *
     * @param  httpCompression
     *         Whether HTTP client compression (GZIP / Brotli) is enabled
     *
     * @return A NettyConfig optimized for minimal memory footprint
     */
    @Nonnull
    public static NettyConfig lowMemory(boolean httpCompression) {
        return new NettyConfig(
                UnpooledByteBufAllocator.DEFAULT,
                true,
                true,
                1,
                DEFAULT_HTTP_CLIENT_EVENT_LOOP_THREADS,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                16 * 1024 * 1024,
                DEFAULT_HTTP_AGGREGATOR_MAX_CONTENT_LENGTH,
                null,
                null,
                null,
                null,
                null,
                httpCompression);
    }

    /**
     * Gets the global {@link ByteBufAllocator} used across JDA components when no
     * specific allocator is provided.
     * <br>
     * Defaults to {@link #DEFAULT_ALLOCATOR} ({@link PooledByteBufAllocator#DEFAULT}).
     *
     * @return The global ByteBufAllocator
     */
    @Nonnull
    public static ByteBufAllocator getGlobalAllocator() {
        return globalAllocator;
    }

    /**
     * Sets the global {@link ByteBufAllocator} used across JDA components when no
     * specific allocator is provided.
     * <br>
     * If set to {@code null}, safely resets to {@link #DEFAULT_ALLOCATOR}.
     * <br>
     * This method is thread-safe. Changing the allocator is safe for in-flight buffers,
     * as existing Netty {@link ByteBuf} instances deallocate through their originating allocator.
     *
     * @param allocator
     *                  The global allocator to use, or {@code null} to reset to
     *                  {@link #DEFAULT_ALLOCATOR}
     */
    public static void setGlobalAllocator(@Nullable ByteBufAllocator allocator) {
        globalAllocator = allocator != null ? allocator : DEFAULT_ALLOCATOR;
    }

    /**
     * The {@link ByteBufAllocator} used by Netty channels.
     *
     * @return The ByteBufAllocator
     */
    @Nonnull
    public ByteBufAllocator getByteBufAllocator() {
        return allocator;
    }

    /**
     * Sets the {@link ByteBufAllocator} used by Netty channels.
     * <br>This method is thread-safe and immediately updates this configuration,
     * the underlying {@link HttpClient}, and the global allocator.
     *
     * @param allocator
     *                  The allocator to use, or null to reset to default
     *
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public synchronized NettyConfig setByteBufAllocator(@Nullable ByteBufAllocator allocator) {
        ByteBufAllocator target = allocator != null ? allocator : DEFAULT_ALLOCATOR;
        if (!Objects.equals(this.allocator, target)) {
            this.allocator = target;
            this.httpClient = this.httpClient.option(ChannelOption.ALLOCATOR, target);
            setGlobalAllocator(target);
        }
        return this;
    }

    /**
     * Whether native transport (Epoll on Linux, KQueue on macOS) is enabled.
     *
     * @return True if native transport is enabled
     */
    public boolean isUseNativeTransport() {
        return useNativeTransport;
    }

    /**
     * Whether TCP_NODELAY (Nagle's algorithm disabled) is enabled for sockets.
     *
     * @return True if TCP_NODELAY is enabled
     */
    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    /**
     * Sets whether TCP_NODELAY (Nagle's algorithm disabled) is enabled for sockets.
     *
     * @param tcpNoDelay
     *                   True to enable TCP_NODELAY, false to disable
     *
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public NettyConfig setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
        return this;
    }

    /**
     * Whether HTTP client compression (GZIP / Brotli) is enabled.
     *
     * @return True if HTTP compression is enabled
     */
    public boolean isHttpCompression() {
        return httpCompression;
    }

    /**
     * Alias for {@link #isHttpCompression()}.
     *
     * @return True if HTTP client compression is enabled
     */
    public boolean isHttpClientCompression() {
        return httpCompression;
    }

    /**
     * Sets whether HTTP client compression (GZIP / Brotli) is enabled.
     * <br>This method is thread-safe and updates the underlying {@link HttpClient}.
     *
     * @param  httpCompression
     *         True to enable HTTP compression, false to disable
     *
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public synchronized NettyConfig setHttpCompression(boolean httpCompression) {
        if (this.httpCompression != httpCompression) {
            this.httpCompression = httpCompression;
            if (this.httpClient != null) {
                this.httpClient = this.httpClient.compress(httpCompression);
            }
        }
        return this;
    }

    /**
     * Alias for {@link #setHttpCompression(boolean)}.
     *
     * @param  httpClientCompression
     *         True to enable HTTP client compression, false to disable
     *
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public synchronized NettyConfig setHttpClientCompression(boolean httpClientCompression) {
        return setHttpCompression(httpClientCompression);
    }

    /**
     * The number of threads in JDA-managed WebSocket
     * {@link EventLoopGroup} (Gateway and Audio).
     *
     * @return The WebSocket event loop thread count
     */
    public int getWebsocketEventLoopThreads() {
        return websocketEventLoopThreads;
    }

    /**
     * The number of worker threads used by Reactor Netty {@link HttpClient} loop
     * resources.
     *
     * @return The HttpClient event loop thread count
     */
    public int getHttpClientEventLoopThreads() {
        return httpClientEventLoopThreads;
    }

    /**
     * The number of worker threads configured specifically for Netty Audio EventLoopGroup.
     * <br>A value of {@code 0} indicates that Audio shares the WebSocket event loop group.
     *
     * <p><b>Music Bot Recommendation:</b>
     * For bots with heavy audio usage (e.g. music bots serving many concurrent voice channels), it is recommended
     * to either increase {@link #getWebsocketEventLoopThreads()} or configure dedicated audio threads via
     * {@link #getAudioLoopGroup()} / {@link #getAudioEventLoopThreads()} so that audio UDP packets (20ms frames)
     * and Voice WebSocket events are processed on an isolated event loop group rather than competing with Discord Gateway events.
     *
     * @return The configured audio event loop thread count (0 if sharing with WebSocket)
     */
    public int getAudioEventLoopThreads() {
        return audioEventLoopThreads;
    }

    /**
     * The socket connection timeout in milliseconds.
     *
     * @return The connect timeout in milliseconds
     */
    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * The socket connection timeout as a {@link Duration}.
     *
     * @return The connect timeout Duration
     */
    @Nonnull
    public Duration getConnectTimeout() {
        return Duration.ofMillis(connectTimeoutMillis);
    }

    /**
     * Sets the socket connection timeout in milliseconds.
     *
     * @param connectTimeoutMillis
     *                             The connect timeout in milliseconds (must be
     *                             positive)
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public NettyConfig setConnectTimeoutMillis(int connectTimeoutMillis) {
        Checks.positive(connectTimeoutMillis, "Connect timeout");
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    /**
     * Sets the socket connection timeout.
     *
     * @param timeout
     *                The connect timeout duration
     *
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public NettyConfig setConnectTimeout(@Nonnull Duration timeout) {
        Checks.notNull(timeout, "Timeout");
        long millis = timeout.toMillis();
        Checks.check(millis > 0 && millis <= Integer.MAX_VALUE, "Timeout must be between 1ms and Integer.MAX_VALUE ms");
        return setConnectTimeoutMillis((int) millis);
    }

    /**
     * Sets the socket connection timeout.
     *
     * @param timeout
     *                The connect timeout amount
     * @param unit
     *                The time unit
     *
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public NettyConfig setConnectTimeout(long timeout, @Nonnull TimeUnit unit) {
        Checks.notNull(unit, "TimeUnit");
        long millis = unit.toMillis(timeout);
        Checks.check(millis > 0 && millis <= Integer.MAX_VALUE, "Timeout must be between 1ms and Integer.MAX_VALUE ms");
        return setConnectTimeoutMillis((int) millis);
    }

    /**
     * The maximum allowed WebSocket frame payload length in bytes.
     *
     * @return The maximum frame payload length
     */
    public int getMaxFramePayloadLength() {
        return maxFramePayloadLength;
    }

    /**
     * Sets the maximum allowed WebSocket frame payload length in bytes.
     *
     * @param maxLength
     *                  The maximum frame payload length in bytes (must be positive)
     *
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public NettyConfig setMaxWebSocketFramePayloadLength(int maxLength) {
        Checks.positive(maxLength, "Max frame payload length");
        this.maxFramePayloadLength = maxLength;
        return this;
    }

    /**
     * The maximum allowed content length in bytes for HTTP handshake aggregation.
     *
     * @return The maximum HTTP content length
     */
    public int getHttpAggregatorMaxContentLength() {
        return httpAggregatorMaxContentLength;
    }

    /**
     * Sets the maximum allowed HTTP aggregator content length in bytes.
     *
     * @param maxLength
     *                  The maximum HTTP aggregator content length in bytes (must be
     *                  positive)
     *
     * @return This NettyConfig instance for chaining
     */
    @Nonnull
    public NettyConfig setHttpObjectAggregatorMaxContentLength(int maxLength) {
        Checks.positive(maxLength, "HTTP aggregator max content length");
        this.httpAggregatorMaxContentLength = maxLength;
        return this;
    }

    /**
     * The {@link EventLoopGroup} used for WebSocket connections (Gateway and
     * Audio).
     * <br>
     * This instance is constant for the lifetime of this {@link NettyConfig}
     * instance.
     *
     * @return The constant WebSocket EventLoopGroup
     */
    @Nonnull
    public EventLoopGroup getWebsocketLoopGroup() {
        return websocketLoopGroup;
    }

    /**
     * Alias for {@link #getWebsocketLoopGroup()}.
     * <br>
     * This instance is constant for the lifetime of this {@link NettyConfig}
     * instance.
     *
     * @return The constant WebSocket EventLoopGroup
     */
    @Nonnull
    public EventLoopGroup getWebsocketEventLoopGroup() {
        return websocketLoopGroup;
    }

    /**
     * The {@link EventLoopGroup} used by the Reactor Netty HTTP client.
     * <br>
     * This instance is constant for the lifetime of this {@link NettyConfig}
     * instance.
     *
     * @return The constant HTTP client EventLoopGroup
     */
    @Nonnull
    public EventLoopGroup getHttpClientLoopGroup() {
        return httpClientLoopGroup;
    }

    /**
     * Alias for {@link #getHttpClientLoopGroup()}.
     * <br>
     * This instance is constant for the lifetime of this {@link NettyConfig}
     * instance.
     *
     * @return The constant HTTP client EventLoopGroup
     */
    @Nonnull
    public EventLoopGroup getHttpClientEventLoopGroup() {
        return httpClientLoopGroup;
    }

    /**
     * The {@link EventLoopGroup} used for Audio connections (Voice WebSocket and UDP).
     * <br>If a dedicated group was not configured, this returns the same instance as {@link #getWebsocketLoopGroup()}.
     * <br>This instance is constant for the lifetime of this {@link NettyConfig} instance.
     *
     * <p><b>Music Bot Recommendation:</b>
     * For bots with heavy audio usage (e.g. music bots serving many concurrent voice channels), it is recommended
     * to either increase {@link #getWebsocketEventLoopThreads()} or configure dedicated audio threads / group so
     * that audio UDP packets and Voice WebSocket events are processed on an isolated event loop group rather than
     * competing with Discord Gateway events.
     *
     * @return The Audio EventLoopGroup (same as {@link #getWebsocketLoopGroup()} if not separately configured)
     */
    @Nonnull
    public EventLoopGroup getAudioLoopGroup() {
        return audioLoopGroup;
    }

    /**
     * Alias for {@link #getAudioLoopGroup()}.
     * <br>This instance is constant for the lifetime of this {@link NettyConfig} instance.
     *
     * @return The Audio EventLoopGroup
     */
    @Nonnull
    public EventLoopGroup getAudioEventLoopGroup() {
        return audioLoopGroup;
    }

    /**
     * Whether the Audio subsystem is sharing the WebSocket {@link EventLoopGroup}.
     *
     * @return True if Audio shares the WebSocket EventLoopGroup
     */
    @SuppressWarnings("ReferenceEquality")
    public boolean isAudioLoopGroupShared() {
        return this.audioLoopGroup == this.websocketLoopGroup;
    }

    /**
     * The custom {@link LoopResources} used by Reactor Netty {@link HttpClient},
     * or {@code null} if default loop resources are used.
     * <br>
     * This instance is constant for the lifetime of this {@link NettyConfig}
     * instance.
     *
     * @return The constant LoopResources, or null
     */
    @Nullable
    public LoopResources getLoopResources() {
        return loopResources;
    }

    /**
     * The custom {@link ConnectionProvider} used by Reactor Netty
     * {@link HttpClient},
     * or {@code null} if default pool is used.
     * <br>
     * This instance is constant for the lifetime of this {@link NettyConfig}
     * instance.
     *
     * @return The constant ConnectionProvider, or null
     */
    @Nullable
    public ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }

    /**
     * The constant {@link HttpClient} configured on this NettyConfig.
     * <br>
     * This instance is constant for the lifetime of this {@link NettyConfig}
     * instance.
     *
     * @return The constant HttpClient instance
     */
    @Nonnull
    public HttpClient getHttpClient() {
        return this.httpClient;
    }

    /**
     * Gets the default maximum idle time for pooled HTTP connections.
     *
     * @return The max connection idle time duration
     */
    @Nonnull
    public Duration getMaxConnectionIdleTime() {
        return DEFAULT_MAX_CONNECTION_IDLE_TIME;
    }

    /**
     * Gets the default maximum life time for pooled HTTP connections.
     *
     * @return The max connection life time duration
     */
    @Nonnull
    public Duration getMaxConnectionLifeTime() {
        return DEFAULT_MAX_CONNECTION_LIFE_TIME;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NettyConfig that)) return false;
        return useNativeTransport == that.useNativeTransport
                && tcpNoDelay == that.tcpNoDelay
                && httpCompression == that.httpCompression
                && websocketEventLoopThreads == that.websocketEventLoopThreads
                && httpClientEventLoopThreads == that.httpClientEventLoopThreads
                && audioEventLoopThreads == that.audioEventLoopThreads
                && connectTimeoutMillis == that.connectTimeoutMillis
                && maxFramePayloadLength == that.maxFramePayloadLength
                && httpAggregatorMaxContentLength == that.httpAggregatorMaxContentLength
                && Objects.equals(allocator, that.allocator)
                && Objects.equals(websocketLoopGroup, that.websocketLoopGroup)
                && Objects.equals(httpClientLoopGroup, that.httpClientLoopGroup)
                && Objects.equals(audioLoopGroup, that.audioLoopGroup)
                && Objects.equals(loopResources, that.loopResources)
                && Objects.equals(connectionProvider, that.connectionProvider)
                && Objects.equals(httpClient, that.httpClient);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                allocator,
                useNativeTransport,
                tcpNoDelay,
                httpCompression,
                websocketEventLoopThreads,
                httpClientEventLoopThreads,
                audioEventLoopThreads,
                connectTimeoutMillis,
                maxFramePayloadLength,
                httpAggregatorMaxContentLength,
                websocketLoopGroup,
                httpClientLoopGroup,
                audioLoopGroup,
                loopResources,
                connectionProvider,
                httpClient);
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void close() {
        NettyUtils.disposeHttpClient(this.httpClient);
        if (this.connectionProvider != null && !this.connectionProvider.isDisposed()) {
            this.connectionProvider.dispose();
        }
        if (this.loopResources != null && !this.loopResources.isDisposed()) {
            this.loopResources.dispose();
        }
        if (this.websocketLoopGroup != null && !this.websocketLoopGroup.isShuttingDown()) {
            this.websocketLoopGroup.shutdownGracefully();
        }
        if (this.httpClientLoopGroup != null && !this.httpClientLoopGroup.isShuttingDown()) {
            this.httpClientLoopGroup.shutdownGracefully();
        }
        if (this.audioLoopGroup != null
                && this.audioLoopGroup != this.websocketLoopGroup
                && this.audioLoopGroup != this.httpClientLoopGroup
                && !this.audioLoopGroup.isShuttingDown()) {
            this.audioLoopGroup.shutdownGracefully();
        }
    }
}
