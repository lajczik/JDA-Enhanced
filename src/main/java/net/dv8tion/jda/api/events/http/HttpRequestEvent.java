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

package net.dv8tion.jda.api.events.http;

import io.netty.handler.codec.http.HttpHeaders;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.requests.Request;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.Route.CompiledRoute;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.utils.requestbody.RequestBody;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Indicates that a {@link RestAction} has been executed.
 *
 * <p>Depending on the request and its result not all values have to be populated.
 */
public class HttpRequestEvent extends Event {
    private final Request<?> request;
    private final Response response;

    public HttpRequestEvent(@Nonnull Request<?> request, @Nonnull Response response) {
        super(request.getJDA());

        this.request = request;
        this.response = response;
    }

    @Nonnull
    public Request<?> getRequest() {
        return this.request;
    }

    @Nullable
    public RequestBody getRequestBody() {
        return this.request.getBody();
    }

    @Nullable
    public Object getRequestBodyRaw() {
        return this.request.getRawBody();
    }

    @Nullable
    public Map<String, String> getRequestHeaders() {
        return this.request.getHeaders();
    }

    @Nullable
    public Response getResponse() {
        return this.response;
    }

    @Nullable
    public InputStream getResponseBody() {
        return this.response.getBody();
    }

    @Nullable
    public DataArray getResponseBodyAsArray() {
        return this.response.getArray();
    }

    @Nullable
    public DataObject getResponseBodyAsObject() {
        return this.response.getObject();
    }

    @Nullable
    public String getResponseBodyAsString() {
        return this.response.getString();
    }

    @Nullable
    public HttpHeaders getResponseHeaders() {
        return this.response.getHeaders();
    }

    @Nonnull
    public Set<String> getCFRays() {
        return this.response.getCFRays();
    }

    @Nonnull
    @CheckReturnValue
    public RestAction<?> getRestAction() {
        return this.request.getRestAction();
    }

    @Nonnull
    public CompiledRoute getRoute() {
        return this.request.getRoute();
    }

    public boolean isRateLimit() {
        return this.response.isRateLimit();
    }
}
