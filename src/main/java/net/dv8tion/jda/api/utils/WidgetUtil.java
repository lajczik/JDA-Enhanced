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

package net.dv8tion.jda.api.utils;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Widget;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.requests.RestConfig;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.entities.WidgetImpl;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.Helpers;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The WidgetUtil is a class for interacting with various facets of Discord's
 * guild widgets
 *
 * @author John A. Grosh
 */
public class WidgetUtil {
    public static final String WIDGET_PNG = RestConfig.DEFAULT_BASE_URL + "guilds/%s/widget.png?style=%s";
    public static final String WIDGET_URL = RestConfig.DEFAULT_BASE_URL + "guilds/%s/widget.json";
    public static final String WIDGET_HTML =
            "<iframe src=\"https://discord.com/widget?id=%s&theme=%s\" width=\"%d\" height=\"%d\" allowtransparency=\"true\" frameborder=\"0\"></iframe>";

    /**
     * Gets the banner image for the specified guild of the specified type.
     * <br>This banner will only be available if the guild in question has the
     * Widget enabled.
     *
     * @param  guild
     *         The guild
     * @param  type
     *         The type (visual style) of the banner
     *
     * @return A String containing the URL of the banner image
     */
    @Nonnull
    public static String getWidgetBanner(@Nonnull Guild guild, @Nonnull BannerType type) {
        Checks.notNull(guild, "Guild");
        return getWidgetBanner(guild.getId(), type);
    }

    /**
     * Gets the banner image for the specified guild of the specified type.
     * <br>This banner will only be available if the guild in question has the
     * Widget enabled. Additionally, this method can be used independently of
     * being on the guild in question.
     *
     * @param  guildId
     *         the guild ID
     * @param  type
     *         The type (visual style) of the banner
     *
     * @return A String containing the URL of the banner image
     */
    @Nonnull
    public static String getWidgetBanner(@Nonnull String guildId, @Nonnull BannerType type) {
        Checks.notNull(guildId, "GuildId");
        Checks.notNull(type, "BannerType");
        return String.format(WIDGET_PNG, guildId, type.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Gets the pre-made HTML Widget for the specified guild using the specified
     * settings. The widget will only display correctly if the guild in question
     * has the Widget enabled.
     *
     * @param  guild
     *         the guild
     * @param  theme
     *         the theme, light or dark
     * @param  width
     *         the width of the widget
     * @param  height
     *         the height of the widget
     *
     * @return a String containing the pre-made widget with the supplied settings
     */
    @Nonnull
    public static String getPremadeWidgetHtml(@Nonnull Guild guild, @Nonnull WidgetTheme theme, int width, int height) {
        Checks.notNull(guild, "Guild");
        return getPremadeWidgetHtml(guild.getId(), theme, width, height);
    }

    /**
     * Gets the pre-made HTML Widget for the specified guild using the specified
     * settings. The widget will only display correctly if the guild in question
     * has the Widget enabled. Additionally, this method can be used independently
     * of being on the guild in question.
     *
     * @param  guildId
     *         the guild ID
     * @param  theme
     *         the theme, light or dark
     * @param  width
     *         the width of the widget
     * @param  height
     *         the height of the widget
     *
     * @return a String containing the pre-made widget with the supplied settings
     */
    @Nonnull
    public static String getPremadeWidgetHtml(
            @Nonnull String guildId, @Nonnull WidgetTheme theme, int width, int height) {
        Checks.notNull(guildId, "GuildId");
        Checks.notNull(theme, "WidgetTheme");
        Checks.notNegative(width, "Width");
        Checks.notNegative(height, "Height");
        return Helpers.format(WIDGET_HTML, guildId, theme.name().toLowerCase(Locale.ROOT), width, height);
    }

    /**
     * Makes a GET request to get the information for a Guild's widget. This
     * widget (if available) contains information about the guild, including the
     * Guild's name, an invite code (if set), a list of voice channels, and a
     * list of online members (plus the voice states of any members in voice
     * channels).
     *
     * <p>This Widget can be obtained from any valid guild ID that has
     * it enabled; no accounts need to be on the server to access this information.
     *
     * @param  guildId
     *         The id of the Guild
     *
     * @throws RateLimitedException
     *         If the request was rate limited, <b>respect the timeout</b>!
     * @throws NumberFormatException
     *         If the provided {@code guildId} cannot be parsed by {@link Long#parseLong(String)}
     *
     * @return {@code null} if the provided guild ID is not a valid Discord guild ID
     *         <br>a Widget object with null fields and isAvailable() returning
     *         false if the guild ID is valid but the guild in question does not
     *         have the widget enabled
     *         <br>a filled-in Widget object if the guild ID is valid and the guild
     *         in question has the widget enabled.
     */
    @Nullable
    public static Widget getWidget(@Nonnull String guildId) throws RateLimitedException {
        return getWidget(MiscUtil.parseSnowflake(guildId));
    }

    /**
     * Makes a GET request to get the information for a Guild's widget. This
     * widget (if available) contains information about the guild, including the
     * Guild's name, an invite code (if set), a list of voice channels, and a
     * list of online members (plus the voice states of any members in voice
     * channels).
     *
     * <p>This Widget can be obtained from any valid guild ID that has
     * it enabled; no accounts need to be on the server to access this information.
     *
     * @param  guildId
     *         The id of the Guild
     *
     * @throws UncheckedIOException
     *         If an I/O error occurs
     * @throws RateLimitedException
     *         If the request was rate limited, <b>respect the timeout</b>!
     *
     * @return {@code null} if the provided guild ID is not a valid Discord guild ID
     *         <br>a Widget object with null fields and isAvailable() returning
     *         false if the guild ID is valid but the guild in question does not
     *         have the widget enabled
     *         <br>a filled-in Widget object if the guild ID is valid and the guild
     *         in question has the widget enabled.
     */
    private static final HttpClient DEFAULT_HTTP_CLIENT = HttpClient.create(ConnectionProvider.builder("JDA-Widget")
                    .maxConnections(64)
                    .pendingAcquireTimeout(Duration.ofSeconds(45))
                    .maxIdleTime(Duration.ofSeconds(10))
                    .build())
            .compress(true);

    /**
     * Makes an asynchronous request to get the information for a Guild's widget as a Project Reactor {@link Mono}.
     *
     * @param  guildId
     *         The id of the Guild
     *
     * @return Never-null {@link Mono} representing the widget retrieval
     */
    @Nonnull
    @CheckReturnValue
    public static Mono<Widget> getWidgetAsMono(long guildId) {
        return getWidgetAsMono(guildId, (Scheduler) null);
    }

    /**
     * Makes an asynchronous request to get the information for a Guild's widget as a Project Reactor {@link Mono},
     * dispatching on the callback scheduler of the provided {@link JDA} instance.
     *
     * @param  guildId
     *         The id of the Guild
     * @param  jda
     *         The JDA instance whose callback scheduler should be used, or null to use the default
     *
     * @return Never-null {@link Mono} representing the widget retrieval
     */
    @Nonnull
    @CheckReturnValue
    public static Mono<Widget> getWidgetAsMono(long guildId, @Nullable JDA jda) {
        return getWidgetAsMono(guildId, jda == null ? null : jda.getCallbackScheduler());
    }

    /**
     * Makes an asynchronous request to get the information for a Guild's widget as a Project Reactor {@link Mono},
     * dispatching on the provided {@link Scheduler}.
     *
     * @param  guildId
     *         The id of the Guild
     * @param  scheduler
     *         The {@link Scheduler} to publish on, or null to use the default
     *
     * @return Never-null {@link Mono} representing the widget retrieval
     */
    @Nonnull
    @CheckReturnValue
    public static Mono<Widget> getWidgetAsMono(long guildId, @Nullable Scheduler scheduler) {
        if (scheduler == null) {
            scheduler = FileProxy.getDefaultScheduler();
            if (scheduler == null) {
                scheduler = Schedulers.boundedElastic();
            }
        }
        Scheduler finalScheduler = scheduler;
        return DEFAULT_HTTP_CLIENT
                .request(HttpMethod.GET)
                .uri(String.format(WIDGET_URL, guildId))
                .send((req, out) -> {
                    req.header(HttpHeaderNames.USER_AGENT, RestConfig.USER_AGENT)
                            .header(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                    return out;
                })
                .responseSingle((response, byteBufMono) -> {
                    int code = response.status().code();
                    if (code == 400 || code == 404) {
                        return byteBufMono.ignoreElement().then(Mono.empty());
                    }
                    if (code == 403) {
                        return byteBufMono.ignoreElement().then(Mono.just((Widget) new WidgetImpl(guildId)));
                    }
                    return byteBufMono.map(buf -> {
                        if (code == 200) {
                            return (Widget) new WidgetImpl(DataObject.fromJson(buf));
                        } else if (code == 429) {
                            long retryAfter = 0;
                            try {
                                retryAfter = DataObject.fromJson(buf).getLong("retry_after");
                            } catch (Exception ignored) {
                            }
                            throw new RuntimeException(new RateLimitedException(WIDGET_URL, retryAfter));
                        } else {
                            throw new IllegalStateException("An unknown status was returned: " + code + " "
                                    + response.status().reasonPhrase());
                        }
                    });
                })
                .publishOn(finalScheduler);
    }

    /**
     * Makes an asynchronous request to get the information for a Guild's widget as a {@link CompletableFuture}.
     *
     * @param  guildId
     *         The id of the Guild
     *
     * @return Never-null {@link CompletableFuture} representing the widget retrieval
     */
    @Nonnull
    @CheckReturnValue
    public static CompletableFuture<Widget> getWidgetAsync(long guildId) {
        return getWidgetAsMono(guildId).toFuture();
    }

    @Nullable
    public static Widget getWidget(long guildId) throws RateLimitedException {
        Checks.notNull(guildId, "GuildId");

        try {
            return getWidgetAsMono(guildId).block();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RateLimitedException) {
                throw (RateLimitedException) cause;
            }
            if (e instanceof RateLimitedException) {
                throw (RateLimitedException) e;
            }
            if (cause instanceof IOException) {
                throw new UncheckedIOException((IOException) cause);
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Represents the available banner types
     * <br>Each of these has a different appearance:
     *
     * <p>
     * <br><b>Shield</b> - tiny, only contains Discord logo and online count
     * <br><b>Banner1</b> - medium, contains server name, icon, and online count, and a "Powered by Discord" bar on the bottom
     * <br><b>Banner2</b> - small, contains server name, icon, and online count, and a Discord logo on the side
     * <br><b>Banner3</b> - medium, contains server name, icon, and online count, and a Discord logo with a "Chat Now" bar on the bottom
     * <br><b>Banner4</b> - large, contains a very big Discord logo, server name, icon, and online count, and a big "Join My Server" button
     */
    public enum BannerType {
        SHIELD,
        BANNER1,
        BANNER2,
        BANNER3,
        BANNER4
    }

    /**
     * Represents the color scheme of the widget
     * <br>These color themes match Discord's dark and light themes
     */
    public enum WidgetTheme {
        LIGHT,
        DARK
    }
}
