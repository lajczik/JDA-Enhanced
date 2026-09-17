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

package net.dv8tion.jda.test.concurrent;

import net.dv8tion.jda.internal.utils.config.ThreadingConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class GatewayThreadingTest {
    @Test
    void testThreadingConfigDefaultEventPoolIsVirtualThread() throws InterruptedException {
        ThreadingConfig config = new ThreadingConfig();
        ExecutorService eventPool = config.getEventPool();

        assertThat(eventPool).isNotNull();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean isVirtual = new AtomicBoolean(false);
        eventPool.execute(() -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        });

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(isVirtual.get()).isTrue();

        config.shutdown();
        assertThat(eventPool.isShutdown()).isTrue();
    }

    @Test
    void testThreadingConfigExplicitCustomEventPool() {
        ThreadingConfig config = new ThreadingConfig();
        ExecutorService customPool = Executors.newSingleThreadExecutor();
        try {
            config.setEventPool(customPool, true);
            assertThat(config.getEventPool()).isSameAs(customPool);
            config.shutdown();
            assertThat(customPool.isShutdown()).isTrue();
        } finally {
            customPool.shutdownNow();
        }
    }

    @Test
    void testThreadingConfigExplicitNullEventPool() {
        ThreadingConfig config = new ThreadingConfig();
        config.setEventPool(null, false);
        assertThat(config.getEventPool()).isNull();
        config.shutdown();
    }
}
