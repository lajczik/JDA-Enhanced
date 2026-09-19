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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker13;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import net.dv8tion.jda.api.utils.NettyConfig;
import org.slf4j.Logger;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.net.URI;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

public class NettyUtils {
    private static final Logger log = JDALogger.getLog(NettyUtils.class);

    private static final boolean EPOLL_AVAILABLE;
    private static final boolean KQUEUE_AVAILABLE;

    static {
        boolean epoll = false;
        try {
            Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
            epoll = (boolean) epollClass.getMethod("isAvailable").invoke(null);
            if (epoll) {
                log.debug("Netty Epoll native transport is available.");
            } else {
                Throwable cause =
                        (Throwable) epollClass.getMethod("unavailabilityCause").invoke(null);
                log.debug(
                        "Netty Epoll native transport is not available: {}",
                        cause != null ? cause.getMessage() : "unknown");
            }
        } catch (Throwable t) {
            log.debug("Netty Epoll check failed: {}", t.getMessage());
        }
        EPOLL_AVAILABLE = epoll;

        boolean kqueue = false;
        try {
            Class<?> kqueueClass = Class.forName("io.netty.channel.kqueue.KQueue");
            kqueue = (boolean) kqueueClass.getMethod("isAvailable").invoke(null);
            if (kqueue) {
                log.debug("Netty KQueue native transport is available.");
            } else {
                Throwable cause =
                        (Throwable) kqueueClass.getMethod("unavailabilityCause").invoke(null);
                log.debug(
                        "Netty KQueue native transport is not available: {}",
                        cause != null ? cause.getMessage() : "unknown");
            }
        } catch (Throwable t) {
            log.debug("Netty KQueue check failed: {}", t.getMessage());
        }
        KQUEUE_AVAILABLE = kqueue;
    }

    @Nonnull
    public static String getTransportName(boolean useNative) {
        if (useNative && EPOLL_AVAILABLE) {
            return "Epoll (Linux native)";
        }
        if (useNative && KQUEUE_AVAILABLE) {
            return "KQueue (macOS/BSD native)";
        }
        return "NIO (standard Java)";
    }

    @Nonnull
    public static String getSslProviderName() {
        return "JDK";
    }

    public static boolean isEpollAvailable() {
        return EPOLL_AVAILABLE;
    }

    public static boolean isKQueueAvailable() {
        return KQUEUE_AVAILABLE;
    }

    @Nonnull
    public static Class<? extends SocketChannel> getSocketChannelClass() {
        return getSocketChannelClass(true);
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    public static Class<? extends SocketChannel> getSocketChannelClass(boolean useNative) {
        if (useNative && EPOLL_AVAILABLE) {
            try {
                return (Class<? extends SocketChannel>) Class.forName("io.netty.channel.epoll.EpollSocketChannel");
            } catch (Throwable ignored) {
            }
        }
        if (useNative && KQUEUE_AVAILABLE) {
            try {
                return (Class<? extends SocketChannel>) Class.forName("io.netty.channel.kqueue.KQueueSocketChannel");
            } catch (Throwable ignored) {
            }
        }
        return NioSocketChannel.class;
    }

    @Nonnull
    public static Class<? extends DatagramChannel> getDatagramChannelClass() {
        return getDatagramChannelClass(true);
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    public static Class<? extends DatagramChannel> getDatagramChannelClass(boolean useNative) {
        if (useNative && EPOLL_AVAILABLE) {
            try {
                return (Class<? extends DatagramChannel>) Class.forName("io.netty.channel.epoll.EpollDatagramChannel");
            } catch (Throwable ignored) {
            }
        }
        if (useNative && KQUEUE_AVAILABLE) {
            try {
                return (Class<? extends DatagramChannel>)
                        Class.forName("io.netty.channel.kqueue.KQueueDatagramChannel");
            } catch (Throwable ignored) {
            }
        }
        return NioDatagramChannel.class;
    }

    @Nonnull
    public static IoHandlerFactory getIoHandlerFactory() {
        return getIoHandlerFactory(true);
    }

    @Nonnull
    public static IoHandlerFactory getIoHandlerFactory(boolean useNative) {
        if (useNative && EPOLL_AVAILABLE) {
            try {
                Class<?> epollIoHandler = Class.forName("io.netty.channel.epoll.EpollIoHandler");
                return (IoHandlerFactory) epollIoHandler.getMethod("newFactory").invoke(null);
            } catch (Throwable ignored) {
            }
        }
        if (useNative && KQUEUE_AVAILABLE) {
            try {
                Class<?> kqueueIoHandler = Class.forName("io.netty.channel.kqueue.KQueueIoHandler");
                return (IoHandlerFactory)
                        kqueueIoHandler.getMethod("newFactory").invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return NioIoHandler.newFactory();
    }

    @Nonnull
    public static EventLoopGroup createEventLoopGroup(int nThreads, @Nonnull ThreadFactory threadFactory) {
        return createEventLoopGroup(nThreads, threadFactory, true);
    }

    @Nonnull
    public static EventLoopGroup createEventLoopGroup(
            int nThreads, @Nonnull ThreadFactory threadFactory, boolean useNative) {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(nThreads, threadFactory, getIoHandlerFactory(useNative));
        if (Boolean.getBoolean("jda.reactor.debug")) {
            log.debug("Created EventLoopGroup {} with {} threads (native={})", group, nThreads, useNative);
        }
        return group;
    }

    @Nonnull
    public static SslContext createSslContext() throws SSLException {
        return SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build();
    }

    public static void configureBootstrap(@Nonnull Bootstrap bootstrap) {
        configureBootstrap(bootstrap, NettyConfig.getDefault());
    }

    public static void configureBootstrap(@Nonnull Bootstrap bootstrap, @Nonnull NettyConfig nettyConfig) {
        Checks.notNull(nettyConfig, "NettyConfig");
        bootstrap
                .channel(getSocketChannelClass(nettyConfig.isUseNativeTransport()))
                .option(ChannelOption.TCP_NODELAY, nettyConfig.isTcpNoDelay())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOCATOR, nettyConfig.getByteBufAllocator())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nettyConfig.getConnectTimeoutMillis());
    }

    @Nonnull
    public static WebSocketClientHandshaker newHandshaker(
            @Nonnull URI webSocketURL, @Nullable HttpHeaders customHeaders, int maxFramePayloadLength) {
        return new WebSocketClientHandshaker13(
                webSocketURL, WebSocketVersion.V13, null, false, customHeaders, maxFramePayloadLength) {
            @Override
            protected FullHttpRequest newHandshakeRequest() {
                FullHttpRequest request = super.newHandshakeRequest();
                request.headers().remove(HttpHeaderNames.ORIGIN);
                return request;
            }
        };
    }

    // Disposes an HttpClient by disposing its underlying connection provider and loop resources.
    public static void disposeHttpClient(@Nullable HttpClient client) {
        if (client == null) {
            return;
        }
        if (Boolean.getBoolean("jda.reactor.debug")) {
            log.debug("Disposing HttpClient {}", client);
        }
        try {
            ConnectionProvider provider = client.configuration().connectionProvider();
            if (provider != null && !provider.isDisposed()) {
                provider.dispose();
                if (Boolean.getBoolean("jda.reactor.debug")) {
                    log.debug("Disposed ConnectionProvider {}", provider);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            LoopResources loops = client.configuration().loopResources();
            if (loops != null && !loops.isDisposed()) {
                loops.dispose();
                if (Boolean.getBoolean("jda.reactor.debug")) {
                    log.debug("Disposed LoopResources {}", loops);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
