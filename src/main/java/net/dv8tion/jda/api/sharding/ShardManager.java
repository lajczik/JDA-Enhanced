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

package net.dv8tion.jda.api.sharding;

import io.netty.channel.EventLoopGroup;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDA.Status;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.attribute.IGuildChannelContainer;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.InterfacedEventManager;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.MiscUtil;
import net.dv8tion.jda.api.utils.NettyConfig;
import net.dv8tion.jda.api.utils.cache.CacheView;
import net.dv8tion.jda.api.utils.cache.ChannelCacheView;
import net.dv8tion.jda.api.utils.cache.ShardCacheView;
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.requests.CompletedRestAction;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.cache.UnifiedChannelCacheView;
import org.jetbrains.annotations.Unmodifiable;
import reactor.netty.http.client.HttpClient;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This class acts as a manager for multiple shards.
 * It contains several methods to make your life with sharding easier.
 *
 * <br>Custom implementations may not support all methods and throw
 * {@link UnsupportedOperationException UnsupportedOperationExceptions} instead.
 *
 * @author Aljoscha Grebe
 */
public interface ShardManager extends IGuildChannelContainer<Channel> {
    /**
     * Adds all provided listeners to the event-listeners that will be used to handle events.
     *
     * <p>Note: when using the {@link InterfacedEventManager InterfacedEventListener} (default),
     * the given listener <b>must</b> be an instance of {@link EventListener}!
     *
     * @param  listeners
     *         The listener(s) which will react to events.
     *
     * @throws IllegalArgumentException
     *         If either listeners or one of it's objects is {@code null}.
     */
    default void addEventListener(@Nonnull Object... listeners) {
        Checks.noneNull(listeners, "listeners");
        this.getShardCache().forEach(jda -> jda.addEventListener(listeners));
    }

    /**
     * Removes all provided listeners from the event-listeners and no longer uses them to handle events.
     *
     * @param  listeners
     *         The listener(s) to be removed.
     *
     * @throws IllegalArgumentException
     *         If either listeners or one of it's objects is {@code null}.
     */
    default void removeEventListener(@Nonnull Object... listeners) {
        Checks.noneNull(listeners, "listeners");
        this.getShardCache().forEach(jda -> jda.removeEventListener(listeners));
    }

    /**
     * Adds listeners provided by the listener provider to each shard to the event-listeners that will be used to handle events.
     * The listener provider gets a shard id applied and is expected to return a listener.
     *
     * <p>Note: when using the {@link InterfacedEventManager InterfacedEventListener} (default),
     * the given listener <b>must</b> be an instance of {@link EventListener}!
     *
     * @param  eventListenerProvider
     *         The provider of listener(s) which will react to events.
     *
     * @throws IllegalArgumentException
     *         If the provided listener provider or any of the listeners or provides are {@code null}.
     */
    default void addEventListeners(@Nonnull IntFunction<Object> eventListenerProvider) {
        Checks.notNull(eventListenerProvider, "event listener provider");
        this.getShardCache().forEach(jda -> {
            Object listener = eventListenerProvider.apply(jda.getShardInfo().getShardId());
            if (listener != null) {
                jda.addEventListener(listener);
            }
        });
    }

    /**
     * Remove listeners from shards by their id.
     * The provider takes shard ids, and returns a collection of listeners that shall be removed from the respective
     * shards.
     *
     * @param  eventListenerProvider
     *         Gets shard ids applied and is expected to return a collection of listeners that shall be removed from
     *         the respective shards
     *
     * @throws IllegalArgumentException
     *         If the provided event listeners provider is {@code null}.
     */
    default void removeEventListeners(@Nonnull IntFunction<Collection<Object>> eventListenerProvider) {
        Checks.notNull(eventListenerProvider, "event listener provider");
        this.getShardCache()
                .forEach(jda -> jda.removeEventListener(
                        eventListenerProvider.apply(jda.getShardInfo().getShardId())));
    }

    /**
     * Remove a listener provider. This will stop further created / restarted shards from getting a listener added by
     * that provider.
     *
     * <p>Default is a no-op for backwards compatibility, see implementations like
     * {@link DefaultShardManager#removeEventListenerProvider(IntFunction)} for actual code
     *
     * @param  eventListenerProvider
     *         The provider of listeners that shall be removed.
     *
     * @throws IllegalArgumentException
     *         If the provided listener provider is {@code null}.
     */
    default void removeEventListenerProvider(@Nonnull IntFunction<Object> eventListenerProvider) {}

    /**
     * Returns the amount of shards queued for (re)connecting.
     *
     * @return The amount of shards queued for (re)connecting.
     */
    int getShardsQueued();

    /**
     * Returns the amount of running shards.
     *
     * @return The amount of running shards.
     */
    default int getShardsRunning() {
        return (int) this.getShardCache().size();
    }

    /**
     * Returns the amount of shards managed by this {@link ShardManager}.
     * This includes shards currently queued for a restart.
     *
     * @return The managed amount of shards.
     */
    default int getShardsTotal() {
        return this.getShardsQueued() + this.getShardsRunning();
    }

    /**
     * The {@link GatewayIntent GatewayIntents} for the JDA sessions of this shard manager.
     *
     * @return {@link EnumSet} of active gateway intents
     */
    @Nonnull
    default EnumSet<GatewayIntent> getGatewayIntents() {
        //noinspection ConstantConditions
        return getShardCache()
                .applyStream((stream) ->
                        stream.map(JDA::getGatewayIntents).findAny().orElse(EnumSet.noneOf(GatewayIntent.class)));
    }

    /**
     * Used to access application details of this bot.
     * <br>Since this is the same for every shard it picks {@link JDA#retrieveApplicationInfo()} from any shard.
     *
     * @throws IllegalStateException
     *         If there is no running shard
     *
     * @return The Application registry for this bot.
     */
    @Nonnull
    @CheckReturnValue
    default RestAction<ApplicationInfo> retrieveApplicationInfo() {
        return this.getShardCache().stream()
                .findAny()
                .orElseThrow(() -> new IllegalStateException("no active shards"))
                .retrieveApplicationInfo();
    }

    /**
     * The average time in milliseconds between all shards that discord took to respond to our last heartbeat.
     * This roughly represents the WebSocket ping of this session. If there are no shards running, this will return {@code -1}.
     *
     * <p><b>{@link RestAction} request times do not
     * correlate to this value!</b>
     *
     * @return The average time in milliseconds between heartbeat and the heartbeat ack response
     */
    default double getAverageGatewayPing() {
        return this.getShardCache().stream()
                .mapToLong(JDA::getGatewayPing)
                .filter(ping -> ping != -1)
                .average()
                .orElse(-1D);
    }

    /**
     * {@link SnowflakeCacheView} of
     * all cached {@link Category Categories} visible to this ShardManager instance.
     *
     * @return {@link SnowflakeCacheView}
     */
    @Override
    @Nonnull
    default SnowflakeCacheView<Category> getCategoryCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getCategoryCache));
    }

    /**
     * Retrieves a custom emoji matching the specified {@code id} if one is available in our cache.
     *
     * <p><b>Unicode emojis are not included as {@link RichCustomEmoji}!</b>
     *
     * @param  id
     *         The id of the requested {@link RichCustomEmoji}.
     *
     * @return An {@link RichCustomEmoji} represented by this id or null if none is found in
     *         our cache.
     */
    @Nullable
    default RichCustomEmoji getEmojiById(long id) {
        return this.getEmojiCache().getElementById(id);
    }

    /**
     * Retrieves a custom emoji matching the specified {@code id} if one is available in our cache.
     *
     * <p><b>Unicode emojis are not included as {@link RichCustomEmoji}!</b>
     *
     * @param  id
     *         The id of the requested {@link RichCustomEmoji}.
     *
     * @throws NumberFormatException
     *         If the provided {@code id} cannot be parsed by {@link Long#parseLong(String)}
     *
     * @return An {@link RichCustomEmoji} represented by this id or null if none is found in
     *         our cache.
     */
    @Nullable
    default RichCustomEmoji getEmojiById(@Nonnull String id) {
        return this.getEmojiCache().getElementById(id);
    }

    /**
     * Unified {@link SnowflakeCacheView} of
     * all cached {@link RichCustomEmoji RichCustomEmojis} visible to this ShardManager instance.
     *
     * @return Unified {@link SnowflakeCacheView}
     */
    @Nonnull
    default SnowflakeCacheView<RichCustomEmoji> getEmojiCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getEmojiCache));
    }

    /**
     * A collection of all known custom emojis (managed/restricted included).
     *
     * <p><b>Hint</b>: To check whether you can use a {@link RichCustomEmoji} in a specific
     * context you can use {@link RichCustomEmoji#canInteract(net.dv8tion.jda.api.entities.Member)} or {@link
     * RichCustomEmoji#canInteract(net.dv8tion.jda.api.entities.User, MessageChannel)}
     *
     * <p><b>Unicode emojis are not included as {@link RichCustomEmoji}!</b>
     *
     * <p>This copies the backing store into a list. This means every call
     * creates a new list with O(n) complexity. It is recommended to store this into
     * a local variable or use {@link #getEmojiCache()} and use its more efficient
     * versions of handling these values.
     *
     * @return An immutable list of custom emojis (which may or may not be available to usage).
     */
    @Nonnull
    @Unmodifiable
    default List<RichCustomEmoji> getEmojis() {
        return this.getEmojiCache().asList();
    }

    /**
     * An unmodifiable list of all {@link RichCustomEmoji RichCustomEmojis} that have the same name as the one
     * provided. <br>If there are no {@link RichCustomEmoji RichCustomEmojis} with the provided name, this will
     * return an empty list.
     *
     * <p><b>Unicode emojis are not included as {@link RichCustomEmoji}!</b>
     *
     * @param  name
     *         The name of the requested {@link RichCustomEmoji RichCustomEmojis}. Without colons.
     * @param  ignoreCase
     *         Whether to ignore case or not when comparing the provided name to each {@link
     *         RichCustomEmoji#getName()}.
     *
     * @return Possibly-empty list of all the {@link RichCustomEmoji RichCustomEmojis} that all have the same
     *         name as the provided name.
     */
    @Nonnull
    @Unmodifiable
    default List<RichCustomEmoji> getEmojisByName(@Nonnull String name, boolean ignoreCase) {
        return this.getEmojiCache().getElementsByName(name, ignoreCase);
    }

    /**
     * This returns the {@link Guild} which has the same id as the one provided.
     * <br>If there is no connected guild with an id that matches the provided one, this will return {@code null}.
     *
     * @param  id
     *         The id of the {@link Guild}.
     *
     * @return Possibly-null {@link Guild} with matching id.
     */
    @Nullable
    default Guild getGuildById(long id) {
        return getGuildCache().getElementById(id);
    }

    /**
     * This returns the {@link Guild} which has the same id as the one provided.
     * <br>If there is no connected guild with an id that matches the provided one, this will return {@code null}.
     *
     * @param  id
     *         The id of the {@link Guild}.
     *
     * @return Possibly-null {@link Guild} with matching id.
     */
    @Nullable
    default Guild getGuildById(@Nonnull String id) {
        return getGuildById(MiscUtil.parseSnowflake(id));
    }

    /**
     * An unmodifiable list of all {@link Guild Guilds} that have the same name as the one provided.
     * <br>If there are no {@link Guild Guilds} with the provided name, this will return an empty list.
     *
     * @param  name
     *         The name of the requested {@link Guild Guilds}.
     * @param  ignoreCase
     *         Whether to ignore case or not when comparing the provided name to each {@link Guild#getName()}.
     *
     * @return Possibly-empty list of all the {@link Guild Guilds} that all have the same name as the provided name.
     */
    @Nonnull
    @Unmodifiable
    default List<Guild> getGuildsByName(@Nonnull String name, boolean ignoreCase) {
        return this.getGuildCache().getElementsByName(name, ignoreCase);
    }

    /**
     * {@link SnowflakeCacheView} of
     * all cached {@link Guild Guilds} visible to this ShardManager instance.
     *
     * @return {@link SnowflakeCacheView}
     */
    @Nonnull
    default SnowflakeCacheView<Guild> getGuildCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getGuildCache));
    }

    /**
     * An unmodifiable List of all {@link Guild Guilds} that the logged account is connected to.
     * <br>If this account is not connected to any {@link Guild Guilds}, this will return
     * an empty list.
     *
     * <p>This copies the backing store into a list. This means every call
     * creates a new list with O(n) complexity. It is recommended to store this into
     * a local variable or use {@link #getGuildCache()} and use its more efficient
     * versions of handling these values.
     *
     * @return Possibly-empty list of all the {@link Guild Guilds} that this account is connected to.
     */
    @Nonnull
    @Unmodifiable
    default List<Guild> getGuilds() {
        return this.getGuildCache().asList();
    }

    /**
     * Gets all {@link Guild Guilds} that contain all given users as their members.
     *
     * @param  users
     *         The users which all the returned {@link Guild Guilds} must contain.
     *
     * @return Unmodifiable list of all {@link Guild} instances which have all {@link UserSnowflake Users} in them.
     */
    @Nonnull
    @Unmodifiable
    default List<Guild> getMutualGuilds(@Nonnull Collection<? extends UserSnowflake> users) {
        Checks.noneNull(users, "users");
        return this.getGuildCache().stream()
                .filter(guild -> users.stream().allMatch(guild::isMember))
                .toList();
    }

    /**
     * Gets all {@link Guild Guilds} that contain all given users as their members.
     *
     * @param  users
     *         The users which all the returned {@link Guild Guilds} must contain.
     *
     * @return Unmodifiable list of all {@link Guild} instances which have all {@link UserSnowflake Users} in them.
     */
    @Nonnull
    @Unmodifiable
    default List<Guild> getMutualGuilds(@Nonnull UserSnowflake... users) {
        Checks.notNull(users, "users");
        return this.getMutualGuilds(Arrays.asList(users));
    }

    /**
     * Attempts to retrieve a {@link User} object based on the provided id.
     * <br>This first calls {@link #getUserById(long)}, and if the return is {@code null} then a request
     * is made to the Discord servers.
     *
     * <p>The returned {@link RestAction} can encounter the following Discord errors:
     * <ul>
     *     <li>{@link ErrorResponse#UNKNOWN_USER ErrorResponse.UNKNOWN_USER}
     *     <br>Occurs when the provided id does not refer to a {@link User}
     *     known by Discord. Typically occurs when developers provide an incomplete id (cut short).</li>
     * </ul>
     *
     * @param  id
     *         The id of the requested {@link User}.
     *
     * @throws IllegalArgumentException
     *         If the provided id String is not a valid snowflake.
     * @throws IllegalStateException
     *         If there isn't any active shards.
     *
     * @return {@link RestAction} - Type: {@link User}
     *         <br>On request, gets the User with id matching provided id from Discord.
     */
    @Nonnull
    @CheckReturnValue
    default RestAction<User> retrieveUserById(@Nonnull String id) {
        return retrieveUserById(MiscUtil.parseSnowflake(id));
    }

    /**
     * Attempts to retrieve a {@link User} object based on the provided id.
     * <br>This first calls {@link #getUserById(long)}, and if the return is {@code null} then a request
     * is made to the Discord servers.
     *
     * <p>The returned {@link RestAction} can encounter the following Discord errors:
     * <ul>
     *     <li>{@link ErrorResponse#UNKNOWN_USER ErrorResponse.UNKNOWN_USER}
     *     <br>Occurs when the provided id does not refer to a {@link User}
     *     known by Discord. Typically occurs when developers provide an incomplete id (cut short).</li>
     * </ul>
     *
     * @param  id
     *         The id of the requested {@link User}.
     *
     * @throws IllegalStateException
     *         If there isn't any active shards.
     *
     * @return {@link RestAction} - Type: {@link User}
     *         <br>On request, gets the User with id matching provided id from Discord.
     */
    @Nonnull
    @CheckReturnValue
    default RestAction<User> retrieveUserById(long id) {
        JDA api = null;
        for (JDA shard : getShardCache()) {
            api = shard;
            EnumSet<GatewayIntent> intents = shard.getGatewayIntents();
            User user = shard.getUserById(id);
            boolean isUpdated =
                    intents.contains(GatewayIntent.GUILD_PRESENCES) || intents.contains(GatewayIntent.GUILD_MEMBERS);
            if (user != null && isUpdated) {
                return new CompletedRestAction<>(shard, user);
            }
        }

        if (api == null) {
            throw new IllegalStateException("no shards active");
        }

        JDAImpl jda = (JDAImpl) api;
        Route.CompiledRoute route = Route.Users.GET_USER.compile(Long.toUnsignedString(id));
        return new RestActionImpl<>(
                jda, route, (response, request) -> jda.getEntityBuilder().createUser(response.getObject()));
    }

    /**
     * Searches for the first user that has the matching Discord Tag.
     * <br>Format has to be in the form {@code Username#Discriminator} where the
     * username must be between 2 and 32 characters (inclusive) matching the exact casing and the discriminator
     * must be exactly 4 digits.
     *
     * <p>This will only check cached users!
     *
     * <p>This only checks users that are known to the currently logged in account (shards). If a user exists
     * with the tag that is not available in the {@link #getUserCache() User-Cache} it will not be detected.
     * <br>Currently Discord does not offer a way to retrieve a user by their discord tag.
     *
     * @param  tag
     *         The Discord Tag in the format {@code Username#Discriminator}
     *
     * @throws IllegalArgumentException
     *         If the provided tag is null or not in the described format
     *
     * @return The {@link User} for the discord tag or null if no user has the provided tag
     */
    @Nullable
    default User getUserByTag(@Nonnull String tag) {
        return getShardCache().applyStream(stream -> stream.map(jda -> jda.getUserByTag(tag))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));
    }

    /**
     * Searches for the first user that has the matching Discord Tag.
     * <br>Format has to be in the form {@code Username#Discriminator} where the
     * username must be between 2 and 32 characters (inclusive) matching the exact casing and the discriminator
     * must be exactly 4 digits.
     *
     * <p>This will only check cached users!
     *
     * <p>This only checks users that are known to the currently logged in account (shards). If a user exists
     * with the tag that is not available in the {@link #getUserCache() User-Cache} it will not be detected.
     * <br>Currently Discord does not offer a way to retrieve a user by their discord tag.
     *
     * @param  username
     *         The name of the user
     * @param  discriminator
     *         The discriminator of the user
     *
     * @throws IllegalArgumentException
     *         If the provided arguments are null or not in the described format
     *
     * @return The {@link User} for the discord tag or null if no user has the provided tag
     */
    @Nullable
    default User getUserByTag(@Nonnull String username, @Nonnull String discriminator) {
        return getShardCache().applyStream(stream -> stream.map(jda -> jda.getUserByTag(username, discriminator))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));
    }

    /**
     * An unmodifiable list of all known {@link PrivateChannel PrivateChannels}.
     *
     * <p>This copies the backing store into a list. This means every call
     * creates a new list with O(n) complexity. It is recommended to store this into
     * a local variable or use {@link #getPrivateChannelCache()} and use its more efficient
     * versions of handling these values.
     *
     * @return Possibly-empty list of all {@link PrivateChannel PrivateChannels}.
     */
    @Nonnull
    @Unmodifiable
    default List<PrivateChannel> getPrivateChannels() {
        return this.getPrivateChannelCache().asList();
    }

    /**
     * Retrieves the {@link Role} associated to the provided id. <br>This iterates
     * over all {@link Guild Guilds} and check whether a Role from that Guild is assigned
     * to the specified ID and will return the first that can be found.
     *
     * @param  id
     *         The id of the searched Role
     *
     * @return Possibly-null {@link Role} for the specified ID
     */
    @Nullable
    default Role getRoleById(long id) {
        return this.getRoleCache().getElementById(id);
    }

    /**
     * Retrieves the {@link Role} associated to the provided id. <br>This iterates
     * over all {@link Guild Guilds} and check whether a Role from that Guild is assigned
     * to the specified ID and will return the first that can be found.
     *
     * @param  id
     *         The id of the searched Role
     *
     * @throws NumberFormatException
     *         If the provided {@code id} cannot be parsed by {@link Long#parseLong(String)}
     *
     * @return Possibly-null {@link Role} for the specified ID
     */
    @Nullable
    default Role getRoleById(@Nonnull String id) {
        return this.getRoleCache().getElementById(id);
    }

    /**
     * Unified {@link SnowflakeCacheView} of
     * all cached {@link Role Roles} visible to this ShardManager instance.
     *
     * @return Unified {@link SnowflakeCacheView}
     */
    @Nonnull
    default SnowflakeCacheView<Role> getRoleCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getRoleCache));
    }

    /**
     * All {@link Role Roles} this ShardManager instance can see. <br>This will iterate over each
     * {@link Guild} retrieved from {@link #getGuilds()} and collect its {@link
     * net.dv8tion.jda.api.entities.Guild#getRoles() Guild.getRoles()}.
     *
     * <p>This copies the backing store into a list. This means every call
     * creates a new list with O(n) complexity. It is recommended to store this into
     * a local variable or use {@link #getRoleCache()} and use its more efficient
     * versions of handling these values.
     *
     * @return Immutable List of all visible Roles
     */
    @Nonnull
    @Unmodifiable
    default List<Role> getRoles() {
        return this.getRoleCache().asList();
    }

    /**
     * Retrieves all {@link Role Roles} visible to this ShardManager instance.
     * <br>This simply filters the Roles returned by {@link #getRoles()} with the provided name, either using
     * {@link String#equals(Object)} or {@link String#equalsIgnoreCase(String)} on {@link Role#getName()}.
     *
     * @param  name
     *         The name for the Roles
     * @param  ignoreCase
     *         Whether to use {@link String#equalsIgnoreCase(String)}
     *
     * @return Immutable List of all Roles matching the parameters provided.
     */
    @Nonnull
    @Unmodifiable
    default List<Role> getRolesByName(@Nonnull String name, boolean ignoreCase) {
        return this.getRoleCache().getElementsByName(name, ignoreCase);
    }

    /**
     * This returns the {@link PrivateChannel} which has the same id as the one provided.
     * <br>If there is no known {@link PrivateChannel} with an id that matches the provided
     * one, then this will return {@code null}.
     *
     * @param  id
     *         The id of the {@link PrivateChannel}.
     *
     * @return Possibly-null {@link PrivateChannel} with matching id.
     */
    @Nullable
    default PrivateChannel getPrivateChannelById(long id) {
        return this.getPrivateChannelCache().getElementById(id);
    }

    /**
     * This returns the {@link PrivateChannel} which has the same id as the one provided.
     * <br>If there is no known {@link PrivateChannel} with an id that matches the provided
     * one, this will return {@code null}.
     *
     * @param  id
     *         The id of the {@link PrivateChannel}.
     *
     * @throws NumberFormatException
     *         If the provided {@code id} cannot be parsed by {@link Long#parseLong(String)}
     *
     * @return Possibly-null {@link PrivateChannel} with matching id.
     */
    @Nullable
    default PrivateChannel getPrivateChannelById(@Nonnull String id) {
        return this.getPrivateChannelCache().getElementById(id);
    }

    /**
     * {@link SnowflakeCacheView} of
     * all cached {@link PrivateChannel PrivateChannels} visible to this ShardManager instance.
     *
     * @return {@link SnowflakeCacheView}
     */
    @Nonnull
    default SnowflakeCacheView<PrivateChannel> getPrivateChannelCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getPrivateChannelCache));
    }

    @Override
    @Nullable
    default GuildChannel getGuildChannelById(long id) {
        GuildChannel channel;
        for (JDA shard : getShards()) {
            channel = shard.getGuildChannelById(id);
            if (channel != null) {
                return channel;
            }
        }

        return null;
    }

    @Override
    @Nullable
    default GuildChannel getGuildChannelById(@Nonnull ChannelType type, long id) {
        Checks.notNull(type, "ChannelType");
        GuildChannel channel;
        for (JDA shard : getShards()) {
            channel = shard.getGuildChannelById(type, id);
            if (channel != null) {
                return channel;
            }
        }

        return null;
    }

    @Override
    @Nonnull
    default SnowflakeCacheView<TextChannel> getTextChannelCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getTextChannelCache));
    }

    @Override
    @Nonnull
    default SnowflakeCacheView<VoiceChannel> getVoiceChannelCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getVoiceChannelCache));
    }

    @Nonnull
    @Override
    default SnowflakeCacheView<StageChannel> getStageChannelCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getStageChannelCache));
    }

    @Nonnull
    @Override
    default SnowflakeCacheView<ThreadChannel> getThreadChannelCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getThreadChannelCache));
    }

    @Nonnull
    @Override
    default SnowflakeCacheView<NewsChannel> getNewsChannelCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getNewsChannelCache));
    }

    @Nonnull
    @Override
    default SnowflakeCacheView<ForumChannel> getForumChannelCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getForumChannelCache));
    }

    @Nonnull
    @Override
    default SnowflakeCacheView<MediaChannel> getMediaChannelCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getMediaChannelCache));
    }

    @Nonnull
    @Override
    default ChannelCacheView<Channel> getChannelCache() {
        return new UnifiedChannelCacheView<>(() -> this.getShardCache().stream().map(JDA::getChannelCache));
    }

    /**
     * This returns the {@link JDA} instance which has the same id as the one provided.
     * <br>If there is no shard with an id that matches the provided one, this will return {@code null}.
     *
     * @param  id
     *         The id of the shard.
     *
     * @return The {@link JDA} instance with the given shardId or
     *         {@code null} if no shard has the given id
     */
    @Nullable
    default JDA getShardById(int id) {
        return this.getShardCache().getElementById(id);
    }

    /**
     * This returns the {@link JDA} instance which has the same id as the one provided.
     * <br>If there is no shard with an id that matches the provided one, this will return {@code null}.
     *
     * @param  id
     *         The id of the shard.
     *
     * @return The {@link JDA} instance with the given shardId or
     *         {@code null} if no shard has the given id
     */
    @Nullable
    default JDA getShardById(@Nonnull String id) {
        return this.getShardCache().getElementById(id);
    }

    /**
     * Unified {@link ShardCacheView ShardCacheView} of
     * all cached {@link JDA} bound to this ShardManager instance.
     *
     * @return Unified {@link ShardCacheView ShardCacheView}
     */
    @Nonnull
    ShardCacheView getShardCache();

    /**
     * Gets all {@link JDA} instances bound to this ShardManager.
     *
     * <p>This copies the backing store into a list. This means every call
     * creates a new list with O(n) complexity. It is recommended to store this into
     * a local variable or use {@link #getShardCache()} and use its more efficient
     * versions of handling these values.
     *
     * @return An immutable list of all managed {@link JDA} instances.
     */
    @Nonnull
    @Unmodifiable
    default List<JDA> getShards() {
        return this.getShardCache().asList();
    }

    /**
     * This returns the {@link JDA.Status} of the shard which has the same id as the one provided.
     * <br>If there is no shard with an id that matches the provided one, this will return {@code null}.
     *
     * @param  shardId
     *         The id of the shard.
     *
     * @return The {@link JDA.Status} of the shard with the given shardId or
     *         {@code null} if no shard has the given id
     */
    @Nullable
    default JDA.Status getStatus(int shardId) {
        JDA jda = this.getShardCache().getElementById(shardId);
        return jda == null ? null : jda.getStatus();
    }

    /**
     * Gets the current {@link JDA.Status Status} of all shards.
     *
     * @return All current shard statuses.
     */
    @Nonnull
    @Unmodifiable
    default Map<JDA, Status> getStatuses() {
        return Collections.unmodifiableMap(
                this.getShardCache().stream().collect(Collectors.toMap(Function.identity(), JDA::getStatus)));
    }

    /**
     * This returns the {@link User} which has the same id as the one provided.
     * <br>If there is no visible user with an id that matches the provided one, this will return {@code null}.
     *
     * @param  id
     *         The id of the requested {@link User}.
     *
     * @return Possibly-null {@link User} with matching id.
     */
    @Nullable
    default User getUserById(long id) {
        return this.getUserCache().getElementById(id);
    }

    /**
     * This returns the {@link User} which has the same id as the one provided.
     * <br>If there is no visible user with an id that matches the provided one, this will return {@code null}.
     *
     * @param  id
     *         The id of the requested {@link User}.
     *
     * @return Possibly-null {@link User} with matching id.
     */
    @Nullable
    default User getUserById(@Nonnull String id) {
        return this.getUserCache().getElementById(id);
    }

    /**
     * {@link SnowflakeCacheView} of
     * all cached {@link User Users} visible to this ShardManager instance.
     *
     * @return {@link SnowflakeCacheView}
     */
    @Nonnull
    default SnowflakeCacheView<User> getUserCache() {
        return CacheView.allSnowflakes(() -> this.getShardCache().stream().map(JDA::getUserCache));
    }

    /**
     * An unmodifiable list of all {@link User Users} that share a
     * {@link Guild} with the currently logged in account.
     * <br>This list will never contain duplicates and represents all {@link User Users}
     * that JDA can currently see.
     *
     * <p>If the developer is sharding, then only users from guilds connected to the specifically logged in
     * shard will be returned in the List.
     *
     * <p>This copies the backing store into a list. This means every call
     * creates a new list with O(n) complexity. It is recommended to store this into
     * a local variable or use {@link #getUserCache()} and use its more efficient
     * versions of handling these values.
     *
     * @return List of all {@link User Users} that are visible to JDA.
     */
    @Nonnull
    @Unmodifiable
    default List<User> getUsers() {
        return this.getUserCache().asList();
    }

    /**
     * Restarts all shards, shutting old ones down first.
     *
     * <p>As all shards need to connect to discord again this will take equally long as the startup of a new ShardManager
     * (using the 5000ms + backoff as delay between starting new JDA instances).
     *
     * @throws RejectedExecutionException
     *         If {@link #shutdown()} has already been invoked
     */
    void restart();

    /**
     * Restarts the shards with the given id only.
     * <br> If there is no shard with the given Id, this method acts like {@link #start(int)}.
     *
     * @param  id
     *         The id of the target shard
     *
     * @throws IllegalArgumentException
     *         If shardId is negative or higher than maxShardId
     * @throws RejectedExecutionException
     *         If {@link #shutdown()} has already been invoked
     */
    void restart(int id);

    /**
     * Sets the {@link Activity} for all shards.
     * <br>An Activity can be retrieved via {@link Activity#playing(String)}.
     * For streams you provide a valid streaming url as second parameter.
     *
     * <p>This will also change the activity for shards that are created in the future.
     *
     * @param  activity
     *         A {@link Activity} instance or null to reset
     *
     * @see    Activity#playing(String)
     * @see    Activity#streaming(String, String)
     */
    default void setActivity(@Nullable Activity activity) {
        this.setActivityProvider(id -> activity);
    }

    /**
     * Sets provider that provider the {@link Activity} for all shards.
     * <br>A Activity can be retrieved via {@link Activity#playing(String)}.
     * For streams you provide a valid streaming url as second parameter.
     *
     * <p>This will also change the provider for shards that are created in the future.
     *
     * @param  activityProvider
     *         Provider for an {@link Activity} instance or null to reset
     *
     * @see    Activity#playing(String)
     * @see    Activity#streaming(String, String)
     */
    default void setActivityProvider(@Nullable IntFunction<? extends Activity> activityProvider) {
        this.getShardCache().forEach(jda -> jda.getPresence()
                .setActivity(
                        activityProvider == null
                                ? null
                                : activityProvider.apply(jda.getShardInfo().getShardId())));
    }

    /**
     * Sets whether all instances should be marked as afk or not
     *
     * <p>This is relevant to client accounts to monitor
     * whether new messages should trigger mobile push-notifications.
     *
     * <p>This will also change the value for shards that are created in the future.
     *
     * @param idle
     *        boolean
     */
    default void setIdle(boolean idle) {
        this.setIdleProvider(id -> idle);
    }

    /**
     * Sets the provider that decides for all shards whether they should be marked as afk or not.
     *
     * <p>This will also change the provider for shards that are created in the future.
     *
     * @param idleProvider
     *        Provider for a boolean
     */
    default void setIdleProvider(@Nonnull IntFunction<Boolean> idleProvider) {
        this.getShardCache().forEach(jda -> jda.getPresence()
                .setIdle(idleProvider.apply(jda.getShardInfo().getShardId())));
    }

    /**
     * Sets the {@link OnlineStatus} and {@link Activity} for all shards.
     *
     * <p>This will also change the status for shards that are created in the future.
     *
     * @param  status
     *         The {@link OnlineStatus}
     *         to be used (OFFLINE/null {@literal ->} INVISIBLE)
     * @param  activity
     *         A {@link Activity} instance or null to reset
     *
     * @throws IllegalArgumentException
     *         If the provided OnlineStatus is {@link OnlineStatus#UNKNOWN UNKNOWN}
     *
     * @see    Activity#playing(String)
     * @see    Activity#streaming(String, String)
     */
    default void setPresence(@Nullable OnlineStatus status, @Nullable Activity activity) {
        this.setPresenceProvider(id -> status, id -> activity);
    }

    /**
     * Sets the provider that provides the {@link OnlineStatus} and
     * {@link Activity} for all shards.
     *
     * <p>This will also change the status for shards that are created in the future.
     *
     * @param  statusProvider
     *         The {@link OnlineStatus}
     *         to be used (OFFLINE/null {@literal ->} INVISIBLE)
     * @param  activityProvider
     *         A {@link Activity} instance or null to reset
     *
     * @throws IllegalArgumentException
     *         If the provided OnlineStatus is {@link OnlineStatus#UNKNOWN UNKNOWN}
     *
     * @see    Activity#playing(String)
     * @see    Activity#streaming(String, String)
     */
    default void setPresenceProvider(
            @Nullable IntFunction<OnlineStatus> statusProvider,
            @Nullable IntFunction<? extends Activity> activityProvider) {
        this.getShardCache().forEach(jda -> jda.getPresence()
                .setPresence(
                        statusProvider == null
                                ? null
                                : statusProvider.apply(jda.getShardInfo().getShardId()),
                        activityProvider == null
                                ? null
                                : activityProvider.apply(jda.getShardInfo().getShardId())));
    }

    /**
     * Sets the {@link OnlineStatus} for all shards.
     *
     * <p>This will also change the status for shards that are created in the future.
     *
     * @param  status
     *         The {@link OnlineStatus}
     *         to be used (OFFLINE/null {@literal ->} INVISIBLE)
     *
     * @throws IllegalArgumentException
     *         If the provided OnlineStatus is {@link OnlineStatus#UNKNOWN UNKNOWN}
     */
    default void setStatus(@Nullable OnlineStatus status) {
        this.setStatusProvider(id -> status);
    }

    /**
     * Sets the provider that provides the {@link OnlineStatus} for all shards.
     *
     * <p>This will also change the provider for shards that are created in the future.
     *
     * @param  statusProvider
     *         The {@link OnlineStatus}
     *         to be used (OFFLINE/null {@literal ->} INVISIBLE)
     *
     * @throws IllegalArgumentException
     *         If the provided OnlineStatus is {@link OnlineStatus#UNKNOWN UNKNOWN}
     */
    default void setStatusProvider(@Nullable IntFunction<OnlineStatus> statusProvider) {
        this.getShardCache().forEach(jda -> jda.getPresence()
                .setStatus(
                        statusProvider == null
                                ? null
                                : statusProvider.apply(jda.getShardInfo().getShardId())));
    }

    /**
     * Shuts down all JDA shards, closing all their connections.
     * After this method has been called the ShardManager instance can not be used anymore.
     *
     * <br>This will shutdown the internal queue worker for (re-)starts of shards.
     * This means {@link #restart(int)}, {@link #restart()}, and {@link #start(int)} will throw
     * {@link RejectedExecutionException}.
     *
     * <p>This will interrupt the default JDA event thread, due to the gateway connection being interrupted.
     */
    void shutdown();

    /**
     * Shuts down the shard with the given id only.
     * <br>If there is no shard with the given id, this will do nothing.
     *
     * @param shardId
     *        The id of the shard that should be stopped
     */
    void shutdown(int shardId);

    /**
     * Adds a new shard with the given id to this ShardManager and starts it.
     *
     * @param  shardId
     *         The id of the shard that should be started
     *
     * @throws RejectedExecutionException
     *         If {@link #shutdown()} has already been invoked
     */
    void start(int shardId);

    /**
     * Initializes and starts all shards. This should only be called once.
     *
     * @throws InvalidTokenException
     *         If the provided token is invalid.
     */
    void login();

    /**
     * The immutable {@link NettyConfig} used by this ShardManager instance.
     * <br>This instance is constant for the lifetime of this {@link ShardManager} instance.
     *
     * @return The constant {@link NettyConfig}
     */
    @Nonnull
    NettyConfig getNettyConfig();

    /**
     * The {@link HttpClient} used by this ShardManager across shards.
     * <br>This instance is constant for the lifetime of this {@link ShardManager} instance.
     *
     * @return The constant {@link HttpClient}
     */
    @Nonnull
    HttpClient getHttpClient();

    /**
     * The {@link EventLoopGroup} used by the HTTP client across shards.
     * <br>This instance is constant for the lifetime of this {@link ShardManager} instance.
     *
     * @return The constant HTTP client {@link EventLoopGroup}
     */
    @Nonnull
    EventLoopGroup getHttpClientEventLoopGroup();

    /**
     * The {@link EventLoopGroup} used for WebSocket connections across shards.
     * <br>This instance is constant for the lifetime of this {@link ShardManager} instance.
     *
     * @return The constant WebSocket {@link EventLoopGroup}
     */
    @Nonnull
    EventLoopGroup getWebsocketEventLoopGroup();

    /**
     * The {@link EventLoopGroup} used for Audio connections across shards.
     * <br>If not explicitly configured, this shares the same group as {@link #getWebsocketEventLoopGroup()}.
     * <br>This instance is constant for the lifetime of this {@link ShardManager} instance.
     *
     * <p><b>Music Bot Recommendation:</b>
     * For bots with heavy audio usage (e.g. music bots serving many concurrent voice channels), it is recommended
     * to either increase WebSocket event loop threads or configure dedicated audio threads / group in {@link NettyConfig}
     * so that audio UDP packets (20ms frames) and Voice WebSocket events are processed on an isolated event loop group
     * rather than competing with Discord Gateway events.
     *
     * @return The constant Audio {@link EventLoopGroup}
     */
    @Nonnull
    EventLoopGroup getAudioEventLoopGroup();
}
