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

package net.dv8tion.jda.internal.entities.channel.concrete.detached;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.channel.concrete.MediaChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.EmojiUnion;
import net.dv8tion.jda.api.managers.channel.concrete.MediaChannelManager;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.entities.channel.middleman.AbstractGuildChannelImpl;
import net.dv8tion.jda.internal.entities.channel.mixin.attribute.IInteractionPermissionMixin;
import net.dv8tion.jda.internal.entities.channel.mixin.concrete.MediaChannelMixin;
import net.dv8tion.jda.internal.entities.emoji.CustomEmojiImpl;
import net.dv8tion.jda.internal.interactions.ChannelInteractionPermissions;
import net.dv8tion.jda.internal.utils.cache.SortedSnowflakeCacheViewImpl;

import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;

public class DetachedMediaChannelImpl extends AbstractGuildChannelImpl<DetachedMediaChannelImpl>
        implements MediaChannel,
                GuildChannelUnion,
                MediaChannelMixin<DetachedMediaChannelImpl>,
                IInteractionPermissionMixin<DetachedMediaChannelImpl> {
    private final SortedSnowflakeCacheViewImpl<ForumTag> tagCache =
            new SortedSnowflakeCacheViewImpl<>(ForumTag.class, ForumTag::getName, Comparator.naturalOrder());
    protected int defaultThreadSlowmode;
    private ChannelInteractionPermissions interactionPermissions;
    private Emoji defaultReaction;
    private String topic;
    private long parentCategoryId;
    private boolean nsfw = false;
    private int position;
    private int slowmode;
    private int defaultSortOrder;

    public DetachedMediaChannelImpl(long id, Guild guild) {
        super(id, guild);
    }

    @Override
    public boolean isDetached() {
        return true;
    }

    @Nonnull
    @Override
    public MediaChannelManager getManager() {
        throw detachedException();
    }

    @Nonnull
    @Override
    public List<Member> getMembers() {
        throw detachedException();
    }

    @Nonnull
    @Override
    public SortedSnowflakeCacheViewImpl<ForumTag> getAvailableTagCache() {
        return tagCache;
    }

    @Override
    public Long2ObjectMap<PermissionOverride> getPermissionOverrideMap() {
        throw detachedException();
    }

    @Nonnull
    @Override
    public ChannelInteractionPermissions getInteractionPermissions() {
        return interactionPermissions;
    }

    @Nonnull
    @Override
    public DetachedMediaChannelImpl setInteractionPermissions(
            @Nonnull ChannelInteractionPermissions interactionPermissions) {
        this.interactionPermissions = interactionPermissions;
        return this;
    }

    @Override
    public boolean isNSFW() {
        return nsfw;
    }

    @Override
    public DetachedMediaChannelImpl setNSFW(boolean nsfw) {
        this.nsfw = nsfw;
        return this;
    }

    @Override
    public int getPositionRaw() {
        return position;
    }

    @Override
    public long getParentCategoryIdLong() {
        return parentCategoryId;
    }

    @Override
    public int getSlowmode() {
        return slowmode;
    }

    @Override
    public DetachedMediaChannelImpl setSlowmode(int slowmode) {
        this.slowmode = slowmode;
        return this;
    }

    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public DetachedMediaChannelImpl setTopic(String topic) {
        this.topic = topic;
        return this;
    }

    // Setters

    @Override
    public EmojiUnion getDefaultReaction() {
        return (EmojiUnion) defaultReaction;
    }

    @Override
    public DetachedMediaChannelImpl setDefaultReaction(DataObject emoji) {
        if (emoji != null && !emoji.isNull("emoji_id")) {
            this.defaultReaction = new CustomEmojiImpl("", emoji.getUnsignedLong("emoji_id"), false);
        } else if (emoji != null && !emoji.isNull("emoji_name")) {
            this.defaultReaction = Emoji.fromUnicode(emoji.getString("emoji_name"));
        } else {
            this.defaultReaction = null;
        }
        return this;
    }

    @Override
    public int getDefaultThreadSlowmode() {
        return defaultThreadSlowmode;
    }

    @Override
    public DetachedMediaChannelImpl setDefaultThreadSlowmode(int defaultThreadSlowmode) {
        this.defaultThreadSlowmode = defaultThreadSlowmode;
        return this;
    }

    @Nonnull
    @Override
    public SortOrder getDefaultSortOrder() {
        return SortOrder.fromKey(defaultSortOrder);
    }

    @Override
    public DetachedMediaChannelImpl setDefaultSortOrder(int defaultSortOrder) {
        this.defaultSortOrder = defaultSortOrder;
        return this;
    }

    @Override
    public int getRawSortOrder() {
        return defaultSortOrder;
    }

    @Override
    public DetachedMediaChannelImpl setParentCategory(long parentCategoryId) {
        this.parentCategoryId = parentCategoryId;
        return this;
    }

    @Override
    public DetachedMediaChannelImpl setPosition(int position) {
        this.position = position;
        return this;
    }
}
