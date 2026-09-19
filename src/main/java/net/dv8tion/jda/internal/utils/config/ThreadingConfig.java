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

package net.dv8tion.jda.internal.utils.config;

import net.dv8tion.jda.internal.utils.concurrent.CountingThreadFactory;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ThreadingConfig {
    private final Object audioLock = new Object();

    private ScheduledExecutorService rateLimitScheduler;
    private ExecutorService rateLimitElastic;
    private ScheduledExecutorService gatewayPool;
    private ExecutorService callbackPool;
    private Scheduler callbackScheduler;
    private ExecutorService eventPool;
    private ScheduledExecutorService audioPool;

    private boolean shutdownRateLimitScheduler;
    private boolean shutdownRateLimitElastic;
    private boolean shutdownGatewayPool;
    private boolean shutdownCallbackPool;
    private boolean shutdownEventPool;
    private boolean shutdownAudioPool;

    public ThreadingConfig() {
        this.callbackPool = newVirtualThreadExecutor("JDA-Callback");
        this.callbackScheduler = Schedulers.fromExecutorService(this.callbackPool);
        this.eventPool = newVirtualThreadExecutor("JDA-Event");

        this.shutdownRateLimitScheduler = true;
        this.shutdownRateLimitElastic = true;
        this.shutdownGatewayPool = true;
        this.shutdownCallbackPool = true;
        this.shutdownEventPool = true;
        this.shutdownAudioPool = true;
    }

    @Nonnull
    public static ScheduledThreadPoolExecutor newScheduler(
            int coreSize, Supplier<String> identifier, String baseName, boolean daemon) {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(coreSize, new CountingThreadFactory(identifier, baseName, daemon));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Nonnull
    public static ExecutorService newVirtualThreadExecutor(@Nonnull String prefix) {
        return Executors.newThreadPerTaskExecutor(new CountingThreadFactory(() -> prefix, "", true, true));
    }

    @Nonnull
    public static ScheduledThreadPoolExecutor newScheduler(int coreSize, Supplier<String> identifier, String baseName) {
        return newScheduler(coreSize, identifier, baseName, true);
    }

    @Nonnull
    public static ThreadingConfig getDefault() {
        return new ThreadingConfig();
    }

    public void setRateLimitScheduler(@Nullable ScheduledExecutorService executor, boolean shutdown) {
        this.rateLimitScheduler = executor;
        this.shutdownRateLimitScheduler = shutdown;
    }

    public void setRateLimitElastic(@Nullable ExecutorService executor, boolean shutdown) {
        this.rateLimitElastic = executor;
        this.shutdownRateLimitElastic = shutdown;
    }

    public void setGatewayPool(@Nullable ScheduledExecutorService executor, boolean shutdown) {
        this.gatewayPool = executor;
        this.shutdownGatewayPool = shutdown;
    }

    public void setEventPool(@Nullable ExecutorService executor, boolean shutdown) {
        this.eventPool = executor;
        this.shutdownEventPool = shutdown;
    }

    public void setAudioPool(@Nullable ScheduledExecutorService executor, boolean shutdown) {
        this.audioPool = executor;
        this.shutdownAudioPool = shutdown;
    }

    public void setCallbackPool(@Nullable ExecutorService executor, boolean shutdown) {
        if (this.callbackScheduler != null) {
            this.callbackScheduler.dispose();
        }
        this.callbackPool = executor == null ? newVirtualThreadExecutor("JDA-Callback") : executor;
        this.shutdownCallbackPool = shutdown;
        this.callbackScheduler = Schedulers.fromExecutorService(this.callbackPool);
    }

    public void shutdown() {
        if (shutdownCallbackPool) {
            callbackPool.shutdown();
            if (callbackScheduler != null) {
                callbackScheduler.dispose();
            }
        }
        if (shutdownGatewayPool && gatewayPool != null) {
            gatewayPool.shutdown();
        }
        if (shutdownEventPool && eventPool != null) {
            eventPool.shutdown();
        }
        if (shutdownAudioPool && audioPool != null) {
            audioPool.shutdown();
        }
    }

    public void shutdownRequester() {
        if (shutdownRateLimitScheduler) {
            rateLimitScheduler.shutdown();
        }
        if (shutdownRateLimitElastic) {
            rateLimitElastic.shutdown();
        }
    }

    public void shutdownNow() {
        if (shutdownCallbackPool) {
            callbackPool.shutdownNow();
            if (callbackScheduler != null) {
                callbackScheduler.dispose();
            }
        }
        if (shutdownGatewayPool && gatewayPool != null) {
            gatewayPool.shutdownNow();
        }
        if (shutdownRateLimitScheduler) {
            rateLimitScheduler.shutdownNow();
        }
        if (shutdownRateLimitElastic) {
            rateLimitElastic.shutdownNow();
        }
        if (shutdownEventPool && eventPool != null) {
            eventPool.shutdownNow();
        }
        if (shutdownAudioPool && audioPool != null) {
            audioPool.shutdownNow();
        }
    }

    @Nonnull
    public ScheduledExecutorService getRateLimitScheduler() {
        return rateLimitScheduler;
    }

    @Nonnull
    public ExecutorService getRateLimitElastic() {
        return rateLimitElastic;
    }

    @Nonnull
    public ScheduledExecutorService getGatewayPool() {
        return gatewayPool;
    }

    @Nonnull
    public ExecutorService getCallbackPool() {
        return callbackPool;
    }

    @Nonnull
    public Scheduler getCallbackScheduler() {
        if (callbackScheduler == null) {
            callbackScheduler = Schedulers.fromExecutorService(callbackPool);
        }
        return callbackScheduler;
    }

    @Nullable
    public ExecutorService getEventPool() {
        return eventPool;
    }

    @Nullable
    public ScheduledExecutorService getAudioPool(@Nonnull Supplier<String> identifier) {
        ScheduledExecutorService pool = audioPool;
        if (pool == null) {
            synchronized (audioLock) {
                pool = audioPool;
                if (pool == null) {
                    pool = audioPool = ThreadingConfig.newScheduler(1, identifier, "AudioLifeCycle");
                }
            }
        }
        return pool;
    }

    public boolean isShutdownRateLimitScheduler() {
        return shutdownRateLimitScheduler;
    }

    public boolean isShutdownRateLimitElastic() {
        return shutdownRateLimitElastic;
    }

    public boolean isShutdownGatewayPool() {
        return shutdownGatewayPool;
    }

    public boolean isShutdownCallbackPool() {
        return shutdownCallbackPool;
    }

    public boolean isShutdownEventPool() {
        return shutdownEventPool;
    }

    public boolean isShutdownAudioPool() {
        return shutdownAudioPool;
    }

    public void init(@Nonnull Supplier<String> identifier) {
        if (this.rateLimitScheduler == null) {
            this.rateLimitScheduler = newScheduler(2, identifier, "RateLimit-Scheduler", false);
        }
        if (this.gatewayPool == null) {
            this.gatewayPool = newScheduler(1, identifier, "Gateway");
        }
        if (this.rateLimitElastic == null) {
            this.rateLimitElastic = newVirtualThreadExecutor(identifier.get() + " RateLimit-Elastic");
        }
        if (this.eventPool == null) {
            this.eventPool = newVirtualThreadExecutor(identifier.get() + " Event");
            this.shutdownEventPool = true;
        }
    }
}
