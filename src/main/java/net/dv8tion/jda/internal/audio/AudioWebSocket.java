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

package net.dv8tion.jda.internal.audio;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import net.dv8tion.jda.api.JDAInfo;
import net.dv8tion.jda.api.audio.SpeakingMode;
import net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.ExceptionEvent;
import net.dv8tion.jda.api.utils.MiscUtil;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.managers.AudioManagerImpl;
import net.dv8tion.jda.internal.requests.CloseFrameInfo;
import net.dv8tion.jda.internal.utils.IOUtil;
import net.dv8tion.jda.internal.utils.JDALogger;
import net.dv8tion.jda.internal.utils.NettyUtils;
import net.dv8tion.jda.internal.utils.SerializationUtil;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.concurrent.*;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

class AudioWebSocket implements DaveProtocolCallbacks {
    public static final Logger LOG = JDALogger.getLog(AudioWebSocket.class);
    public static final int DISCORD_SECRET_KEY_LENGTH = 32;
    private static final byte[] UDP_KEEP_ALIVE = {(byte) 0xC9, 0, 0, 0, 0, 0, 0, 0, 0};
    private final AudioConnection audioConnection;
    private final ConnectionListener listener;
    private final ScheduledExecutorService keepAlivePool;
    private final Guild guild;
    private final String sessionId;
    private final String token;
    private final String wssEndpoint;
    public volatile Channel channel;
    protected volatile AudioEncryption encryption;
    protected volatile CryptoAdapter crypto;
    protected EventLoopGroup group;
    protected boolean ownsEventLoopGroup;
    protected volatile CloseFrameInfo serverCloseFrame;
    protected volatile CloseFrameInfo clientCloseFrame;
    private DaveSession daveSession;
    private volatile ConnectionStatus connectionStatus = ConnectionStatus.NOT_CONNECTED;
    private boolean ready = false;
    private boolean reconnecting = false;
    private boolean shouldReconnect;
    private int ssrc;
    private byte[] secretKey;
    private Future<?> keepAliveHandle;
    private InetSocketAddress address;
    private long sequence;

    private volatile boolean shutdown = false;

    protected AudioWebSocket(
            AudioConnection audioConnection,
            ConnectionListener listener,
            String endpoint,
            Guild guild,
            String sessionId,
            String token,
            boolean shouldReconnect) {
        this.audioConnection = audioConnection;
        this.listener = listener;
        this.guild = guild;
        this.sessionId = sessionId;
        this.token = token;
        this.shouldReconnect = shouldReconnect;

        this.keepAlivePool = getJDA().getAudioLifeCyclePool();

        // Add the version query parameter
        String url = IOUtil.addQuery(endpoint, "v", JDAInfo.AUDIO_GATEWAY_VERSION);
        // Append the Secure Websocket scheme so that our websocket library knows how to
        // connect
        if (url.startsWith("wss://")) {
            wssEndpoint = url;
        } else {
            wssEndpoint = "wss://" + url;
        }

        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot create a audio websocket connection using a null/empty sessionId!");
        }
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Cannot create a audio websocket connection using a null/empty token!");
        }
    }

    private static ByteBuffer toByteBuffer(ByteBuf buf) {
        if (buf.nioBufferCount() == 1) {
            return buf.nioBuffer();
        }
        // Consolidate multiple NIO buffers into a single contiguous ByteBuffer
        // without leaking a copy() ByteBuf
        ByteBuffer[] nioBuffers = buf.nioBuffers();
        int totalLen = 0;
        for (ByteBuffer b : nioBuffers) {
            totalLen += b.remaining();
        }
        ByteBuffer combined = ByteBuffer.allocateDirect(totalLen);
        for (ByteBuffer b : nioBuffers) {
            combined.put(b);
        }
        combined.flip();
        return combined;
    }

    /* Used by AudioConnection */

    protected void send(DataObject message) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("<- {}", message);
        }
        if (channel != null && channel.isActive()) {
            ByteBuf buf = channel.alloc().buffer();
            SerializationUtil.writeJson(buf, message.toMap());
            channel.writeAndFlush(new TextWebSocketFrame(buf));
        }
    }

    protected void send(String message) {
        LOG.trace("<- {}", message);
        if (channel != null && channel.isActive()) {
            ByteBuf buf = ByteBufUtil.writeUtf8(channel.alloc(), message);
            channel.writeAndFlush(new TextWebSocketFrame(buf));
        }
    }

    protected void send(int op, Object data) {
        send(DataObject.empty().put("op", op).put("d", data));
    }

    protected void clearCloseFrames() {
        serverCloseFrame = null;
        clientCloseFrame = null;
    }

    protected void startConnection() {
        clearCloseFrames();
        if (!reconnecting && channel != null && channel.isActive()) {
            throw new IllegalStateException(
                    "Somehow, someway, this AudioWebSocket has already attempted to start a connection!");
        }

        try {
            URI uri = URI.create(wssEndpoint);
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

            NettyConfig nettyConfig = getJDA().getNettyConfig();
            this.group = getJDA().getAudioEventLoopGroup();
            this.ownsEventLoopGroup = false;

            HttpHeaders customHeaders = new DefaultHttpHeaders();
            customHeaders.add(
                    HttpHeaderNames.USER_AGENT, getJDA().getRequester().getUserAgent());
            WebSocketClientHandshaker handshaker =
                    NettyUtils.newHandshaker(uri, customHeaders, nettyConfig.getMaxFramePayloadLength());

            changeStatus(ConnectionStatus.CONNECTING_AWAITING_WEBSOCKET_CONNECT);

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
                    p.addLast("ws-handler", new AudioWebSocketHandler(handshaker));
                }
            });

            ChannelFuture connectFuture = b.connect(host, port);
            this.channel = connectFuture.channel();
            connectFuture.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    LOG.warn(
                            "Failed to establish websocket connection to {}: {}\n"
                                    + "Closing connection and attempting to reconnect.",
                            wssEndpoint,
                            future.cause().getMessage());
                    close(ConnectionStatus.ERROR_WEBSOCKET_UNABLE_TO_CONNECT);
                }
            });
        } catch (Exception e) {
            LOG.warn(
                    "Encountered exception while attempting to connect to {}: {}\n"
                            + "Closing connection and attempting to reconnect.",
                    wssEndpoint,
                    e.getMessage());
            this.close(ConnectionStatus.ERROR_WEBSOCKET_UNABLE_TO_CONNECT);
        }
    }

    protected void close(ConnectionStatus closeStatus) {
        // Makes sure we don't run this method again
        // after the socket.close(1000) call fires onDisconnect
        if (shutdown) {
            return;
        }
        locked((manager) -> {
            if (shutdown) {
                return;
            }
            ConnectionStatus status = closeStatus;
            ready = false;
            shutdown = true;
            stopKeepAlive();

            if (audioConnection.udpChannel != null) {
                audioConnection.udpChannel.close();
            }
            if (channel != null && channel.isActive()) {
                clientCloseFrame = new CloseFrameInfo(1000, null);
                channel.writeAndFlush(new CloseWebSocketFrame(1000, null)).addListener(ChannelFutureListener.CLOSE);
            }
            clearCloseFrames();
            if (ownsEventLoopGroup && group != null) {
                group.shutdownGracefully();
            }

            audioConnection.shutdown();
            daveSession.destroy();

            AudioChannel disconnectedChannel = manager.getConnectedChannel();
            manager.setAudioConnection(null);

            // Verify that it is actually a lost of connection
            // and not due the connected channel being deleted.
            JDAImpl api = getJDA();
            if (status == ConnectionStatus.DISCONNECTED_KICKED_FROM_CHANNEL
                    && (!api.getClient().isSession() || !api.getClient().isConnected())) {
                LOG.debug("Connection was closed due to session invalidate!");
                status = ConnectionStatus.ERROR_CANNOT_RESUME;
            } else if (status == ConnectionStatus.ERROR_LOST_CONNECTION
                    || status == ConnectionStatus.DISCONNECTED_KICKED_FROM_CHANNEL) {
                // Get guild from JDA, don't use [guild] field to make sure
                // that we don't have a problem of an out of date guild stored in [guild]
                // during a possible mWS invalidate.
                Guild connGuild = api.getGuildById(guild.getIdLong());
                if (connGuild != null) {
                    AudioChannel channel = (AudioChannel) connGuild.getGuildChannelById(
                            audioConnection.getChannel().getIdLong());
                    if (channel == null) {
                        status = ConnectionStatus.DISCONNECTED_CHANNEL_DELETED;
                    }
                }
            }

            changeStatus(status);

            // decide if we reconnect.
            if (shouldReconnect
                    // indicated that the connection was purposely closed. don't reconnect.
                    && status.shouldReconnect()
                    // Already handled.
                    && status != ConnectionStatus.AUDIO_REGION_CHANGE) {
                if (disconnectedChannel == null) {
                    LOG.debug("Cannot reconnect due to null audio channel");
                    return;
                }
                api.getDirectAudioController().reconnect(disconnectedChannel);
            } else if (status == ConnectionStatus.DISCONNECTED_REMOVED_FROM_GUILD) {
                // Remove audio manager as we are no longer in the guild
                api.getAudioManagersView().remove(guild.getIdLong());
            } else if (status != ConnectionStatus.AUDIO_REGION_CHANGE
                    && status != ConnectionStatus.DISCONNECTED_KICKED_FROM_CHANNEL) {
                api.getDirectAudioController().disconnect(guild);
            }
        });
    }

    protected void changeStatus(ConnectionStatus newStatus) {
        connectionStatus = newStatus;
        listener.onStatusChange(newStatus);
    }

    protected void setAutoReconnect(boolean shouldReconnect) {
        this.shouldReconnect = shouldReconnect;
    }

    protected ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    protected InetSocketAddress getAddress() {
        return address;
    }

    protected byte[] getSecretKey() {
        return secretKey;
    }

    protected int getSSRC() {
        return ssrc;
    }

    protected boolean isReady() {
        return ready;
    }

    /* TCP Listeners */

    public void onConnected(HttpHeaders headers) {
        if (shutdown) {
            if (channel != null && channel.isActive()) {
                channel.writeAndFlush(new CloseWebSocketFrame(1000, null)).addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }

        if (reconnecting) {
            resume();
        } else {
            identify();
        }
        changeStatus(ConnectionStatus.CONNECTING_AWAITING_AUTHENTICATION);
        audioConnection.prepareReady();
        reconnecting = false;
    }

    public void onTextMessage(ByteBuf data) {
        try {
            boolean deduplicate = getJDA().getSessionConfig().isStringDeduplication();
            handleEvent(DataObject.fromJson(data, deduplicate));
        } catch (Exception ex) {
            LOG.error("Encountered exception trying to handle an event message", ex);
        }
    }

    public void onDisconnected(
            CloseFrameInfo serverCloseFrame, CloseFrameInfo clientCloseFrame, boolean closedByServer) {
        if (shutdown) {
            return;
        }
        Thread.ofVirtual()
                .name(guild.getId() + " AudioWS-DisconnectThread")
                .uncaughtExceptionHandler((thread, throwable) -> {
                    LOG.error("Uncaught exception in AudioWS disconnect-thread", throwable);
                    JDAImpl api = getJDA();
                    api.handleEvent(new ExceptionEvent(api, throwable, true));
                })
                .start(() -> handleDisconnect(serverCloseFrame, clientCloseFrame, closedByServer));
    }

    private void handleDisconnect(
            CloseFrameInfo serverCloseFrame, CloseFrameInfo clientCloseFrame, boolean closedByServer) {
        if (shutdown) {
            return;
        }
        LOG.debug("The Audio connection was closed!\nBy remote? {}", closedByServer);
        if (serverCloseFrame != null) {
            LOG.debug("Reason: {}\nClose code: {}", serverCloseFrame.reason(), serverCloseFrame.statusCode());
            int code = serverCloseFrame.statusCode();
            VoiceCode.Close closeCode = VoiceCode.Close.from(code);
            switch (closeCode) {
                case RATE_LIMIT_EXCEEDED:
                case SERVER_NOT_FOUND:
                case SERVER_CRASH:
                case INVALID_SESSION:
                    this.close(ConnectionStatus.ERROR_CANNOT_RESUME);
                    break;
                case AUTHENTICATION_FAILED:
                    this.close(ConnectionStatus.DISCONNECTED_AUTHENTICATION_FAILURE);
                    break;
                case DISCONNECTED_ALL_CLIENTS:
                case DISCONNECTED:
                    this.close(ConnectionStatus.DISCONNECTED_KICKED_FROM_CHANNEL);
                    break;
                default:
                    this.reconnect();
            }
            return;
        }
        if (clientCloseFrame != null) {
            LOG.debug("ClientReason: {}\nClientCode: {}", clientCloseFrame.reason(), clientCloseFrame.statusCode());
            if (clientCloseFrame.statusCode() != 1000) {
                // unexpected close -> error -> attempt resume
                this.reconnect();
                return;
            }
        }
        this.close(ConnectionStatus.NOT_CONNECTED);
    }

    public void handleCallbackError(Throwable cause) {
        LOG.error("There was some audio websocket error", cause);
        JDAImpl api = getJDA();
        api.handleEvent(new ExceptionEvent(api, cause, true));
    }

    /* Dave Protocol */

    public DaveSession getDaveSession() {
        return daveSession;
    }

    void setDaveSession(DaveSession daveSession) {
        this.daveSession = daveSession;
    }

    private void sendBinary(int opcode, ByteBuffer payload) {
        if (channel != null && channel.isActive()) {
            ByteBuf buffer = channel.alloc().buffer(1 + payload.remaining());
            buffer.writeByte(opcode);
            buffer.writeBytes(payload);
            channel.writeAndFlush(new BinaryWebSocketFrame(buffer));
        }
    }

    private void sendBinary(int opcode, ByteBuf payload) {
        if (channel != null && channel.isActive()) {
            ByteBuf buffer = channel.alloc().buffer(1 + payload.readableBytes());
            buffer.writeByte(opcode);
            buffer.writeBytes(payload);
            channel.writeAndFlush(new BinaryWebSocketFrame(buffer));
        }
    }

    public void onBinaryMessage(ByteBuf message) {
        this.sequence = message.readUnsignedShort();
        int opcode = message.readUnsignedByte();

        switch (opcode) {
            case VoiceCode.MLS_EXTERNAL_SENDER: {
                LOG.trace("-> MLS_EXTERNAL_SENDER");
                daveSession.onDaveProtocolMLSExternalSenderPackage(toByteBuffer(message));
                break;
            }
            case VoiceCode.MLS_PROPOSALS: {
                LOG.trace("-> MLS_PROPOSALS");
                daveSession.onMLSProposals(toByteBuffer(message));
                break;
            }
            case VoiceCode.MLS_ANNOUNCE_COMMIT_TRANSITION: {
                LOG.trace("-> MLS_ANNOUNCE_COMMIT_TRANSITION");
                int transitionId = message.readUnsignedShort();
                daveSession.onMLSPrepareCommitTransition(transitionId, toByteBuffer(message));
                break;
            }
            case VoiceCode.MLS_WELCOME: {
                LOG.trace("-> MLS_WELCOME");
                int transitionId = message.readUnsignedShort();
                daveSession.onMLSWelcome(transitionId, toByteBuffer(message));
                break;
            }
            default:
                LOG.trace("-> UNKNOWN OP {}", opcode);
        }
    }

    @Override
    public void sendMLSKeyPackage(@Nonnull ByteBuffer mlsKeyPackage) {
        LOG.trace("<- MLS_KEY_PACKAGE");
        sendBinary(VoiceCode.MLS_KEY_PACKAGE, mlsKeyPackage);
    }

    @Override
    public void sendDaveProtocolReadyForTransition(int transitionId) {
        LOG.trace("<- DAVE_TRANSITION_READY");
        send(VoiceCode.DAVE_TRANSITION_READY, DataObject.empty().put("transition_id", transitionId));
    }

    @Override
    public void sendMLSCommitWelcome(@Nonnull ByteBuffer commitWelcomeMessage) {
        LOG.trace("<- MLS_COMMIT_WELCOME");
        sendBinary(VoiceCode.MLS_COMMIT_WELCOME, commitWelcomeMessage);
    }

    @Override
    public void sendMLSInvalidCommitWelcome(int transitionId) {
        LOG.trace("<- MLS_INVALID_COMMIT_WELCOME");
        send(VoiceCode.MLS_INVALID_COMMIT_WELCOME, DataObject.empty().put("transition_id", transitionId));
    }

    /* Internals */

    private void handleEvent(DataObject contentAll) {
        int opCode = contentAll.getInt("op");
        sequence = contentAll.getLong("seq", sequence);

        switch (opCode) {
            case VoiceCode.HELLO: {
                LOG.trace("-> HELLO {}", contentAll);
                DataObject payload = contentAll.getObject("d");
                int interval = payload.getInt("heartbeat_interval");
                stopKeepAlive();
                setupKeepAlive(interval);
                daveSession.initialize();
                break;
            }
            case VoiceCode.READY: {
                LOG.trace("-> READY {}", contentAll);
                DataObject content = contentAll.getObject("d");
                ssrc = content.getInt("ssrc");
                int port = content.getInt("port");
                String ip = content.getString("ip");
                DataArray modes = content.getArray("modes");
                encryption = CryptoAdapter.negotiate(AudioEncryption.fromArray(modes));
                if (encryption == null) {
                    close(ConnectionStatus.ERROR_UNSUPPORTED_ENCRYPTION_MODES);
                    LOG.error("None of the provided encryption modes are supported: {}", modes);
                    return;
                } else {
                    LOG.debug("Using encryption mode " + encryption.getKey());
                }

                // Find our external IP and Port using Discord
                InetSocketAddress externalIpAndPort;

                changeStatus(ConnectionStatus.CONNECTING_ATTEMPTING_UDP_DISCOVERY);
                int tries = 0;
                do {
                    externalIpAndPort = handleUdpDiscovery(new InetSocketAddress(ip, port), ssrc);
                    tries++;
                    if (externalIpAndPort == null && tries > 5) {
                        close(ConnectionStatus.ERROR_UDP_UNABLE_TO_CONNECT);
                        return;
                    }
                } while (externalIpAndPort == null);

                daveSession.assignSsrcToCodec(DaveSession.Codec.OPUS, ssrc);

                DataObject object = DataObject.empty()
                        .put("protocol", "udp")
                        .put(
                                "data",
                                DataObject.empty()
                                        .put("address", externalIpAndPort.getHostString())
                                        .put("port", externalIpAndPort.getPort())
                                        .put("mode", encryption.getKey())); // Discord requires encryption
                send(VoiceCode.SELECT_PROTOCOL, object);
                changeStatus(ConnectionStatus.CONNECTING_AWAITING_READY);
                break;
            }
            case VoiceCode.RESUMED: {
                LOG.trace("-> RESUMED {}", contentAll);
                LOG.debug("Successfully resumed session!");
                changeStatus(ConnectionStatus.CONNECTED);
                ready = true;
                MiscUtil.locked(audioConnection.readyLock, audioConnection.readyCondvar::signalAll);
                break;
            }
            case VoiceCode.SESSION_DESCRIPTION: {
                LOG.trace("-> SESSION_DESCRIPTION {}", contentAll);
                send(
                        VoiceCode.USER_SPEAKING_UPDATE, // required to receive audio?
                        DataObject.empty().put("delay", 0).put("speaking", 0).put("ssrc", ssrc));
                // secret_key is an array of 32 ints that are less than 256, so they are bytes.
                DataArray keyArray = contentAll.getObject("d").getArray("secret_key");

                secretKey = new byte[DISCORD_SECRET_KEY_LENGTH];
                for (int i = 0; i < keyArray.length(); i++) {
                    secretKey[i] = (byte) keyArray.getInt(i);
                }

                crypto = new DaveCryptoAdapter(CryptoAdapter.getAdapter(encryption, secretKey), daveSession, ssrc);
                daveSession.onSelectProtocolAck(contentAll.getObject("d").getInt("dave_protocol_version"));

                LOG.debug("Audio connection has finished connecting!");
                ready = true;
                MiscUtil.locked(audioConnection.readyLock, audioConnection.readyCondvar::signalAll);
                changeStatus(ConnectionStatus.CONNECTED);
                break;
            }
            case VoiceCode.HEARTBEAT: {
                LOG.trace("-> HEARTBEAT {}", contentAll);
                send(VoiceCode.HEARTBEAT, System.currentTimeMillis());
                break;
            }
            case VoiceCode.HEARTBEAT_ACK: {
                LOG.trace("-> HEARTBEAT_ACK {}", contentAll);
                long ping =
                        System.currentTimeMillis() - contentAll.getObject("d").getLong("t");
                listener.onPing(ping);
                break;
            }
            case VoiceCode.USER_SPEAKING_UPDATE: {
                LOG.trace("-> USER_SPEAKING_UPDATE {}", contentAll);
                DataObject content = contentAll.getObject("d");
                int ssrc = content.getInt("ssrc");
                long userId = content.getUnsignedLong("user_id");
                audioConnection.updateUserSSRC(ssrc, userId);
                daveSession.addUser(userId);

                EnumSet<SpeakingMode> speaking = SpeakingMode.getModes(content.getInt("speaking"));
                User user = getUser(userId);
                if (user == null) {
                    // more relevant for audio connection
                    LOG.trace("Got an Audio USER_SPEAKING_UPDATE for a non-existent User. JSON: {}", contentAll);
                    listener.onUserSpeakingModeUpdate(UserSnowflake.fromId(userId), speaking);
                } else {
                    listener.onUserSpeakingModeUpdate((UserSnowflake) user, speaking);
                }

                break;
            }
            case VoiceCode.USER_BULK_CONNECT: {
                LOG.trace("-> USER_BULK_CONNECT {}", contentAll);
                DataObject payload = contentAll.getObject("d");
                DataArray userIds = payload.getArray("user_ids");
                for (int i = 0; i < userIds.length(); i++) {
                    long userId = userIds.getUnsignedLong(i);
                    daveSession.addUser(userId);
                }
                break;
            }
            case VoiceCode.USER_DISCONNECT: {
                LOG.trace("-> USER_DISCONNECT {}", contentAll);
                DataObject payload = contentAll.getObject("d");
                long userId = payload.getUnsignedLong("user_id");
                audioConnection.removeUserSSRC(userId);
                daveSession.removeUser(userId);
                break;
            }
            case VoiceCode.DAVE_PREPARE_TRANSITION: {
                LOG.trace("-> DAVE_PREPARE_TRANSITION {}", contentAll);
                DataObject payload = contentAll.getObject("d");
                daveSession.onDaveProtocolPrepareTransition(
                        payload.getInt("transition_id"), payload.getInt("protocol_version"));
                break;
            }
            case VoiceCode.DAVE_EXECUTE_TRANSITION: {
                LOG.trace("-> DAVE_EXECUTE_TRANSITION {}", contentAll);
                DataObject payload = contentAll.getObject("d");
                daveSession.onDaveProtocolExecuteTransition(payload.getInt("transition_id"));
                break;
            }
            case VoiceCode.DAVE_PREPARE_EPOCH: {
                LOG.trace("-> DAVE_PREPARE_EPOCH {}", contentAll);
                DataObject payload = contentAll.getObject("d");
                daveSession.onDaveProtocolPrepareEpoch(
                        payload.getUnsignedLong("epoch"), payload.getInt("protocol_version"));
                break;
            }

            default: {
                LOG.trace("-> UNKNOWN OP {}: {}", opCode, contentAll);
                // undocumented / unused
                break;
            }
        }
    }

    private void identify() {
        sequence = 0;
        int maxDaveProtocolVersion = daveSession.getMaxProtocolVersion();
        if (maxDaveProtocolVersion == 0) {
            LOG.warn("Maximum Dave Protocol Version is 0. "
                    + "This means your connection does not properly support encryption. "
                    + "This will fail to work in the future.");
        }

        DataObject connectObj = DataObject.empty()
                .put("server_id", guild.getId())
                .put("user_id", getJDA().getSelfUser().getId())
                .put("session_id", sessionId)
                .put("token", token)
                .put("max_dave_protocol_version", maxDaveProtocolVersion);
        send(VoiceCode.IDENTIFY, connectObj);
    }

    private void resume() {
        LOG.debug("Sending resume payload...");
        DataObject resumeObj = DataObject.empty()
                .put("server_id", guild.getId())
                .put("session_id", sessionId)
                .put("token", token)
                .put("seq_ack", sequence);
        send(VoiceCode.RESUME, resumeObj);
    }

    private JDAImpl getJDA() {
        return audioConnection.getJDA();
    }

    private void locked(Consumer<AudioManagerImpl> consumer) {
        AudioManagerImpl manager = (AudioManagerImpl) guild.getAudioManager();
        MiscUtil.locked(manager.CONNECTION_LOCK, () -> consumer.accept(manager));
    }

    private void reconnect() {
        if (shutdown) {
            return;
        }
        locked((unused) -> {
            if (shutdown) {
                return;
            }
            ready = false;
            reconnecting = true;
            changeStatus(ConnectionStatus.ERROR_LOST_CONNECTION);
            startConnection();
        });
    }

    private InetSocketAddress handleUdpDiscovery(InetSocketAddress address, int ssrc) {
        // We will now send a packet to discord to punch a port hole in the NAT wall.
        // This is called UDP hole punching.
        try {
            // First close existing channel from possible previous attempts
            if (audioConnection.udpChannel != null) {
                audioConnection.udpChannel.close();
            }

            NettyConfig nettyConfig = getJDA().getNettyConfig();
            AudioDatagramHandler datagramHandler = new AudioDatagramHandler(audioConnection);
            CompletableFuture<InetSocketAddress> discoveryFuture = new CompletableFuture<>();
            datagramHandler.setDiscoveryFuture(discoveryFuture);

            Bootstrap udpBootstrap = new Bootstrap();
            udpBootstrap
                    .group(this.group)
                    .channel(NettyUtils.getDatagramChannelClass(nettyConfig.isUseNativeTransport()))
                    .handler(datagramHandler);

            ChannelFuture bindFuture = udpBootstrap.bind(0).sync();
            DatagramChannel udpChannel = (DatagramChannel) bindFuture.channel();
            audioConnection.udpChannel = udpChannel;

            // Create a byte buffer of length 74 containing our ssrc.
            ByteBuf buffer = udpChannel.alloc().buffer(74);
            buffer.writeShort(1); // 1 = send (receive will be 2)
            buffer.writeShort(70); // length = 70 bytes (required)
            buffer.writeInt(ssrc);
            buffer.writeZero(66);

            udpChannel.writeAndFlush(new DatagramPacket(buffer, address));

            InetSocketAddress ourAddress = discoveryFuture.get(2, TimeUnit.SECONDS);
            datagramHandler.setDiscoveryFuture(null);
            this.address = address;
            return ourAddress;
        } catch (Exception e) {
            LOG.error("Failed to perform UDP hole punching", e);
            return null;
        }
    }

    private void stopKeepAlive() {
        if (keepAliveHandle != null) {
            keepAliveHandle.cancel(true);
        }
        keepAliveHandle = null;
    }

    private void setupKeepAlive(int keepAliveInterval) {
        if (keepAliveHandle != null) {
            LOG.error("Setting up a KeepAlive runnable while the previous one seems to still be active!!");
        }

        Runnable keepAliveRunnable = () -> {
            getJDA().setContext();
            if (channel != null && channel.isActive()) // TCP keep-alive
            {
                DataObject packet = DataObject.empty().put("t", System.currentTimeMillis());
                if (sequence > 0) {
                    packet.put("seq_ack", sequence);
                }
                send(VoiceCode.HEARTBEAT, packet);
            }
            if (audioConnection.udpChannel != null && audioConnection.udpChannel.isActive()) // UDP keep-alive
            {
                ByteBuf keepAliveBuf = Unpooled.wrappedBuffer(UDP_KEEP_ALIVE);
                audioConnection.udpChannel.writeAndFlush(new DatagramPacket(keepAliveBuf, address));
            }
        };

        try {
            keepAliveHandle =
                    keepAlivePool.scheduleAtFixedRate(keepAliveRunnable, 0, keepAliveInterval, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        } // ignored because this is probably caused due to a race condition
        // related to the threadpool shutdown.
    }

    private User getUser(long userId) {
        return getJDA().getUserById(userId);
    }

    private class AudioWebSocketHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;
        private boolean disconnectedHandled = false;

        public AudioWebSocketHandler(WebSocketClientHandshaker handshaker) {
            this.handshaker = handshaker;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            getJDA().setContext();
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            getJDA().setContext();
            if (!disconnectedHandled) {
                disconnectedHandled = true;
                onDisconnected(
                        serverCloseFrame, clientCloseFrame, serverCloseFrame != null || clientCloseFrame == null);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            getJDA().setContext();
            if (!handshaker.isHandshakeComplete()) {
                if (msg instanceof FullHttpResponse response) {
                    handshaker.finishHandshake(ctx.channel(), response);
                    ChannelHandler httpAggregator = ctx.pipeline().get("http-aggregator");
                    if (httpAggregator != null) {
                        ctx.pipeline().remove(httpAggregator);
                    }
                    int maxPayload = getJDA().getNettyConfig().getMaxFramePayloadLength();
                    if (ctx.pipeline().get("ws-decoder") != null) {
                        ctx.pipeline()
                                .addAfter("ws-decoder", "ws-aggregator", new WebSocketFrameAggregator(maxPayload));
                    } else {
                        ctx.pipeline()
                                .addBefore("ws-handler", "ws-aggregator", new WebSocketFrameAggregator(maxPayload));
                    }
                    onConnected(response.headers());
                    return;
                }
            }

            if (msg instanceof WebSocketFrame frame) {
                switch (frame) {
                    case TextWebSocketFrame textFrame -> onTextMessage(textFrame.content());
                    case BinaryWebSocketFrame binaryFrame -> onBinaryMessage(binaryFrame.content());
                    case CloseWebSocketFrame closeFrame -> {
                        int rawCode = closeFrame.statusCode();
                        String reason = closeFrame.reasonText();
                        serverCloseFrame = new CloseFrameInfo(rawCode, reason);
                        if (!disconnectedHandled) {
                            disconnectedHandled = true;
                            onDisconnected(serverCloseFrame, clientCloseFrame, true);
                        }
                        ctx.close();
                    }
                    case PingWebSocketFrame pingFrame ->
                        ctx.writeAndFlush(
                                new PongWebSocketFrame(pingFrame.content().retain()));
                    default -> {}
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            getJDA().setContext();
            handleCallbackError(cause);
            ctx.close();
        }
    }
}
