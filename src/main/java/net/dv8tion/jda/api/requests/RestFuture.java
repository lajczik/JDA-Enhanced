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

package net.dv8tion.jda.api.requests;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import net.dv8tion.jda.internal.utils.requestbody.RequestBody;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of {@link CompletableFuture} used for {@link RestAction#submit()}.
 *
 * @param <T> The result type
 */
public class RestFuture<T> extends CompletableFuture<T> {
    final Request<T> request;
    final JDA api;

    public RestFuture(
            RestActionImpl<T> restAction,
            boolean shouldQueue,
            BooleanSupplier checks,
            RequestBody data,
            Object rawData,
            long deadline,
            boolean priority,
            Route.CompiledRoute route,
            Map<String, String> headers) {
        this.api = restAction.getJDA();
        this.request = new Request<>(
                restAction,
                this::complete,
                this::completeExceptionally,
                checks,
                shouldQueue,
                data,
                rawData,
                deadline,
                priority,
                route,
                headers);
        ((JDAImpl) restAction.getJDA()).getRequester().request(this.request);
    }

    public RestFuture(@Nullable JDA api) {
        this.api = api;
        this.request = null;
    }

    public RestFuture(@Nullable JDA api, T t) {
        this.api = api;
        this.request = null;
        complete(t);
    }

    public RestFuture(@Nullable JDA api, Throwable t) {
        this.api = api;
        this.request = null;
        completeExceptionally(t);
    }

    public RestFuture(T t) {
        this(null, t);
    }

    public RestFuture(Throwable t) {
        this(null, t);
    }

    @Nonnull
    @Override
    public Executor defaultExecutor() {
        JDA jda = this.api != null ? this.api : (this.request != null ? this.request.getJDA() : null);
        if (jda != null) {
            ExecutorService executor = jda.getCallbackPool();
            if (executor == null || executor.isShutdown()) {
                executor = jda.getEventPool();
            }
            if (executor != null && !executor.isShutdown()) {
                return executor;
            }
        }
        return super.defaultExecutor();
    }

    @Nonnull
    @CheckReturnValue
    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        JDA jda = this.api != null ? this.api : (this.request != null ? this.request.getJDA() : null);
        return new RestFuture<>(jda);
    }

    @Override
    public boolean cancel(boolean mayInterrupt) {
        if (this.request != null) {
            this.request.cancel();
        }

        return (!isDone() && !isCancelled()) && super.cancel(mayInterrupt);
    }
}
