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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.Method;
import net.dv8tion.jda.api.requests.Request;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.requests.RestConfig;
import net.dv8tion.jda.api.requests.RestRateLimiter;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.utils.JDALogger;
import net.dv8tion.jda.internal.utils.config.AuthorizationConfig;
import net.dv8tion.jda.internal.utils.requestbody.ByteBufRequestBody;
import net.dv8tion.jda.internal.utils.requestbody.RequestBody;
import org.slf4j.Logger;
import org.slf4j.MDC;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;

public class Requester {
    private static final int[] RETRY_ERROR_CODES = {
        502, // bad gateway
        503, // service temporarily unavailable
        504, // gateway timeout
        520, // web server returns an unknown error
        521, // web server is down
        522, // connection timed out
        523, // origin is unreachable
        524, // a timeout occurred
        529, // The service is overloaded
    };

    public static final Logger LOG = JDALogger.getLog(Requester.class);

    public static final RequestBody EMPTY_BODY = new ByteBufRequestBody(Unpooled.EMPTY_BUFFER, null);

    private static final AsciiString X_RATELIMIT_PRECISION = AsciiString.cached("x-ratelimit-precision");
    private static final AsciiString MILLISECOND = AsciiString.cached("millisecond");
    private static final AsciiString CF_RAY = AsciiString.cached("cf-ray");

    protected final JDAImpl api;
    protected final AuthorizationConfig authConfig;
    private final RestRateLimiter rateLimiter;
    private final String baseUrl;
    private final String userAgent;
    private final Consumer<? super HttpClientRequest> customBuilder;

    private final HttpClient httpClient;

    // when we actually set the shard info we can also set the mdc context map,
    // before it makes no sense
    private boolean isContextReady = false;
    private ConcurrentMap<String, String> contextMap = null;

    private volatile boolean retryOnTimeout = false;

    public Requester(JDA api, AuthorizationConfig authConfig, RestConfig config, RestRateLimiter rateLimiter) {
        if (authConfig == null) {
            throw new NullPointerException("Provided config was null!");
        }

        this.authConfig = authConfig;
        this.api = (JDAImpl) api;
        this.rateLimiter = rateLimiter;
        this.baseUrl = config.getBaseUrl();
        this.userAgent = config.getUserAgent();
        this.customBuilder = config.getCustomBuilder();
        this.httpClient = this.api.getHttpClient();
    }

    public void setContextReady(boolean ready) {
        this.isContextReady = ready;
    }

    public void setContext() {
        if (!isContextReady) {
            return;
        }
        if (contextMap == null) {
            contextMap = api.getContextMap();
        }
        contextMap.forEach(MDC::put);
    }

    public JDAImpl getJDA() {
        return api;
    }

    public <T> void request(Request<T> apiRequest) {
        if (rateLimiter.isStopped()) {
            throw new RejectedExecutionException("The Requester has been stopped! No new requests can be requested!");
        }

        if (apiRequest.shouldQueue()) {
            rateLimiter.enqueue(new WorkTask(apiRequest));
        } else {
            ExecutorService eventPool = api.getEventPool();
            if (eventPool != null && !eventPool.isShutdown()) {
                eventPool.execute(() -> execute(new WorkTask(apiRequest), true));
            } else {
                execute(new WorkTask(apiRequest), true);
            }
        }
    }

    private static boolean isRetry(Throwable e) {
        return e instanceof SocketException // Socket couldn't be created or access failed
                || e instanceof SocketTimeoutException // Connection timed out
                || e instanceof ConnectTimeoutException
                || e instanceof ReadTimeoutException
                || e instanceof SSLPeerUnverifiedException; // SSL Certificate was wrong
    }

    private Response execute(WorkTask task) {
        return execute(task, false);
    }

    private Response execute(WorkTask task, boolean handleOnRateLimit) {
        return execute(task, false, handleOnRateLimit);
    }

    private Response execute(WorkTask task, boolean retried, boolean handleOnRatelimit) {
        Route.CompiledRoute route = task.getRoute();
        String url = route.toUrl(baseUrl);
        Request<?> apiRequest = task.request;

        Method method = apiRequest.getRoute().getMethod();
        HttpMethod nettyMethod = HttpMethod.valueOf(method.toString());

        RequestBody body = apiRequest.getBody();
        if (body == null && method.requiresRequestBody()) {
            body = EMPTY_BODY;
        }

        if (apiRequest.getRawBody() != null) {
            LOG.trace(
                    "Sending request on route {}/{} with body\n{}",
                    method,
                    apiRequest.getRoute().getCompiledRoute(),
                    apiRequest.getRawBody());
        }

        final RequestBody finalBody = body;
        HttpClient.ResponseReceiver<?> receiver = httpClient
                .request(nettyMethod)
                .uri(url)
                .send((req, out) -> {
                    req.header(HttpHeaderNames.USER_AGENT, userAgent)
                            .header(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP)
                            .header(HttpHeaderNames.AUTHORIZATION, authConfig.getToken())
                            .header(X_RATELIMIT_PRECISION, MILLISECOND);

                    if (apiRequest.getHeaders() != null) {
                        for (Entry<String, String> header :
                                apiRequest.getHeaders().entrySet()) {
                            req.header(header.getKey(), header.getValue());
                        }
                    }

                    if (customBuilder != null) {
                        try {
                            customBuilder.accept(req);
                        } catch (Exception e) {
                            LOG.error("Custom request builder caused exception", e);
                        }
                    }
                    if (finalBody != null) {
                        String contentType = finalBody.contentTypeHeader();
                        if (contentType != null) {
                            req.header(HttpHeaderNames.CONTENT_TYPE, contentType);
                        }
                        try {
                            long contentLength = finalBody.contentLength();
                            if (contentLength >= 0) {
                                req.header(HttpHeaderNames.CONTENT_LENGTH, Long.toString(contentLength));
                            }
                        } catch (IOException e) {
                            LOG.warn("Failed to determine content length for request body", e);
                        }
                        ByteBufAllocator allocator = this.api.getNettyConfig().getByteBufAllocator();
                        return out.send(ByteBufMono.fromCallable(() -> finalBody.getByteBuf(allocator))
                                .subscribeOn(this.api.getCallbackScheduler()));
                    }
                    return out;
                });

        Set<String> rays = new LinkedHashSet<>();
        RawHttpResponse lastRaw = null;

        try {
            LOG.trace("Executing request {} {}", route.getMethod(), url);
            int code = 0;
            for (int attempt = 0; attempt < 4; attempt++) {
                if (apiRequest.isSkipped()) {
                    if (lastRaw != null && lastRaw.byteBuf != null) {
                        if (lastRaw.byteBuf.refCnt() > 0) {
                            ReferenceCountUtil.safeRelease(lastRaw.byteBuf);
                        }
                        lastRaw = null;
                    }
                    return null;
                }

                // If previous attempt had a buffer and we are retrying, release it now before the next attempt
                if (lastRaw != null && lastRaw.byteBuf != null) {
                    if (lastRaw.byteBuf.refCnt() > 0) {
                        ReferenceCountUtil.safeRelease(lastRaw.byteBuf);
                    }
                    lastRaw = null;
                }

                lastRaw = receiver.responseSingle((httpRes, byteBufMono) -> {
                            int status = httpRes.status().code();
                            String message = httpRes.status().reasonPhrase();
                            HttpHeaders respHeaders = httpRes.responseHeaders().copy();
                            return byteBufMono
                                    .retain()
                                    .map(buf -> new RawHttpResponse(status, message, respHeaders, buf, url))
                                    .defaultIfEmpty(new RawHttpResponse(
                                            status, message, respHeaders, Unpooled.EMPTY_BUFFER, url));
                        })
                        .block();

                if (lastRaw == null) {
                    break;
                }

                code = lastRaw.code;
                String cfRay = lastRaw.headers.get(CF_RAY);
                if (cfRay != null) {
                    rays.add(cfRay);
                }

                // Retry a few specific server errors that are related to server issues (stop if reached attempt 3)
                if (!shouldRetry(code) || attempt == 3) {
                    break;
                }

                LOG.debug(
                        "Requesting {} -> {} returned status {}... retrying (attempt {})",
                        apiRequest.getRoute().getMethod(),
                        url,
                        code,
                        attempt + 1);
                try {
                    Thread.sleep(500L << attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (lastRaw == null) {
                return null;
            }

            LOG.trace("Finished Request {} {} with code {}", route.getMethod(), url, code);

            if (shouldRetry(code)) {
                // Epic failure from other end. Attempted 4 times.
                Response resp = toResponse(lastRaw, -1, rays);
                task.handleResponse(resp);
                return resp;
            }

            if (!rays.isEmpty()) {
                LOG.debug("Received response with following cf-rays: {}", rays);
            }

            if (handleOnRatelimit && code == 429) {
                long retryAfter = parseRetry(lastRaw);
                Response resp = toResponse(lastRaw, retryAfter, rays);
                task.handleResponse(resp);
                return resp;
            } else if (code != 429) {
                Response resp = toResponse(lastRaw, -1, rays);
                task.handleResponse(resp);
                return resp;
            } else if (getContentType(lastRaw).startsWith(HttpHeaderValues.APPLICATION_JSON.toString())) {
                // On 429, replace the retry-after header if its wrong (discord moment)
                // We just pick whichever is bigger between body and header
                try {
                    long retryAfterBody = (long) Math.ceil(
                            DataObject.fromJson(lastRaw.byteBuf.slice()).getDouble("retry_after", 0) * 1000);
                    long retryAfterHeader = parseRetry(lastRaw);
                    long retryAfter = Math.max(retryAfterHeader, retryAfterBody);
                    lastRaw.headers.set(HttpHeaderNames.RETRY_AFTER, Long.toString(retryAfter / 1000));
                } catch (Exception e) {
                    LOG.warn("Failed to parse retry-after response body", e);
                }
                return toResponse(lastRaw, parseRetry(lastRaw), rays);
            }

            return toResponse(lastRaw, -1, rays);
        } catch (Exception e) {
            if (lastRaw != null && lastRaw.byteBuf != null) {
                if (lastRaw.byteBuf.refCnt() > 0) {
                    ReferenceCountUtil.safeRelease(lastRaw.byteBuf);
                }
                lastRaw = null;
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof UnknownHostException) {
                LOG.error("DNS resolution failed: {}", cause.getMessage());
                task.handleResponse((Exception) cause, rays);
                return null;
            }
            if (retryOnTimeout && !retried && isRetry(cause)) {
                return execute(task, true, handleOnRatelimit);
            }
            if (cause instanceof IOException) {
                LOG.error("There was an I/O error while executing a REST request: {}", cause.getMessage());
                task.handleResponse((Exception) cause, rays);
                return null;
            }
            LOG.error("There was an unexpected error while executing a REST request", e);
            task.handleResponse(e, rays);
            return null;
        }
    }

    private Response toResponse(RawHttpResponse raw, long retryAfter, Set<String> cfRays) {
        if (raw == null) {
            return null;
        }
        return new Response(raw.code, raw.message, retryAfter, raw.byteBuf, raw.headers, raw.url, cfRays);
    }

    public HttpClient getHttpClient() {
        return this.httpClient;
    }

    public String getUserAgent() {
        return this.userAgent;
    }

    public RestRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public void setRetryOnTimeout(boolean retryOnTimeout) {
        this.retryOnTimeout = retryOnTimeout;
    }

    public void stop(boolean shutdown, Runnable callback) {
        rateLimiter.stop(shutdown, callback);
    }

    private static boolean shouldRetry(int code) {
        if (code < RETRY_ERROR_CODES[0] || code > RETRY_ERROR_CODES[RETRY_ERROR_CODES.length - 1]) {
            return false;
        }
        for (int retryCode : RETRY_ERROR_CODES) {
            if (retryCode == code) {
                return true;
            }
        }
        return false;
    }

    private long parseRetry(RawHttpResponse response) {
        if (response == null || response.headers == null) {
            return 0;
        }
        String retryAfter = response.headers.get(HttpHeaderNames.RETRY_AFTER);
        if (retryAfter == null) {
            return 0;
        }
        try {
            return (long) (Double.parseDouble(retryAfter) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String getContentType(RawHttpResponse response) {
        if (response == null || response.headers == null) {
            return "";
        }
        String type = response.headers.get(HttpHeaderNames.CONTENT_TYPE);
        return type == null ? "" : type.toLowerCase(Locale.ROOT);
    }

    private static class RawHttpResponse {
        final int code;
        final String message;
        final HttpHeaders headers;
        final ByteBuf byteBuf;
        final String url;

        RawHttpResponse(int code, String message, HttpHeaders headers, ByteBuf byteBuf, String url) {
            this.code = code;
            this.message = message;
            this.headers = headers;
            this.byteBuf = byteBuf;
            this.url = url;
        }
    }

    private class WorkTask implements RestRateLimiter.Work {
        private final Request<?> request;
        private boolean done;

        private WorkTask(Request<?> request) {
            this.request = request;
        }

        @Nonnull
        @Override
        public Route.CompiledRoute getRoute() {
            return request.getRoute();
        }

        @Nonnull
        @Override
        public JDA getJDA() {
            return request.getJDA();
        }

        @Nullable
        @Override
        public Response execute() {
            return Requester.this.execute(this);
        }

        @Override
        public boolean isSkipped() {
            return request.isSkipped();
        }

        @Override
        public boolean isDone() {
            return isSkipped() || done;
        }

        @Override
        public boolean isPriority() {
            return request.isPriority();
        }

        @Override
        public boolean isCancelled() {
            return request.isCancelled();
        }

        @Override
        public void cancel() {
            request.cancel();
        }

        private void handleResponse(Response response) {
            done = true;
            request.handleResponse(response);
        }

        private void handleResponse(Exception error, Set<String> rays) {
            done = true;
            request.handleResponse(new Response(error, rays));
        }
    }
}
