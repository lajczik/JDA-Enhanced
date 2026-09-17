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

package net.dv8tion.jda.test.restaction;

import net.dv8tion.jda.api.requests.Request;
import net.dv8tion.jda.api.requests.RestFuture;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.internal.requests.CompletedRestAction;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import net.dv8tion.jda.test.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class RestActionTest extends IntegrationTest {
    @Test
    void testMapOperator() {
        assertThat(new CompletedRestAction<>(jda, "12345")
                        .map(Integer::parseInt)
                        .complete())
                .isEqualTo(12345);
    }

    @Test
    void testFlatMapOperator() {
        assertThat(new CompletedRestAction<>(jda, "12345")
                        .flatMap(value -> new CompletedRestAction<>(jda, Integer.parseInt(value)))
                        .complete())
                .isEqualTo(12345);

        assertThat(new CompletedRestAction<>(jda, "12345")
                        .flatMap(
                                value -> value.startsWith("123"),
                                value -> new CompletedRestAction<>(jda, Integer.parseInt(value)))
                        .complete())
                .isEqualTo(12345);

        assertThatThrownBy(() -> new CompletedRestAction<>(jda, "12345")
                        .flatMap(
                                value -> value.startsWith("wrong"),
                                value -> new CompletedRestAction<>(jda, Integer.parseInt(value)))
                        .complete())
                .isInstanceOf(CancellationException.class)
                .hasMessage("FlatMap condition failed");

        assertThat(new CompletedRestAction<>(jda, "12345")
                        .flatMap(
                                value -> value.startsWith("wrong"),
                                value -> new CompletedRestAction<>(jda, Integer.parseInt(value)))
                        .submit())
                .failsWithin(Duration.ZERO)
                .withThrowableThat()
                .havingRootCause()
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void testDelayOperator() {
        when(scheduledExecutorService.schedule(any(Runnable.class), anyLong(), any()))
                .thenReturn(null);

        new CompletedRestAction<>(jda, "12345")
                .delay(Duration.ofSeconds(2), scheduledExecutorService)
                .queue();

        new CompletedRestAction<>(jda, "12345")
                .delay(3, TimeUnit.SECONDS, scheduledExecutorService)
                .queue();

        verify(scheduledExecutorService, times(1)).schedule(any(Runnable.class), eq(2000L), eq(TimeUnit.MILLISECONDS));

        verify(scheduledExecutorService, times(1)).schedule(any(Runnable.class), eq(3L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testQueueAfter() {
        when(scheduledExecutorService.schedule(any(Runnable.class), anyLong(), any()))
                .thenReturn(null);

        new CompletedRestAction<>(jda, "12345").queueAfter(2, TimeUnit.SECONDS, scheduledExecutorService);

        verify(scheduledExecutorService, times(1)).schedule(any(Runnable.class), eq(2L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testRequestOnSuccessUsesCallbackPool() {
        ExecutorService mockCallbackPool = mock(ExecutorService.class);
        when(jda.getCallbackPool()).thenReturn(mockCallbackPool);

        Route.CompiledRoute route = Route.Self.GET_SELF.compile();
        RestActionImpl<String> action = new RestActionImpl<>(jda, route, (res, req) -> "test");
        Request<String> request =
                new Request<>(action, (res) -> {}, (err) -> {}, null, true, null, null, 0, false, route, Map.of());

        request.onSuccess("test");
        verify(mockCallbackPool, times(1)).execute(any(Runnable.class));
    }

    @Test
    void testToFutureAndMono() {
        CompletedRestAction<String> action = new CompletedRestAction<>(jda, "hello");

        assertThat(action.toFuture().join()).isEqualTo("hello");
        assertThat(action.asMono().block()).isEqualTo("hello");
        assertThat(action.mono().block()).isEqualTo("hello");
    }

    @Test
    void testRestFutureDefaultExecutorUsesCallbackPool() {
        ExecutorService mockCallbackPool = mock(ExecutorService.class);
        when(jda.getCallbackPool()).thenReturn(mockCallbackPool);

        RestFuture<String> future = new RestFuture<>(jda, "result");
        assertThat(future.defaultExecutor()).isSameAs(mockCallbackPool);

        CompletableFuture<Integer> mapped = future.thenApply(String::length);
        assertThat(mapped.defaultExecutor()).isSameAs(mockCallbackPool);
    }

    @Test
    void testRestFutureFallbackToEventPool() {
        ExecutorService mockCallbackPool = mock(ExecutorService.class);
        ExecutorService mockEventPool = mock(ExecutorService.class);
        when(mockCallbackPool.isShutdown()).thenReturn(true);
        when(jda.getCallbackPool()).thenReturn(mockCallbackPool);
        when(jda.getEventPool()).thenReturn(mockEventPool);

        RestFuture<String> future = new RestFuture<>(jda, "result");
        assertThat(future.defaultExecutor()).isSameAs(mockEventPool);
    }

    @Test
    void testCompletedRestActionSubmitUsesRestFuture() {
        ExecutorService mockCallbackPool = mock(ExecutorService.class);
        when(jda.getCallbackPool()).thenReturn(mockCallbackPool);

        CompletedRestAction<String> action = new CompletedRestAction<>(jda, "success");
        CompletableFuture<String> submitFuture = action.submit();

        assertThat(submitFuture).isInstanceOf(RestFuture.class);
        assertThat(submitFuture.defaultExecutor()).isSameAs(mockCallbackPool);
    }
}
