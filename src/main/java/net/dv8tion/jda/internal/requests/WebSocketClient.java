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

package net.dv8tion.jda.internal.requests;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.dv8tion.jda.api.GatewayEncoding;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDAInfo;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.ExceptionEvent;
import net.dv8tion.jda.api.events.RawGatewayEvent;
import net.dv8tion.jda.api.events.session.*;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.utils.Compression;
import net.dv8tion.jda.api.utils.MiscUtil;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.api.utils.SessionController;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;
import net.dv8tion.jda.api.utils.data.etf.ExTermEncoder;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.audio.ConnectionRequest;
import net.dv8tion.jda.internal.audio.ConnectionStage;
import net.dv8tion.jda.internal.entities.GuildImpl;
import net.dv8tion.jda.internal.handle.*;
import net.dv8tion.jda.internal.managers.AudioManagerImpl;
import net.dv8tion.jda.internal.managers.PresenceImpl;
import net.dv8tion.jda.internal.utils.*;
import net.dv8tion.jda.internal.utils.cache.AbstractCacheView;
import net.dv8tion.jda.internal.utils.compress.ZlibStreamDecoder;
import net.dv8tion.jda.internal.utils.compress.ZstdStreamDecoder;
import net.dv8tion.jda.internal.utils.concurrent.CountingThreadFactory;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

public class WebSocketClient {
    public static final ThreadLocal<Boolean> WS_THREAD = ThreadLocal.withInitial(() -> false);
    public static final Logger LOG = JDALogger.getLog(WebSocketClient.class);

    protected static final String INVALIDATE_REASON = "INVALIDATE_SESSION";
    protected static final long IDENTIFY_BACKOFF = TimeUnit.SECONDS.toMillis(SessionController.IDENTIFY_DELAY);
    private static final int PAYLOAD_HIGH_WATERMARK = 512;
    private static final int PAYLOAD_LOW_WATERMARK = 256;
    protected final JDAImpl api;
    protected final JDA.ShardInfo shardInfo;
    protected final Map<String, SocketHandler> handlers = new Object2ObjectOpenHashMap<>();
    protected final Compression compression;
    protected final int gatewayIntents;
    protected final MemberChunkManager chunkManager;
    protected final GatewayEncoding encoding;
    protected final ReentrantLock queueLock = new ReentrantLock();
    protected final ScheduledExecutorService executor;
    protected final ReentrantLock reconnectLock = new ReentrantLock();
    protected final Condition reconnectCondvar = reconnectLock.newCondition();
    protected final BlockingQueue<Runnable> payloadQueue = new LinkedBlockingQueue<>();
    protected final ExecutorService payloadProcessor;
    protected final Long2ObjectMap<ConnectionRequest> queuedAudioConnections = MiscUtil.newLongMap();
    protected final Queue<DataObject> chunkSyncQueue = new ConcurrentLinkedQueue<>();
    protected final Queue<DataObject> ratelimitQueue = new ConcurrentLinkedQueue<>();
    protected final AtomicInteger messagesSent = new AtomicInteger(0);
    public volatile Channel channel;
    protected EventLoopGroup group;
    protected boolean ownsEventLoopGroup;
    protected volatile CloseFrameInfo serverCloseFrame;
    protected volatile CloseFrameInfo clientCloseFrame;
    protected String traceMetadata = null;
    protected volatile String sessionId = null;
    protected String resumeUrl = null;
    protected volatile Future<?> keepAliveThread;
    protected boolean initiating;
    protected int missedHeartbeats = 0;
    protected int reconnectTimeoutS = 2;
    protected volatile long heartbeatStartTime;
    protected long identifyTime = 0;
    protected volatile long ratelimitResetTime;
    protected volatile boolean shutdown = false;
    protected boolean shouldReconnect;
    protected boolean handleIdentifyRateLimit = false;
    protected boolean connected = false;
    protected volatile boolean printedRateLimitMessage = false;
    protected volatile boolean sentAuthInfo = false;
    protected boolean firstInit = true;
    protected boolean processingReady = true;
    protected volatile ConnectNode connectNode;
    private WebSocketSendingThread ratelimitThread;

    public WebSocketClient(JDAImpl api, Compression compression, int gatewayIntents, GatewayEncoding encoding) {
        this.api = api;
        this.executor = api.getGatewayPool();
        this.shardInfo = api.getShardInfo();
        this.compression = compression;
        this.gatewayIntents = gatewayIntents;
        this.chunkManager = new MemberChunkManager(this);
        this.encoding = encoding;
        this.shouldReconnect = api.isAutoReconnect();
        this.connectNode = new StartingNode();
        this.payloadProcessor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                this.payloadQueue,
                new CountingThreadFactory(() -> api.getIdentifierString(), "Gateway-Processor", true));
        setupHandlers();
        try {
            api.getSessionController().appendSession(connectNode);
        } catch (RuntimeException | Error e) {
            LOG.error("Failed to append new session to session controller queue. Shutting down!", e);
            this.api.setStatus(JDA.Status.SHUTDOWN);
            this.api.handleEvent(new ShutdownEvent(api, OffsetDateTime.now(), 1006));
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else {
                throw (Error) e;
            }
        }
    }

    public JDA getJDA() {
        return api;
    }

    public void setAutoReconnect(boolean reconnect) {
        this.shouldReconnect = reconnect;
    }

    public boolean isConnected() {
        return connected;
    }

    public int getGatewayIntents() {
        return gatewayIntents;
    }

    public MemberChunkManager getChunkManager() {
        return chunkManager;
    }

    public void ready() {
        if (initiating) {
            initiating = false;
            processingReady = false;
            if (firstInit) {
                firstInit = false;
                if (api.getGuilds().size() >= 2000) // Show large warning when connected to >=2000 guilds
                {
                    JDAImpl.LOG.warn(" __      __ _    ___  _  _  ___  _  _   ___  _ ");
                    JDAImpl.LOG.warn(" \\ \\    / //_\\  | _ \\| \\| ||_ _|| \\| | / __|| |");
                    JDAImpl.LOG.warn("  \\ \\/\\/ // _ \\ |   /| .` | | | | .` || (_ ||_|");
                    JDAImpl.LOG.warn("   \\_/\\_//_/ \\_\\|_|_\\|_|\\_||___||_|\\_| \\___|(_)");
                    JDAImpl.LOG.warn("You're running a session with over 2000 connected");
                    JDAImpl.LOG.warn("guilds. You should shard the connection in order");
                    JDAImpl.LOG.warn("to split the load or things like resuming");
                    JDAImpl.LOG.warn("connection might not work as expected.");
                    JDAImpl.LOG.warn("For more info see https://git.io/vrFWP");
                }
                JDAImpl.LOG.info("Finished Loading!");
                api.handleEvent(new ReadyEvent(api));
            } else {
                updateAudioManagerReferences();
                JDAImpl.LOG.info("Finished (Re)Loading!");
                api.handleEvent(new SessionRecreateEvent(api));
            }
        } else {
            JDAImpl.LOG.debug("Successfully resumed Session!");
            api.handleEvent(new SessionResumeEvent(api));
        }
        api.setStatus(JDA.Status.CONNECTED);
    }

    public boolean isReady() {
        return !initiating;
    }

    public boolean isSession() {
        return sessionId != null;
    }

    public void handle(List<DataObject> events) {
        events.forEach(this::onDispatch);
    }

    public void send(DataObject message) {
        locked("Interrupted while trying to add request to queue", () -> ratelimitQueue.add(message));
    }

    public void cancelChunkRequest(String nonce) {
        locked(
                "Interrupted while trying to cancel chunk request",
                () -> chunkSyncQueue.removeIf(it -> it.getString("nonce", "").equals(nonce)));
    }

    public void sendChunkRequest(DataObject request) {
        locked("Interrupted while trying to add chunk request", () -> chunkSyncQueue.add(request));
    }

    protected boolean send(DataObject message, boolean skipQueue) {
        Channel ch = this.channel;
        if (!connected || ch == null || !ch.isActive()) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (this.ratelimitResetTime <= now) {
            this.messagesSent.set(0);
            this.ratelimitResetTime = now + 60000; // 60 seconds
            this.printedRateLimitMessage = false;
        }

        // Allows 115 messages to be sent before limiting.
        if (this.messagesSent.get() <= 115 || (skipQueue && this.messagesSent.get() <= 119)) {
            if (LOG.isTraceEnabled()) {
                String redactedMessage = message.toString().replace(getToken(), "<REDACTED>");
                LOG.trace("<- {}", redactedMessage);
            }
            ByteBuf buf = ch.alloc().buffer();
            if (encoding == GatewayEncoding.ETF) {
                buf.writeByte(131);
                ExTermEncoder.pack(buf, message.toMap());
                ch.writeAndFlush(new BinaryWebSocketFrame(buf));
            } else {
                SerializationUtil.writeJson(buf, message.toMap());
                ch.writeAndFlush(new TextWebSocketFrame(buf));
            }
            this.messagesSent.getAndIncrement();
            return true;
        } else {
            if (!printedRateLimitMessage) {
                LOG.warn(
                        "Hit the WebSocket RateLimit! This can be caused by too many presence or voice status updates (connect/disconnect/mute/deaf). "
                                + "Regular: {} Voice: {} Chunking: {}",
                        ratelimitQueue.size(),
                        queuedAudioConnections.size(),
                        chunkSyncQueue.size());
                printedRateLimitMessage = true;
            }
            return false;
        }
    }

    protected void setupSendingThread() {
        ratelimitThread = new WebSocketSendingThread(this);
        ratelimitThread.start();
    }

    public void close() {
        close(1000, null);
    }

    public void close(int code) {
        close(code, null);
    }

    public void close(int code, String reason) {
        Channel ch = this.channel;
        if (ch != null && ch.isActive()) {
            this.clientCloseFrame = new CloseFrameInfo(code, reason);
            ch.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public void shutdown() {
        boolean callOnShutdown = MiscUtil.locked(reconnectLock, () -> {
            if (shutdown) {
                return false;
            }
            shutdown = true;
            shouldReconnect = false;
            if (connectNode != null) {
                api.getSessionController().removeSession(connectNode);
            }
            boolean wasConnected = connected;
            close(1000, "Shutting down");
            reconnectCondvar.signalAll(); // signal reconnect attempts to stop
            return !wasConnected;
        });

        if (ownsEventLoopGroup && group != null) {
            group.shutdownGracefully();
        }

        List<Runnable> dropped = payloadProcessor.shutdownNow();
        for (Runnable task : dropped) {
            if (task instanceof PayloadTask payloadTask) {
                payloadTask.release();
            }
        }

        if (callOnShutdown) {
            onShutdown(1000);
        }
    }

    /*
     * ### Start Internal methods ###
     */

    protected void clearCloseFrames() {
        serverCloseFrame = null;
        clientCloseFrame = null;
    }

    protected void onShutdown(int rawCloseCode) {
        clearCloseFrames();
        api.shutdownInternals(new ShutdownEvent(api, OffsetDateTime.now(), rawCloseCode));
    }

    protected synchronized void connect() {
        clearCloseFrames();
        if (api.getStatus() != JDA.Status.ATTEMPTING_TO_RECONNECT) {
            api.setStatus(JDA.Status.CONNECTING_TO_WEBSOCKET);
        }
        if (shutdown) {
            throw new RejectedExecutionException("JDA is shutdown!");
        }
        initiating = true;

        try {
            String gatewayUrl = resumeUrl != null ? resumeUrl : api.getGatewayUrl();
            gatewayUrl = IOUtil.addQuery(
                    gatewayUrl,
                    "encoding",
                    encoding.name().toLowerCase(Locale.ROOT),
                    "v",
                    JDAInfo.DISCORD_GATEWAY_VERSION);
            if (compression != Compression.NONE) {
                gatewayUrl = IOUtil.addQuery(gatewayUrl, "compress", compression.getKey());
            }

            URI uri = URI.create(gatewayUrl);
            String scheme = uri.getScheme() == null ? "wss" : uri.getScheme();
            String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            int defaultPort = "ws".equalsIgnoreCase(scheme) ? 80 : 443;
            final int port = uri.getPort() != -1 ? uri.getPort() : defaultPort;
            boolean ssl = "wss".equalsIgnoreCase(scheme);
            final SslContext sslCtx;
            if (ssl) {
                sslCtx = NettyUtils.createSslContext();
            } else {
                sslCtx = null;
            }

            NettyConfig nettyConfig = api.getNettyConfig();
            this.group = api.getWebsocketEventLoopGroup();
            this.ownsEventLoopGroup = false;

            HttpHeaders customHeaders = new DefaultHttpHeaders();
            customHeaders.add(HttpHeaderNames.USER_AGENT, api.getRequester().getUserAgent());

            WebSocketClientHandshaker handshaker =
                    NettyUtils.newHandshaker(uri, customHeaders, nettyConfig.getMaxFramePayloadLength());

            CompletableFuture<Void> handshakeFuture = new CompletableFuture<>();

            Bootstrap b = new Bootstrap();
            b.group(group);
            NettyUtils.configureBootstrap(b, nettyConfig);
            b.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    if (sslCtx != null) {
                        p.addLast("ssl", sslCtx.newHandler(ch.alloc(), host, port));
                    }
                    p.addLast("http-codec", new HttpClientCodec());
                    p.addLast(
                            "http-aggregator",
                            new HttpObjectAggregator(nettyConfig.getHttpAggregatorMaxContentLength()));
                    if (compression == Compression.ZSTD) {
                        if (!Compression.ZSTD.isSupported()) {
                            throw new IllegalStateException(
                                    "ZSTD compression is not supported on this classpath (missing zstd-jni dependency)");
                        }
                        p.addLast("zstd-decoder", new ZstdStreamDecoder(api.getMaxBufferSize()));
                    } else if (compression == Compression.ZLIB) {
                        p.addLast("zlib-decoder", new ZlibStreamDecoder(api.getMaxBufferSize()));
                    }
                    p.addLast("ws-handler", new GatewayWebSocketHandler(handshaker, handshakeFuture));
                }
            });

            ChannelFuture connectFuture = b.connect(host, port);
            this.channel = connectFuture.channel();
            connectFuture.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    handshakeFuture.completeExceptionally(future.cause());
                }
            });
            try {
                handshakeFuture.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        } catch (Throwable e) {
            resumeUrl = null;
            api.resetGatewayUrl();
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else if (e instanceof Error) {
                throw (Error) e;
            }
            throw new IllegalStateException(e);
        }
    }

    public void onConnected(HttpHeaders headers) {
        api.setStatus(JDA.Status.CONNECTING_TO_WEBSOCKET);
        if (sessionId == null) {
            String transport = this.channel != null ? this.channel.getClass().getSimpleName() : "Unknown";
            LOG.info("Connected to WebSocket (Transport: {}, Compression: {})", transport, compression);
            // Log which intents are used on debug level since most people won't know how to
            // use the
            // binary output anyway
            LOG.debug("Connected with gateway intents: {}", Integer.toBinaryString(gatewayIntents));
        } else {
            // no need to log for resume here
            LOG.debug("Connected to WebSocket");
        }
        connected = true;
        // reconnectTimeoutS = 2; We will reset this when the session was started
        // successfully
        // (ready/resume)
        messagesSent.set(0);
        ratelimitResetTime = System.currentTimeMillis() + 60000;
    }

    public void onDisconnected(
            CloseFrameInfo serverCloseFrame, CloseFrameInfo clientCloseFrame, boolean closedByServer) {
        sentAuthInfo = false;
        connected = false;
        Thread.ofVirtual()
                .name(api.getIdentifierString() + " MainWS-ReconnectThread")
                .start(() -> handleDisconnect(serverCloseFrame, clientCloseFrame, closedByServer));
    }

    private void handleDisconnect(
            CloseFrameInfo serverCloseFrame, CloseFrameInfo clientCloseFrame, boolean closedByServer) {
        api.setStatus(JDA.Status.DISCONNECTED);
        CloseCode closeCode = null;
        int rawCloseCode = 1005;
        // When we get 1000 from remote close we will try to resume
        // as apparently discord doesn't understand what "graceful disconnect" means
        boolean isInvalidate = false;

        if (keepAliveThread != null) {
            keepAliveThread.cancel(false);
            keepAliveThread = null;
        }
        if (closedByServer && serverCloseFrame != null) {
            rawCloseCode = serverCloseFrame.statusCode();
            String rawCloseReason = serverCloseFrame.reason();
            closeCode = CloseCode.from(rawCloseCode);
            if (closeCode == CloseCode.RATE_LIMITED) {
                LOG.error(
                        "WebSocket connection closed due to ratelimit! Sent more than 120 websocket messages in under 60 seconds!");
            } else if (closeCode == CloseCode.UNKNOWN_ERROR) {
                LOG.error("WebSocket connection closed due to server error! {}: {}", rawCloseCode, rawCloseReason);
            } else if (closeCode != null) {
                LOG.debug("WebSocket connection closed with code {}", closeCode);
            } else if (rawCloseReason != null) {
                LOG.warn("WebSocket connection closed with code {}: {}", rawCloseCode, rawCloseReason);
            } else {
                LOG.warn("WebSocket connection closed with unknown meaning for close-code {}", rawCloseCode);
            }
        } else if (clientCloseFrame != null) {
            rawCloseCode = clientCloseFrame.statusCode();
            if (rawCloseCode == 1000 && INVALIDATE_REASON.equals(clientCloseFrame.reason())) {
                // When we close with 1000 we properly dropped our session due to invalidation
                // in that case we can be sure that resume will not work and instead we
                // invalidate
                // and reconnect here
                isInvalidate = true;
            }
        }

        // null is considered -reconnectable- as we do not know the close-code meaning
        boolean closeCodeIsReconnect = closeCode == null || closeCode.isReconnect();
        if (!shouldReconnect || !closeCodeIsReconnect || executor.isShutdown()) // we should not reconnect
        {
            if (ratelimitThread != null) {
                ratelimitThread.shutdown();
                ratelimitThread = null;
            }

            if (!closeCodeIsReconnect) {
                // it is possible that a token can be invalidated due to too many reconnect
                // attempts
                // or that a bot reached a new shard minimum and cannot connect with the current
                // settings
                // if that is the case we have to drop our connection and inform the user with a
                // fatal error message
                LOG.error(
                        "WebSocket connection was closed and cannot be recovered due to identification issues\n{}",
                        closeCode);

                // Forward the close reason to any hooks to awaitStatus / awaitReady
                // Since people cannot read logs, we have to explicitly forward this error.
                switch (closeCode) {
                    case SHARDING_REQUIRED:
                    case INVALID_SHARD:
                        api.shutdownReason = ShutdownReason.INVALID_SHARDS;
                        break;
                    case DISALLOWED_INTENTS:
                        api.shutdownReason = ShutdownReason.DISALLOWED_INTENTS;
                        break;
                    case GRACEFUL_CLOSE:
                        break;
                    default:
                        api.shutdownReason = new ShutdownReason("Connection closed with code " + closeCode);
                }
            }

            onShutdown(rawCloseCode);
        } else {
            if (isInvalidate) {
                invalidate(); // 1000 means our session is dropped so we cannot resume
            }
            api.handleEvent(new SessionDisconnectEvent(
                    api, serverCloseFrame, clientCloseFrame, closedByServer, OffsetDateTime.now()));
            try {
                handleReconnect(rawCloseCode);
            } catch (InterruptedException e) {
                LOG.error("Failed to resume due to interrupted thread", e);
                invalidate();
                queueReconnect();
            }
        }
    }

    private void handleReconnect(int code) throws InterruptedException {
        if (sessionId == null) {
            if (handleIdentifyRateLimit) {
                long backoff = calculateIdentifyBackoff();
                if (backoff > 0) {
                    // it seems that most of the time this is already sub-0 when we reach this point
                    LOG.error("Encountered IDENTIFY Rate Limit! Waiting {} milliseconds before trying again!", backoff);
                    Thread.sleep(backoff);
                } else {
                    LOG.error("Encountered IDENTIFY Rate Limit!");
                }
            }
            LOG.warn("Got disconnected from WebSocket (Code {}). Appending to reconnect queue", code);
            queueReconnect();
        } else // if resume is possible
        {
            LOG.debug("Got disconnected from WebSocket (Code: {}). Attempting to resume session", code);
            reconnect();
        }
    }

    protected long calculateIdentifyBackoff() {
        long currentTime = System.currentTimeMillis();
        // calculate remaining backoff time since identify
        return currentTime - (identifyTime + IDENTIFY_BACKOFF);
    }

    protected void queueReconnect() {
        try {
            this.api.setStatus(JDA.Status.RECONNECT_QUEUED);
            this.connectNode = new ReconnectNode();
            this.api.getSessionController().appendSession(connectNode);
        } catch (IllegalStateException ex) {
            LOG.error("Reconnect queue rejected session. Shutting down...");
            this.api.setStatus(JDA.Status.SHUTDOWN);
            this.api.handleEvent(new ShutdownEvent(api, OffsetDateTime.now(), 1006));
        }
    }

    protected void reconnect() throws InterruptedException {
        reconnect(false);
    }

    /**
     * This method is used to start the reconnect of the JDA instance.
     * It is public for access from SessionReconnectQueue extensions.
     *
     * @param callFromQueue
     *                      whether this was in SessionReconnectQueue and got polled
     */
    public void reconnect(boolean callFromQueue) throws InterruptedException {
        Set<MDC.MDCCloseable> contextEntries = null;
        Map<String, String> previousContext = null;
        {
            ConcurrentMap<String, String> contextMap = api.getContextMap();
            if (callFromQueue && contextMap != null) {
                previousContext = MDC.getCopyOfContextMap();
                contextEntries = contextMap.entrySet().stream()
                        .map((entry) -> MDC.putCloseable(entry.getKey(), entry.getValue()))
                        .collect(Collectors.toSet());
            }
        }

        String message = "";
        if (callFromQueue) {
            message = String.format(
                    "Queue is attempting to reconnect a shard...%s ",
                    shardInfo != null ? " Shard: " + shardInfo.getShardString() : "");
        }
        if (sessionId != null) {
            reconnectTimeoutS = 0;
        }
        LOG.debug("{}Attempting to reconnect in {}s", message, reconnectTimeoutS);
        boolean isShutdown = MiscUtil.locked(reconnectLock, () -> {
            while (shouldReconnect) {
                api.setStatus(JDA.Status.WAITING_TO_RECONNECT);

                int delay = reconnectTimeoutS;
                // Exponential backoff, reset on session creation (ready/resume)
                reconnectTimeoutS =
                        reconnectTimeoutS == 0 ? 2 : Math.min(reconnectTimeoutS << 1, api.getMaxReconnectDelay());

                try {
                    // On shutdown, this condvar is notified and we stop reconnecting
                    reconnectCondvar.await(delay, TimeUnit.SECONDS);
                    if (!shouldReconnect) {
                        break;
                    }

                    handleIdentifyRateLimit = false;
                    api.setStatus(JDA.Status.ATTEMPTING_TO_RECONNECT);
                    LOG.debug("Attempting to reconnect!");
                    connect();
                    break;
                } catch (RejectedExecutionException | InterruptedException ex) {
                    // JDA has already been shutdown so we can stop here
                    return true;
                } catch (RuntimeException ex) {
                    LOG.debug("Reconnect failed with exception", ex);
                    LOG.warn("Reconnect failed! Next attempt in {}s", reconnectTimeoutS);
                }
            }
            return !shouldReconnect;
        });

        if (isShutdown) {
            LOG.debug("Reconnect cancelled due to shutdown.");
            shutdown();
        }

        if (contextEntries != null) {
            contextEntries.forEach(MDC.MDCCloseable::close);
        }
        if (previousContext != null) {
            previousContext.forEach(MDC::put);
        }
    }

    protected void setupKeepAlive(int timeout) {
        if (keepAliveThread != null) {
            keepAliveThread.cancel(false);
        }
        keepAliveThread = executor.scheduleAtFixedRate(
                () -> {
                    api.setContext();
                    if (connected) {
                        sendKeepAlive();
                    }
                },
                0,
                timeout,
                TimeUnit.MILLISECONDS);
    }

    protected void sendKeepAlive() {
        DataObject keepAlivePacket =
                DataObject.empty().put("op", WebSocketCode.HEARTBEAT).put("d", api.getResponseTotal());

        if (missedHeartbeats >= 2) {
            missedHeartbeats = 0;
            LOG.warn("Missed 2 heartbeats! Trying to reconnect...");
            close(4900, "ZOMBIE CONNECTION");
        } else {
            missedHeartbeats += 1;
            send(keepAlivePacket, true);
            heartbeatStartTime = System.currentTimeMillis();
        }
    }

    protected void sendIdentify() {
        LOG.debug("Sending Identify-packet...");
        PresenceImpl presenceObj = (PresenceImpl) api.getPresence();
        DataObject connectionProperties = DataObject.empty()
                .put("os", System.getProperty("os.name"))
                .put("browser", "JDA+")
                .put("device", "JDA+");
        DataObject payload = DataObject.empty()
                .put("presence", presenceObj.getFullPresence())
                .put("token", getToken())
                .put("properties", connectionProperties)
                .put("large_threshold", api.getLargeThreshold())
                .put("intents", gatewayIntents);

        DataObject identify =
                DataObject.empty().put("op", WebSocketCode.IDENTIFY).put("d", payload);
        if (shardInfo != null) {
            payload.put("shard", DataArray.empty().add(shardInfo.getShardId()).add(shardInfo.getShardTotal()));
        }
        send(identify, true);
        handleIdentifyRateLimit = true;
        identifyTime = System.currentTimeMillis();
        sentAuthInfo = true;
        api.setStatus(JDA.Status.AWAITING_LOGIN_CONFIRMATION);
    }

    protected void sendResume() {
        LOG.debug("Sending Resume-packet...");
        DataObject resume = DataObject.empty()
                .put("op", WebSocketCode.RESUME)
                .put(
                        "d",
                        DataObject.empty()
                                .put("session_id", sessionId)
                                .put("token", getToken())
                                .put("seq", api.getResponseTotal()));
        send(resume, true);
        // sentAuthInfo = true; set on RESUMED response as this could fail
        api.setStatus(JDA.Status.AWAITING_LOGIN_CONFIRMATION);
    }

    protected void invalidate() {
        resumeUrl = null;
        sessionId = null;
        sentAuthInfo = false;

        locked("Interrupted while trying to invalidate chunk/sync queue", chunkSyncQueue::clear);

        List<Runnable> pending = new ArrayList<>();
        payloadQueue.drainTo(pending);
        for (Runnable task : pending) {
            if (task instanceof PayloadTask payloadTask) {
                payloadTask.release();
            }
        }

        api.getGuildSetupController().clearCache();
        api.getGuildSetupController().cacheGuildsOnInvalidate(api.getGuildsView());

        api.getChannelsView().clear();

        api.getGuildsView().clear();
        api.getUsersView().clear();

        api.getEventCache().clear();
        chunkManager.clear();

        api.handleEvent(new SessionInvalidateEvent(api));
    }

    protected void updateAudioManagerReferences() {
        api.getGuildSetupController().clearReloadCandidates();
        AbstractCacheView<AudioManager> managerView = api.getAudioManagersView();
        try (UnlockHook hook = managerView.writeLock()) {
            Long2ObjectMap<AudioManager> managerMap = managerView.getMap();
            if (!managerMap.isEmpty()) {
                LOG.trace("Updating AudioManager references");
            }

            ObjectIterator<Long2ObjectMap.Entry<AudioManager>> it =
                    managerMap.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<AudioManager> entry = it.next();
                long guildId = entry.getLongKey();
                AudioManagerImpl mng = (AudioManagerImpl) entry.getValue();

                GuildImpl guild = (GuildImpl) api.getGuildById(guildId);
                if (guild == null) {
                    // We no longer have access to the guild that this audio manager was for. Set
                    // the value to null.
                    queuedAudioConnections.remove(guildId);
                    mng.closeAudioConnection(ConnectionStatus.DISCONNECTED_REMOVED_DURING_RECONNECT);
                    it.remove();
                }
            }
        }
    }

    protected String getToken() {
        // all bot tokens are prefixed with "Bot "
        return api.getToken().substring("Bot ".length());
    }

    protected List<DataObject> convertPresencesReplace(long responseTotal, DataArray array) {
        // Needs special handling due to content of "d" being an array
        List<DataObject> output = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            DataObject presence = array.getObject(i);
            DataObject obj = DataObject.empty();
            obj.put("comment", "This was constructed from a PRESENCES_REPLACE payload")
                    .put("op", WebSocketCode.DISPATCH)
                    .put("s", responseTotal)
                    .put("d", presence)
                    .put("t", "PRESENCE_UPDATE");
            output.add(obj);
        }
        return output;
    }

    protected void handleEvent(DataObject content) {
        try {
            onEvent(content);
        } catch (Exception ex) {
            LOG.error("Encountered exception on lifecycle level\nJSON: {}", content, ex);
            api.handleEvent(new ExceptionEvent(api, ex, true));
        }
    }

    protected void onEvent(DataObject content) {
        WS_THREAD.set(true);
        int opCode = content.getInt("op");

        if (!content.isNull("s")) {
            api.setResponseTotal(content.getInt("s"));
        }

        switch (opCode) {
            case WebSocketCode.DISPATCH -> onDispatch(content);
            case WebSocketCode.HEARTBEAT -> {
                LOG.debug("Got Keep-Alive request (OP 1). Sending response...");
                sendKeepAlive();
            }
            case WebSocketCode.RECONNECT -> {
                LOG.debug("Got Reconnect request (OP 7). Closing connection now...");
                close(4900, "OP 7: RECONNECT");
            }
            case WebSocketCode.INVALIDATE_SESSION -> {
                LOG.debug("Got Invalidate request (OP 9). Invalidating...");
                handleIdentifyRateLimit =
                        handleIdentifyRateLimit && System.currentTimeMillis() - identifyTime < IDENTIFY_BACKOFF;

                sentAuthInfo = false;
                boolean isResume = content.getBoolean("d");
                // When d: true we can wait a bit and then try to resume again
                // sending 4000 to not drop session
                int closeCode = isResume ? 4900 : 1000;
                if (isResume) {
                    LOG.debug("Session can be recovered... Closing and sending new RESUME request");
                } else {
                    invalidate();
                }

                close(closeCode, INVALIDATE_REASON);
            }
            case WebSocketCode.HELLO -> {
                LOG.debug("Got HELLO packet (OP 10). Initializing keep-alive.");
                DataObject data = content.getObject("d");
                setupKeepAlive(data.getInt("heartbeat_interval"));
                if (sessionId == null) {
                    sendIdentify();
                } else {
                    sendResume();
                }
            }
            case WebSocketCode.HEARTBEAT_ACK -> {
                LOG.trace("Got Heartbeat Ack (OP 11).");
                missedHeartbeats = 0;
                api.setGatewayPing(System.currentTimeMillis() - heartbeatStartTime);
            }
            default -> LOG.debug("Got unknown op-code: {} with content: {}", opCode, content);
        }
    }

    protected void onDispatch(DataObject raw) {
        String type = raw.getString("t");
        long responseTotal = api.getResponseTotal();

        if (!raw.isType("d", DataType.OBJECT)) {
            // Needs special handling due to content of "d" being an array
            if (type.equals("PRESENCES_REPLACE")) {
                DataArray payload = raw.getArray("d");
                List<DataObject> converted = convertPresencesReplace(responseTotal, payload);
                SocketHandler handler = getHandler("PRESENCE_UPDATE");
                LOG.trace("{} -> {}", type, payload);
                for (DataObject o : converted) {
                    handler.handle(responseTotal, o);
                    // Send raw event after cache has been updated - including comment
                    if (api.isRawEvents()) {
                        api.handleEvent(new RawGatewayEvent(api, responseTotal, o));
                    }
                }
            } else {
                LOG.debug("Received event with unhandled body type JSON: {}", raw);
            }
            return;
        }

        DataObject content = raw.getObject("d");
        LOG.trace("{} -> {}", type, content);

        JDAImpl jda = (JDAImpl) getJDA();
        try {
            switch (type) {
                // INIT types
                case "READY":
                    reconnectTimeoutS = 2;
                    api.setStatus(JDA.Status.LOADING_SUBSYSTEMS);
                    processingReady = true;
                    handleIdentifyRateLimit = false;
                    // first handle the ready payload before applying the session id
                    // this prevents a possible race condition with the cache of the guild setup
                    // controller
                    // otherwise the audio connection requests that are currently pending might be
                    // removed in the process
                    handlers.get("READY").handle(responseTotal, raw);
                    sessionId = content.getString("session_id");
                    resumeUrl = content.getString("resume_gateway_url", null);
                    traceMetadata = content.opt("_trace").map(String::valueOf).orElse(null);
                    LOG.debug("Received READY with _trace {}", traceMetadata);
                    break;
                case "RESUMED":
                    reconnectTimeoutS = 2;
                    sentAuthInfo = true;
                    traceMetadata = content.opt("_trace").map(String::valueOf).orElse(traceMetadata);
                    if (!processingReady) {
                        initiating = false;
                        ready();
                    } else {
                        LOG.debug("Resumed while still processing initial ready");
                        jda.setStatus(JDA.Status.LOADING_SUBSYSTEMS);
                    }
                    break;
                default:
                    long guildId = content.getLong("guild_id", 0L);
                    if (api.isUnavailable(guildId) && !type.equals("GUILD_CREATE") && !type.equals("GUILD_DELETE")) {
                        LOG.debug("Ignoring {} for unavailable guild with id {}. JSON: {}", type, guildId, content);
                        break;
                    }
                    SocketHandler handler = handlers.get(type);
                    if (handler != null) {
                        handler.handle(responseTotal, raw);
                    } else {
                        LOG.debug("Unrecognized event:\n{}", raw);
                    }
            }
            // Send raw event after cache has been updated
            if (api.isRawEvents()) {
                api.handleEvent(new RawGatewayEvent(api, responseTotal, raw));
            }
        } catch (ParsingException ex) {
            LOG.warn(
                    "Got an unexpected Json-parse error. Please redirect the following message to the devs:\n\tJDA {}\n\t{}\n\t{} -> {}",
                    JDAInfo.VERSION,
                    ex.getMessage(),
                    type,
                    content,
                    ex);
        } catch (Exception ex) {
            LOG.error(
                    "Got an unexpected error. Please redirect the following message to the devs:\n\tJDA {}\n\t{} -> {}",
                    JDAInfo.VERSION,
                    type,
                    content,
                    ex);
        }

        if (responseTotal % EventCache.TIMEOUT_AMOUNT == 0) {
            jda.getEventCache().timeout(responseTotal);
        }
    }

    public void onTextMessage(ByteBuf data) {
        boolean deduplicate = api.getSessionConfig().isStringDeduplication();
        handleEvent(DataObject.fromJson(data, deduplicate));
    }

    public void onBinaryMessage(ByteBuf binary) {
        boolean deduplicate = api.getSessionConfig().isStringDeduplication();
        if (encoding == GatewayEncoding.ETF) {
            handleEvent(DataObject.fromETF(binary, deduplicate));
        } else {
            handleEvent(DataObject.fromJson(binary, deduplicate));
        }
    }

    public void handleError(Throwable cause) {
        if (cause instanceof SocketTimeoutException || (cause.getCause() instanceof SocketTimeoutException)) {
            LOG.debug("Socket timed out");
        } else if (cause instanceof IOException || (cause.getCause() instanceof IOException)) {
            LOG.debug("Encountered I/O error", cause);
        } else {
            LOG.error("There was an error in the WebSocket connection. Trace: {}", traceMetadata, cause);
            api.handleEvent(new ExceptionEvent(api, cause, true));
        }
    }

    protected ConnectionRequest getNextAudioConnectRequest() {
        // Don't try to setup audio connections before JDA has finished loading.
        if (sessionId == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        ConnectionRequest request = null;
        synchronized (queuedAudioConnections) {
            for (ObjectIterator<Long2ObjectMap.Entry<ConnectionRequest>> it =
                            queuedAudioConnections.long2ObjectEntrySet().iterator();
                    it.hasNext(); ) {
                Long2ObjectMap.Entry<ConnectionRequest> entry = it.next();
                long guildId = entry.getLongKey();
                ConnectionRequest audioRequest = entry.getValue();
                if (audioRequest.getNextAttemptEpoch() < now) {
                    // Check if the guild is ready
                    Guild guild = api.getGuildById(guildId);
                    if (guild == null) {
                        // Not yet ready, check if the guild is known to this shard
                        GuildSetupController controller = api.getGuildSetupController();
                        if (!controller.isKnown(guildId)) {
                            // The guild is not tracked anymore
                            // -> we can't connect the audio channel
                            LOG.debug(
                                    "Removing audio connection request because the guild has been removed. {}",
                                    audioRequest);
                            it.remove();
                            continue;
                        }
                        continue;
                    }

                    ConnectionListener listener = guild.getAudioManager().getConnectionListener();
                    if (audioRequest.getStage() != ConnectionStage.DISCONNECT) {
                        // Check if we can connect to the target channel
                        AudioChannel channel = (AudioChannel) guild.getGuildChannelById(audioRequest.getChannelId());
                        if (channel == null) {
                            if (listener != null) {
                                listener.onStatusChange(ConnectionStatus.DISCONNECTED_CHANNEL_DELETED);
                            }
                            it.remove();
                            continue;
                        }

                        if (!guild.getSelfMember().hasPermission(channel, Permission.VOICE_CONNECT)) {
                            if (listener != null) {
                                listener.onStatusChange(ConnectionStatus.DISCONNECTED_LOST_PERMISSION);
                            }
                            it.remove();
                            continue;
                        }
                    }
                    // This will take the first result
                    if (request == null) {
                        request = audioRequest;
                    }
                }
            }
        }

        return request;
    }

    protected void locked(String comment, Runnable task) {
        try {
            MiscUtil.locked(queueLock, task);
        } catch (Exception e) {
            LOG.error(comment, e);
        }
    }

    protected <T> T locked(String comment, Supplier<T> task) {
        try {
            return MiscUtil.locked(queueLock, task);
        } catch (Exception e) {
            LOG.error(comment, e);
            return null;
        }
    }

    public void queueAudioReconnect(AudioChannel channel) {
        locked("There was an error queueing the audio reconnect", () -> {
            long guildId = channel.getGuild().getIdLong();
            ConnectionRequest request = queuedAudioConnections.get(guildId);

            if (request == null) {
                // If no request, then just reconnect
                request = new ConnectionRequest(channel, ConnectionStage.RECONNECT);
                queuedAudioConnections.put(guildId, request);
            } else {
                // If there is a request we change it to reconnect, no matter what it is
                request.setStage(ConnectionStage.RECONNECT);
            }
            // in all cases, update to this channel
            request.setChannel(channel);
        });
    }

    public void queueAudioConnect(AudioChannel channel) {
        locked("There was an error queueing the audio connect", () -> {
            long guildId = channel.getGuild().getIdLong();
            ConnectionRequest request = queuedAudioConnections.get(guildId);

            if (request == null) {
                // starting a whole new connection
                request = new ConnectionRequest(channel, ConnectionStage.CONNECT);
                queuedAudioConnections.put(guildId, request);
            } else if (request.getStage() == ConnectionStage.DISCONNECT) {
                // if planned to disconnect, we want to reconnect
                request.setStage(ConnectionStage.RECONNECT);
            }

            // in all cases, update to this channel
            request.setChannel(channel);
        });
    }

    public void queueAudioDisconnect(Guild guild) {
        locked("There was an error queueing the audio disconnect", () -> {
            long guildId = guild.getIdLong();
            ConnectionRequest request = queuedAudioConnections.get(guildId);

            if (request == null) {
                // If we do not have a request
                queuedAudioConnections.put(guildId, new ConnectionRequest(guild));
            } else {
                // If we have a request, change to DISCONNECT
                request.setStage(ConnectionStage.DISCONNECT);
            }
        });
    }

    public ConnectionRequest removeAudioConnection(long guildId) {
        // This will only be used by GuildDeleteHandler to ensure that
        // no further voice state updates are sent for this Guild
        return locked(
                "There was an error cleaning up audio connections for deleted guild",
                () -> queuedAudioConnections.remove(guildId));
    }

    public ConnectionRequest updateAudioConnection(long guildId, AudioChannel connectedChannel) {
        return locked(
                "There was an error updating the audio connection",
                () -> updateAudioConnection0(guildId, connectedChannel));
    }

    @SuppressWarnings("fallthrough")
    public ConnectionRequest updateAudioConnection0(long guildId, AudioChannel connectedChannel) {
        // Called by VoiceStateUpdateHandler when we receive a response from discord
        // about our request to CONNECT or DISCONNECT.
        // "stage" should never be RECONNECT here thus we don't check for that case
        ConnectionRequest request = queuedAudioConnections.get(guildId);

        if (request == null) {
            return null;
        }
        ConnectionStage requestStage = request.getStage();
        if (connectedChannel == null) {
            // If we got an update that DISCONNECT happened
            // -> If it was on RECONNECT we now switch to CONNECT
            // -> If it was on DISCONNECT we can now remove it
            // -> Otherwise we ignore it
            switch (requestStage) {
                case DISCONNECT:
                    return queuedAudioConnections.remove(guildId);
                case RECONNECT:
                    request.setStage(ConnectionStage.CONNECT);
                    request.setNextAttemptEpoch(System.currentTimeMillis());
                default:
                    return null;
            }
        } else if (requestStage == ConnectionStage.CONNECT) {
            // If the removeRequest was related to a channel that isn't the currently queued
            // request, then don't remove it.
            if (request.getChannelId() == connectedChannel.getIdLong()) {
                return queuedAudioConnections.remove(guildId);
            }
        }
        // If the channel is not the one we are looking for!
        return null;
    }

    protected void queuePayload(ByteBuf buf, boolean isBinary) {
        buf.retain();
        try {
            payloadProcessor.execute(new PayloadTask(this, buf, isBinary));
            Channel ch = this.channel;
            if (ch != null
                    && payloadQueue.size() >= PAYLOAD_HIGH_WATERMARK
                    && ch.config().isAutoRead()) {
                ch.config().setAutoRead(false);
            }
        } catch (RejectedExecutionException ex) {
            ReferenceCountUtil.safeRelease(buf);
        }
    }

    public Map<String, SocketHandler> getHandlers() {
        return handlers;
    }

    @SuppressWarnings("unchecked")
    public <T extends SocketHandler> T getHandler(String type) {
        try {
            return (T) handlers.get(type);
        } catch (ClassCastException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void setupHandlers() {
        SocketHandler.NOPHandler nopHandler = new SocketHandler.NOPHandler(api);
        handlers.put("APPLICATION_COMMAND_PERMISSIONS_UPDATE", new ApplicationCommandPermissionsUpdateHandler(api));
        handlers.put("AUTO_MODERATION_RULE_CREATE", new AutoModRuleHandler(api, "CREATE"));
        handlers.put("AUTO_MODERATION_RULE_UPDATE", new AutoModRuleHandler(api, "UPDATE"));
        handlers.put("AUTO_MODERATION_RULE_DELETE", new AutoModRuleHandler(api, "DELETE"));
        handlers.put("AUTO_MODERATION_ACTION_EXECUTION", new AutoModExecutionHandler(api));
        handlers.put("CHANNEL_CREATE", new ChannelCreateHandler(api));
        handlers.put("CHANNEL_DELETE", new ChannelDeleteHandler(api));
        handlers.put("CHANNEL_UPDATE", new ChannelUpdateHandler(api));
        handlers.put("ENTITLEMENT_CREATE", new EntitlementCreateHandler(api));
        handlers.put("ENTITLEMENT_UPDATE", new EntitlementUpdateHandler(api));
        handlers.put("ENTITLEMENT_DELETE", new EntitlementDeleteHandler(api));
        handlers.put("GUILD_AUDIT_LOG_ENTRY_CREATE", new GuildAuditLogEntryCreateHandler(api));
        handlers.put("GUILD_BAN_ADD", new GuildBanHandler(api, true));
        handlers.put("GUILD_BAN_REMOVE", new GuildBanHandler(api, false));
        handlers.put("GUILD_CREATE", new GuildCreateHandler(api));
        handlers.put("GUILD_DELETE", new GuildDeleteHandler(api));
        handlers.put("GUILD_EMOJIS_UPDATE", new GuildEmojisUpdateHandler(api));
        handlers.put("GUILD_SCHEDULED_EVENT_CREATE", new ScheduledEventCreateHandler(api));
        handlers.put("GUILD_SCHEDULED_EVENT_UPDATE", new ScheduledEventUpdateHandler(api));
        handlers.put("GUILD_SCHEDULED_EVENT_DELETE", new ScheduledEventDeleteHandler(api));
        handlers.put("GUILD_SCHEDULED_EVENT_USER_ADD", new ScheduledEventUserHandler(api, true));
        handlers.put("GUILD_SCHEDULED_EVENT_USER_REMOVE", new ScheduledEventUserHandler(api, false));
        handlers.put("GUILD_MEMBER_ADD", new GuildMemberAddHandler(api));
        handlers.put("GUILD_MEMBER_REMOVE", new GuildMemberRemoveHandler(api));
        handlers.put("GUILD_MEMBER_UPDATE", new GuildMemberUpdateHandler(api));
        handlers.put("GUILD_MEMBERS_CHUNK", new GuildMembersChunkHandler(api));
        handlers.put("GUILD_ROLE_CREATE", new GuildRoleCreateHandler(api));
        handlers.put("GUILD_ROLE_DELETE", new GuildRoleDeleteHandler(api));
        handlers.put("GUILD_ROLE_UPDATE", new GuildRoleUpdateHandler(api));
        handlers.put("GUILD_SYNC", new GuildSyncHandler(api));
        handlers.put("GUILD_STICKERS_UPDATE", new GuildStickersUpdateHandler(api));
        handlers.put("GUILD_SOUNDBOARD_SOUND_CREATE", new GuildSoundboardSoundCreateHandler(api));
        GuildSoundboardSoundUpdateHandler soundboardSoundUpdateHandler = new GuildSoundboardSoundUpdateHandler(api);
        handlers.put("GUILD_SOUNDBOARD_SOUND_UPDATE", soundboardSoundUpdateHandler);
        handlers.put(
                "GUILD_SOUNDBOARD_SOUNDS_UPDATE",
                new GuildSoundboardSoundsUpdateHandler(api, soundboardSoundUpdateHandler));
        handlers.put("GUILD_SOUNDBOARD_SOUND_DELETE", new GuildSoundboardSoundDeleteHandler(api));
        handlers.put("VOICE_CHANNEL_EFFECT_SEND", new VoiceChannelEffectSendHandler(api));
        handlers.put("GUILD_UPDATE", new GuildUpdateHandler(api));
        handlers.put("INTERACTION_CREATE", new InteractionCreateHandler(api));
        handlers.put("INVITE_CREATE", new InviteCreateHandler(api));
        handlers.put("INVITE_DELETE", new InviteDeleteHandler(api));
        handlers.put("MESSAGE_CREATE", new MessageCreateHandler(api));
        handlers.put("MESSAGE_DELETE", new MessageDeleteHandler(api));
        handlers.put("MESSAGE_DELETE_BULK", new MessageBulkDeleteHandler(api));
        handlers.put("MESSAGE_REACTION_ADD", new MessageReactionHandler(api, true));
        handlers.put("MESSAGE_REACTION_REMOVE", new MessageReactionHandler(api, false));
        handlers.put("MESSAGE_REACTION_REMOVE_ALL", new MessageReactionBulkRemoveHandler(api));
        handlers.put("MESSAGE_REACTION_REMOVE_EMOJI", new MessageReactionClearEmojiHandler(api));
        handlers.put("MESSAGE_POLL_VOTE_ADD", new MessagePollVoteHandler(api, true));
        handlers.put("MESSAGE_POLL_VOTE_REMOVE", new MessagePollVoteHandler(api, false));
        handlers.put("MESSAGE_UPDATE", new MessageUpdateHandler(api));
        handlers.put("PRESENCE_UPDATE", new PresenceUpdateHandler(api));
        handlers.put("READY", new ReadyHandler(api));
        handlers.put("STAGE_INSTANCE_CREATE", new StageInstanceCreateHandler(api));
        handlers.put("STAGE_INSTANCE_DELETE", new StageInstanceDeleteHandler(api));
        handlers.put("STAGE_INSTANCE_UPDATE", new StageInstanceUpdateHandler(api));
        handlers.put("THREAD_CREATE", new ThreadCreateHandler(api));
        handlers.put("THREAD_DELETE", new ThreadDeleteHandler(api));
        handlers.put("THREAD_LIST_SYNC", new ThreadListSyncHandler(api));
        handlers.put("THREAD_MEMBERS_UPDATE", new ThreadMembersUpdateHandler(api));
        handlers.put("THREAD_MEMBER_UPDATE", new ThreadMemberUpdateHandler(api));
        handlers.put("THREAD_UPDATE", new ThreadUpdateHandler(api));
        handlers.put("TYPING_START", new TypingStartHandler(api));
        handlers.put("USER_UPDATE", new UserUpdateHandler(api));
        handlers.put("VOICE_SERVER_UPDATE", new VoiceServerUpdateHandler(api));
        handlers.put("VOICE_STATE_UPDATE", new VoiceStateUpdateHandler(api));
        handlers.put("VOICE_CHANNEL_STATUS_UPDATE", new VoiceChannelStatusUpdateHandler(api));

        // Unused events
        handlers.put("CHANNEL_PINS_ACK", nopHandler);
        handlers.put("CHANNEL_PINS_UPDATE", nopHandler);
        handlers.put("GUILD_INTEGRATIONS_UPDATE", nopHandler);
        handlers.put("PRESENCES_REPLACE", nopHandler);
        handlers.put("WEBHOOKS_UPDATE", nopHandler);
    }

    private static class PayloadTask implements Runnable {
        private final WebSocketClient client;
        private final ByteBuf buffer;
        private final boolean isBinary;

        PayloadTask(WebSocketClient client, ByteBuf buffer, boolean isBinary) {
            this.client = client;
            this.buffer = buffer;
            this.isBinary = isBinary;
        }

        @Override
        public void run() {
            try {
                client.api.setContext();
                WS_THREAD.set(true);
                if (isBinary) {
                    client.onBinaryMessage(buffer);
                } else {
                    client.onTextMessage(buffer);
                }
            } catch (Throwable t) {
                LOG.error("Encountered exception while processing gateway payload", t);
            } finally {
                WS_THREAD.remove();
                release();
                Channel ch = client.channel;
                if (ch != null
                        && client.payloadQueue.size() <= PAYLOAD_LOW_WATERMARK
                        && !ch.config().isAutoRead()) {
                    ch.eventLoop().execute(() -> ch.config().setAutoRead(true));
                }
            }
        }

        public void release() {
            ReferenceCountUtil.safeRelease(buffer);
        }
    }

    private class GatewayWebSocketHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;
        private final CompletableFuture<Void> handshakeFuture;
        private boolean disconnectedHandled = false;

        public GatewayWebSocketHandler(WebSocketClientHandshaker handshaker, CompletableFuture<Void> handshakeFuture) {
            this.handshaker = handshaker;
            this.handshakeFuture = handshakeFuture;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            api.setContext();
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            api.setContext();
            if (!handshakeFuture.isDone()) {
                handshakeFuture.completeExceptionally(new ClosedChannelException());
            }
            if (!disconnectedHandled) {
                disconnectedHandled = true;
                executor.execute(() -> onDisconnected(
                        serverCloseFrame, clientCloseFrame, serverCloseFrame != null || clientCloseFrame == null));
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            api.setContext();
            if (!handshaker.isHandshakeComplete()) {
                if (msg instanceof FullHttpResponse response) {
                    if (!response.status().equals(HttpResponseStatus.SWITCHING_PROTOCOLS)) {
                        String body = response.content().toString(StandardCharsets.UTF_8);
                        LOG.error(
                                "WebSocket handshake failed with status {}: {}\nHeaders: {}",
                                response.status(),
                                body,
                                response.headers());
                    }
                    handshaker.finishHandshake(ctx.channel(), response);
                    ChannelHandler httpAggregator = ctx.pipeline().get("http-aggregator");
                    if (httpAggregator != null) {
                        ctx.pipeline().remove(httpAggregator);
                    }
                    int maxPayload = api.getNettyConfig().getMaxFramePayloadLength();
                    if (ctx.pipeline().get("ws-decoder") != null) {
                        ctx.pipeline()
                                .addAfter("ws-decoder", "ws-aggregator", new WebSocketFrameAggregator(maxPayload));
                    } else {
                        ctx.pipeline()
                                .addBefore("ws-handler", "ws-aggregator", new WebSocketFrameAggregator(maxPayload));
                    }
                    handshakeFuture.complete(null);
                    onConnected(response.headers());
                    return;
                }
            }
            if (msg instanceof ByteBuf buf) {
                queuePayload(buf, true);
            } else if (msg instanceof WebSocketFrame frame) {
                switch (frame) {
                    case TextWebSocketFrame textFrame -> queuePayload(textFrame.content(), false);
                    case BinaryWebSocketFrame binaryFrame -> queuePayload(binaryFrame.content(), true);
                    case CloseWebSocketFrame closeFrame -> {
                        int rawCode = closeFrame.statusCode();
                        String reason = closeFrame.reasonText();
                        serverCloseFrame = new CloseFrameInfo(rawCode, reason);
                        if (!disconnectedHandled) {
                            disconnectedHandled = true;
                            executor.execute(() -> onDisconnected(serverCloseFrame, clientCloseFrame, true));
                        }
                        ctx.close();
                    }
                    case PingWebSocketFrame pingFrame ->
                        ctx.writeAndFlush(
                                new PongWebSocketFrame(pingFrame.content().retain()));
                    case PongWebSocketFrame pongFrame -> {
                        missedHeartbeats = 0;
                        api.setGatewayPing(System.currentTimeMillis() - heartbeatStartTime);
                    }
                    default -> {}
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            api.setContext();
            if (!handshakeFuture.isDone()) {
                handshakeFuture.completeExceptionally(cause);
            }
            handleError(cause);
            ctx.close();
        }
    }

    protected abstract class ConnectNode implements SessionController.SessionConnectNode {
        @Nonnull
        @Override
        public JDA getJDA() {
            return api;
        }

        @Nonnull
        @Override
        public JDA.ShardInfo getShardInfo() {
            return api.getShardInfo();
        }
    }

    protected class StartingNode extends ConnectNode {
        @Override
        public boolean isReconnect() {
            return false;
        }

        @Override
        public void run(boolean isLast) throws InterruptedException {
            if (shutdown) {
                return;
            }
            setupSendingThread();
            connect();
            if (isLast) {
                return;
            }
            try {
                api.awaitStatus(JDA.Status.LOADING_SUBSYSTEMS, JDA.Status.RECONNECT_QUEUED);
            } catch (IllegalStateException ex) {
                close();
                LOG.debug("Shutdown while trying to connect");
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash("C", getJDA());
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof StartingNode node)) {
                return false;
            }
            return node.getJDA().equals(getJDA());
        }
    }

    protected class ReconnectNode extends ConnectNode {
        @Override
        public boolean isReconnect() {
            return true;
        }

        @Override
        public void run(boolean isLast) throws InterruptedException {
            if (shutdown) {
                return;
            }
            reconnect(true);
            if (isLast) {
                return;
            }
            try {
                api.awaitStatus(JDA.Status.LOADING_SUBSYSTEMS, JDA.Status.RECONNECT_QUEUED);
            } catch (IllegalStateException ex) {
                close();
                LOG.debug("Shutdown while trying to reconnect");
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash("R", getJDA());
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof ReconnectNode node)) {
                return false;
            }
            return node.getJDA().equals(getJDA());
        }
    }
}
