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

import net.dv8tion.jda.internal.utils.concurrent.CountingThreadFactory;
import net.dv8tion.jda.internal.utils.concurrent.HttpClientThread;
import net.dv8tion.jda.internal.utils.concurrent.WebSocketThread;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CountingThreadFactoryTest {
    @Test
    void testDefaultThreadCreation() {
        CountingThreadFactory factory = new CountingThreadFactory(() -> "JDA", "Test");
        Thread thread = factory.newThread(() -> {});

        assertThat(thread).isNotNull();
        assertThat(thread.getName()).isEqualTo("JDA Test-Worker 1");
        assertThat(thread.isDaemon()).isTrue();
    }

    @Test
    void testWebsocketThreadCreation() {
        CountingThreadFactory factory = new CountingThreadFactory(() -> "JDA", "WebSocket", WebSocketThread::new);
        Thread thread = factory.newThread(() -> {});

        assertThat(thread).isInstanceOf(WebSocketThread.class);
        assertThat(thread.getName()).isEqualTo("JDA WebSocket-Worker 1");
        assertThat(thread.isDaemon()).isTrue();
    }

    @Test
    void testHttpClientThreadCreation() {
        CountingThreadFactory factory = new CountingThreadFactory(() -> "JDA", "HttpClient", HttpClientThread::new);
        Thread thread = factory.newThread(() -> {});

        assertThat(thread).isInstanceOf(HttpClientThread.class);
        assertThat(thread.getName()).isEqualTo("JDA HttpClient-Worker 1");
        assertThat(thread.isDaemon()).isTrue();
    }

    @Test
    void testVirtualThreadCreation() {
        CountingThreadFactory factory = new CountingThreadFactory(() -> "JDA", "Callback", true, true);
        Thread thread = factory.newThread(() -> {});

        assertThat(thread).isNotNull();
        assertThat(thread.isVirtual()).isTrue();
        assertThat(thread.getName()).isEqualTo("JDA Callback-Worker 1");
        assertThat(factory.isVirtual()).isTrue();
    }
}
