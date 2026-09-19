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

package net.dv8tion.jda.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.FileProxy;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.internal.utils.config.ThreadingConfig;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

public class NettyConfigTest {
    @Test
    void testDefaultValues() {
        NettyConfig config = NettyConfig.getDefault();
        assertThat(config.getByteBufAllocator()).isSameAs(PooledByteBufAllocator.DEFAULT);
        assertThat(config.isUseNativeTransport()).isTrue();
        assertThat(config.isTcpNoDelay()).isTrue();
        assertThat(config.getWebsocketEventLoopThreads()).isEqualTo(NettyConfig.DEFAULT_WEBSOCKET_EVENT_LOOP_THREADS);
        assertThat(config.getHttpClientEventLoopThreads())
                .isEqualTo(NettyConfig.DEFAULT_HTTP_CLIENT_EVENT_LOOP_THREADS);
        assertThat(config.getConnectTimeoutMillis()).isEqualTo(10000);
        assertThat(config.getMaxFramePayloadLength()).isEqualTo(65536 * 1024);
        assertThat(config.getHttpAggregatorMaxContentLength()).isEqualTo(65536);
        assertThat(config.getWebsocketLoopGroup()).isNotNull();
        assertThat(config.getHttpClientLoopGroup()).isNotNull();
        assertThat(config.getAudioEventLoopThreads()).isEqualTo(NettyConfig.DEFAULT_AUDIO_EVENT_LOOP_THREADS);
        assertThat(config.getAudioLoopGroup()).isSameAs(config.getWebsocketLoopGroup());
        assertThat(config.getAudioEventLoopGroup()).isSameAs(config.getWebsocketLoopGroup());
        assertThat(config.getLoopResources()).isNull();
        assertThat(config.getConnectionProvider()).isNull();
        assertThat(config.getHttpClient()).isNotNull();
        assertThat(config.getMaxConnectionIdleTime()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getMaxConnectionLifeTime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.isHttpCompression()).isTrue();
        assertThat(config.isHttpClientCompression()).isTrue();
        config.close();
    }

    @Test
    void testHttpCompressionConfiguration() {
        NettyConfig defaultConfig = NettyConfig.getDefault();
        NettyConfig noCompressionConfig = NettyConfig.getDefault(false);
        try {
            assertThat(defaultConfig.isHttpCompression()).isTrue();
            assertThat(defaultConfig.isHttpClientCompression()).isTrue();

            assertThat(noCompressionConfig.isHttpCompression()).isFalse();
            assertThat(noCompressionConfig.isHttpClientCompression()).isFalse();

            assertThat(defaultConfig).isNotEqualTo(noCompressionConfig);
            assertThat(defaultConfig.hashCode()).isNotEqualTo(noCompressionConfig.hashCode());

            NettyConfig lowMemNoComp = NettyConfig.lowMemory(false);
            try {
                assertThat(lowMemNoComp.isHttpCompression()).isFalse();
            } finally {
                lowMemNoComp.close();
            }

            noCompressionConfig.setHttpCompression(true);
            assertThat(noCompressionConfig.isHttpCompression()).isTrue();
            noCompressionConfig.setHttpClientCompression(false);
            assertThat(noCompressionConfig.isHttpClientCompression()).isFalse();
        } finally {
            defaultConfig.close();
            noCompressionConfig.close();
        }
    }

    @Test
    void testNettyConfigConstructor() {
        EventLoopGroup wsGroup = new NioEventLoopGroup(1);
        EventLoopGroup httpGroup = new NioEventLoopGroup(1);
        try {
            NettyConfig config = new NettyConfig(
                    UnpooledByteBufAllocator.DEFAULT,
                    false,
                    false,
                    2,
                    4,
                    12000,
                    32 * 1024 * 1024,
                    128 * 1024,
                    wsGroup,
                    httpGroup,
                    null,
                    null,
                    null);

            assertThat(config.getByteBufAllocator()).isSameAs(UnpooledByteBufAllocator.DEFAULT);
            assertThat(config.isUseNativeTransport()).isFalse();
            assertThat(config.isTcpNoDelay()).isFalse();
            assertThat(config.getWebsocketEventLoopThreads()).isEqualTo(2);
            assertThat(config.getHttpClientEventLoopThreads()).isEqualTo(4);
            assertThat(config.getConnectTimeoutMillis()).isEqualTo(12000);
            assertThat(config.getMaxFramePayloadLength()).isEqualTo(32 * 1024 * 1024);
            assertThat(config.getHttpAggregatorMaxContentLength()).isEqualTo(128 * 1024);
            assertThat(config.getWebsocketLoopGroup()).isSameAs(wsGroup);
            assertThat(config.getHttpClientLoopGroup()).isSameAs(httpGroup);
            assertThat(config.getAudioEventLoopThreads()).isEqualTo(0);
            assertThat(config.getAudioLoopGroup()).isSameAs(wsGroup);
        } finally {
            wsGroup.shutdownGracefully();
            httpGroup.shutdownGracefully();
        }
    }

    @Test
    void testRuntimeSetters() {
        NettyConfig config = NettyConfig.getDefault();
        try {
            config.setByteBufAllocator(UnpooledByteBufAllocator.DEFAULT)
                    .setTcpNoDelay(false)
                    .setConnectTimeoutMillis(15000)
                    .setMaxWebSocketFramePayloadLength(32 * 1024 * 1024)
                    .setHttpObjectAggregatorMaxContentLength(128 * 1024);

            assertThat(config.getByteBufAllocator()).isSameAs(UnpooledByteBufAllocator.DEFAULT);
            assertThat(config.isTcpNoDelay()).isFalse();
            assertThat(config.getConnectTimeoutMillis()).isEqualTo(15000);
            assertThat(config.getConnectTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(config.getMaxFramePayloadLength()).isEqualTo(32 * 1024 * 1024);
            assertThat(config.getHttpAggregatorMaxContentLength()).isEqualTo(128 * 1024);

            config.setConnectTimeout(Duration.ofSeconds(20));
            assertThat(config.getConnectTimeoutMillis()).isEqualTo(20000);

            config.setConnectTimeout(5, TimeUnit.SECONDS);
            assertThat(config.getConnectTimeoutMillis()).isEqualTo(5000);
        } finally {
            config.close();
        }
    }

    @Test
    void testLowMemoryPreset() {
        NettyConfig config = NettyConfig.lowMemory();
        try {
            assertThat(config.getByteBufAllocator()).isSameAs(UnpooledByteBufAllocator.DEFAULT);
            assertThat(config.getMaxFramePayloadLength()).isEqualTo(16 * 1024 * 1024);
            assertThat(config.getWebsocketEventLoopThreads()).isEqualTo(1);
            assertThat(config.getHttpClientEventLoopThreads())
                    .isEqualTo(NettyConfig.DEFAULT_HTTP_CLIENT_EVENT_LOOP_THREADS);
        } finally {
            config.close();
        }
    }

    @Test
    void testCloseAndDisposal() {
        ConnectionProvider provider = ConnectionProvider.create("cp1", 5);
        LoopResources loops = LoopResources.create("lr1", 1, true);
        EventLoopGroup wsGroup = new NioEventLoopGroup(1);
        EventLoopGroup httpGroup = new NioEventLoopGroup(1);

        try {
            NettyConfig config = new NettyConfig(
                    PooledByteBufAllocator.DEFAULT,
                    true,
                    true,
                    1,
                    1,
                    10000,
                    65536 * 1024,
                    65536,
                    wsGroup,
                    httpGroup,
                    loops,
                    provider,
                    null);

            config.close();
            assertThat(provider.isDisposed()).isTrue();
            assertThat(loops.isDisposed()).isTrue();
            assertThat(wsGroup.isShuttingDown()).isTrue();
            assertThat(httpGroup.isShuttingDown()).isTrue();
        } finally {
            if (!provider.isDisposed()) provider.dispose();
            if (!loops.isDisposed()) loops.dispose();
            if (!wsGroup.isShuttingDown()) wsGroup.shutdownGracefully();
            if (!httpGroup.isShuttingDown()) httpGroup.shutdownGracefully();
        }
    }

    @Test
    void testValidation() {
        NettyConfig config = NettyConfig.getDefault();
        try {
            assertThatIllegalArgumentException().isThrownBy(() -> config.setConnectTimeoutMillis(0));
            assertThatIllegalArgumentException().isThrownBy(() -> config.setConnectTimeoutMillis(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> config.setConnectTimeout(Duration.ofMillis(0)));
            assertThatIllegalArgumentException().isThrownBy(() -> config.setConnectTimeout(-5, TimeUnit.SECONDS));
            assertThatIllegalArgumentException().isThrownBy(() -> config.setMaxWebSocketFramePayloadLength(0));
            assertThatIllegalArgumentException().isThrownBy(() -> config.setMaxWebSocketFramePayloadLength(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> config.setHttpObjectAggregatorMaxContentLength(0));
            assertThatIllegalArgumentException().isThrownBy(() -> config.setHttpObjectAggregatorMaxContentLength(-1));

            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            new NettyConfig(null, true, true, 0, 1, 1000, 1000, 1000, null, null, null, null, null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() ->
                            new NettyConfig(null, true, true, 1, 0, 1000, 1000, 1000, null, null, null, null, null));
        } finally {
            config.close();
        }
    }

    @Test
    void testJDABuilderNettyMethods() {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            JDABuilder builder = JDABuilder.createDefault("test.token")
                    .setByteBufAllocator(UnpooledByteBufAllocator.DEFAULT)
                    .setUseNativeTransport(false)
                    .setTcpNoDelay(false)
                    .setHttpCompression(false)
                    .setWebsocketEventLoopThreadCount(2)
                    .setHttpClientEventLoopThreadCount(4)
                    .setWebsocketConnectTimeout(Duration.ofSeconds(8))
                    .setMaxWebSocketFramePayloadLength(20 * 1024 * 1024)
                    .setHttpObjectAggregatorMaxContentLength(32 * 1024)
                    .setEventLoopGroup(group);

            assertThat(builder).isNotNull();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testShardManagerBuilderNettyMethods() {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            DefaultShardManagerBuilder builder = DefaultShardManagerBuilder.createDefault("test.token")
                    .setByteBufAllocator(UnpooledByteBufAllocator.DEFAULT)
                    .setUseNativeTransport(false)
                    .setTcpNoDelay(false)
                    .setHttpClientCompression(false)
                    .setWebsocketEventLoopThreadCount(2)
                    .setHttpClientEventLoopThreadCount(4)
                    .setWebsocketConnectTimeout(5, TimeUnit.SECONDS)
                    .setMaxWebSocketFramePayloadLength(16 * 1024 * 1024)
                    .setHttpObjectAggregatorMaxContentLength(64 * 1024)
                    .setEventLoopGroup(group);

            ShardManager manager = builder.build(false);
            try {
                NettyConfig config = manager.getNettyConfig();
                assertThat(config).isNotNull();
                assertThat(config.getByteBufAllocator()).isSameAs(UnpooledByteBufAllocator.DEFAULT);
                assertThat(config.isUseNativeTransport()).isFalse();
                assertThat(config.isTcpNoDelay()).isFalse();
                assertThat(config.getWebsocketEventLoopThreads()).isEqualTo(2);
                assertThat(config.getHttpClientEventLoopThreads()).isEqualTo(4);
                assertThat(config.getConnectTimeoutMillis()).isEqualTo(5000);
                assertThat(config.getMaxFramePayloadLength()).isEqualTo(16 * 1024 * 1024);
                assertThat(config.getHttpAggregatorMaxContentLength()).isEqualTo(64 * 1024);
                assertThat(config.getWebsocketLoopGroup()).isSameAs(group);
                assertThat(config.getHttpClientLoopGroup()).isSameAs(group);
                assertThat(config.isHttpCompression()).isFalse();
                assertThat(config.isHttpClientCompression()).isFalse();
            } finally {
                manager.shutdown();
            }
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testGlobalAllocator() {
        // Defaults to high-performance DEFAULT_ALLOCATOR (PooledByteBufAllocator.DEFAULT)
        NettyConfig.setGlobalAllocator(null);
        assertThat(NettyConfig.getGlobalAllocator()).isSameAs(NettyConfig.DEFAULT_ALLOCATOR);
        assertThat(NettyConfig.getGlobalAllocator()).isSameAs(PooledByteBufAllocator.DEFAULT);

        // Switch to UnpooledByteBufAllocator safely
        NettyConfig.setGlobalAllocator(UnpooledByteBufAllocator.DEFAULT);
        assertThat(NettyConfig.getGlobalAllocator()).isSameAs(UnpooledByteBufAllocator.DEFAULT);

        // Reset via null safely returns to DEFAULT_ALLOCATOR
        NettyConfig.setGlobalAllocator(null);
        assertThat(NettyConfig.getGlobalAllocator()).isSameAs(NettyConfig.DEFAULT_ALLOCATOR);

        // Instance allocator change synchronizes global allocator
        NettyConfig config = NettyConfig.getDefault();
        try {
            assertThat(config.getByteBufAllocator()).isSameAs(PooledByteBufAllocator.DEFAULT);
            config.setByteBufAllocator(UnpooledByteBufAllocator.DEFAULT);
            assertThat(config.getByteBufAllocator()).isSameAs(UnpooledByteBufAllocator.DEFAULT);
            assertThat(NettyConfig.getGlobalAllocator()).isSameAs(UnpooledByteBufAllocator.DEFAULT);
        } finally {
            config.close();
            NettyConfig.setGlobalAllocator(null);
        }
    }

    @Test
    void testConcurrentGlobalAllocatorSwitching() throws Exception {
        int threads = 4;
        int iterations = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int threadId = i;
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < iterations; j++) {
                        ByteBufAllocator target = (threadId + j) % 2 == 0
                                ? UnpooledByteBufAllocator.DEFAULT
                                : PooledByteBufAllocator.DEFAULT;
                        NettyConfig.setGlobalAllocator(target);
                        ByteBufAllocator current = NettyConfig.getGlobalAllocator();
                        assertThat(current).isNotNull();
                        ByteBuf buf = current.buffer(64);
                        try {
                            buf.writeInt(42);
                        } finally {
                            buf.release();
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            NettyConfig.setGlobalAllocator(null);
        }
    }

    @Test
    void testFileProxyResetDefaultHttpClient() {
        NettyConfig config = NettyConfig.getDefault();
        HttpClient client = config.getHttpClient();
        try {
            FileProxy.setDefaultHttpClient(client);
            // Resetting with mismatched client should not clear it
            HttpClient otherClient = HttpClient.create();
            FileProxy.resetDefaultHttpClient(otherClient);
            // Resetting with expected client should clear it
            FileProxy.resetDefaultHttpClient(client);
            // Resetting with null should unconditionally succeed
            FileProxy.setDefaultHttpClient(client);
            FileProxy.resetDefaultHttpClient(null);
        } finally {
            config.close();
        }
    }

    @Test
    void testFileProxyResetDefaultScheduler() {
        Scheduler scheduler = Schedulers.newSingle("test-scheduler");
        Scheduler otherScheduler = Schedulers.newSingle("other-scheduler");
        try {
            FileProxy.setDefaultScheduler(scheduler);
            assertThat(FileProxy.getDefaultScheduler()).isSameAs(scheduler);

            // Resetting with mismatched scheduler should not clear it
            FileProxy.resetDefaultScheduler(otherScheduler);
            assertThat(FileProxy.getDefaultScheduler()).isSameAs(scheduler);

            // Resetting with expected scheduler should clear it
            FileProxy.resetDefaultScheduler(scheduler);
            assertThat(FileProxy.getDefaultScheduler()).isNull();

            // Resetting with null should unconditionally succeed
            FileProxy.setDefaultScheduler(scheduler);
            FileProxy.resetDefaultScheduler(null);
            assertThat(FileProxy.getDefaultScheduler()).isNull();
        } finally {
            FileProxy.resetDefaultScheduler(null);
            scheduler.dispose();
            otherScheduler.dispose();
        }
    }

    @Test
    void testFileProxyWithScheduler() {
        Scheduler customScheduler = Schedulers.newSingle("custom-scheduler");
        try {
            FileProxy proxy = new FileProxy("https://discord.com/test.png").withScheduler(customScheduler);
            assertThat(proxy).isNotNull();
        } finally {
            customScheduler.dispose();
        }
    }

    @Test
    void testThreadingConfigCallbackScheduler() {
        ThreadingConfig config = ThreadingConfig.getDefault();
        try {
            Scheduler scheduler = config.getCallbackScheduler();
            assertThat(scheduler).isNotNull();

            ExecutorService customPool = Executors.newSingleThreadExecutor();
            try {
                config.setCallbackPool(customPool, true);
                Scheduler customScheduler = config.getCallbackScheduler();
                assertThat(customScheduler).isNotNull();
                assertThat(customScheduler).isNotSameAs(scheduler);
            } finally {
                config.shutdown();
            }
        } finally {
            config.shutdown();
        }
    }

    @Test
    void testDedicatedAudioEventLoopThreads() {
        NettyConfig config = new NettyConfig(
                null, true, true, 1, 2, 2, 10000, 65536 * 1024, 65536, null, null, null, null, null, null);
        try {
            assertThat(config.getAudioEventLoopThreads()).isEqualTo(2);
            assertThat(config.getAudioLoopGroup()).isNotNull();
            assertThat(config.getAudioLoopGroup()).isNotSameAs(config.getWebsocketLoopGroup());
            assertThat(config.getAudioLoopGroup()).isNotSameAs(config.getHttpClientLoopGroup());
        } finally {
            config.close();
            assertThat(config.getAudioLoopGroup().isShuttingDown()).isTrue();
        }
    }

    @Test
    void testExplicitAudioLoopGroup() {
        EventLoopGroup audioGroup = new NioEventLoopGroup(1);
        try {
            NettyConfig config = new NettyConfig(
                    null, true, true, 1, 2, 0, 10000, 65536 * 1024, 65536, null, null, audioGroup, null, null, null);
            assertThat(config.getAudioLoopGroup()).isSameAs(audioGroup);
            config.close();
        } finally {
            audioGroup.shutdownGracefully();
        }
    }
}
