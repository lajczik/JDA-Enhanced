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

package net.dv8tion.jda.internal.entities;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.emoji.CustomEmoji;
import net.dv8tion.jda.api.interactions.commands.SlashCommandReference;
import net.dv8tion.jda.api.utils.Bag;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.HashBag;

import java.util.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SelectMenuMentions implements Mentions {
    private final DataObject resolved;
    private final JDAImpl jda;
    private final InteractionEntityBuilder interactionEntityBuilder;
    private final Guild guild;
    private final List<String> values;

    private List<User> cachedUsers;
    private List<Member> cachedMembers;
    private List<Role> cachedRoles;
    private List<GuildChannel> cachedChannels;

    public SelectMenuMentions(
            JDAImpl jda,
            InteractionEntityBuilder interactionEntityBuilder,
            @Nullable Guild guild,
            DataObject resolved,
            DataArray values) {
        this.jda = jda;
        this.interactionEntityBuilder = interactionEntityBuilder;
        this.guild = guild;
        this.resolved = resolved;
        this.values = values.stream(DataArray::getString).toList();
    }

    @Nonnull
    @Override
    public JDA getJDA() {
        return jda;
    }

    @Override
    public boolean mentionsEveryone() {
        return false;
    }

    @Nonnull
    @Override
    public List<User> getUsers() {
        if (cachedUsers != null) {
            return cachedUsers;
        }

        DataObject userMap = resolved.optObject("users").orElseGet(DataObject::empty);
        EntityBuilder builder = jda.getEntityBuilder();

        return cachedUsers = values.stream()
                .map(id -> userMap.optObject(id).orElse(null))
                .filter(Objects::nonNull)
                .<User>map(builder::createUser)
                .toList();
    }

    @Nonnull
    @Override
    public Bag<User> getUsersBag() {
        return new HashBag<>(getUsers());
    }

    @Nonnull
    @Override
    public List<GuildChannel> getChannels() {
        if (guild == null) {
            return List.of();
        }
        if (cachedChannels != null) {
            return cachedChannels;
        }

        DataObject channelMap = resolved.optObject("channels").orElseGet(DataObject::empty);

        return cachedChannels = values.stream()
                .map(id -> channelMap.optObject(id).orElse(null))
                .filter(Objects::nonNull)
                .map(json -> {
                    ChannelType channelType = ChannelType.fromId(json.getInt("type", -1));
                    if (!guild.isDetached()) {
                        return guild.getGuildChannelById(channelType, json.getUnsignedLong("id"));
                    }

                    // Unknown guilds
                    if (channelType.isThread()) {
                        return interactionEntityBuilder.createThreadChannel(guild, json);
                    }
                    // Will return null if the type isn't known
                    return interactionEntityBuilder.createGuildChannel(guild, json);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Nonnull
    @Override
    public Bag<GuildChannel> getChannelsBag() {
        return new HashBag<>(getChannels());
    }

    @Nonnull
    @Override
    public <T extends GuildChannel> List<T> getChannels(@Nonnull Class<T> clazz) {
        return getChannels().stream().filter(clazz::isInstance).map(clazz::cast).toList();
    }

    @Nonnull
    @Override
    public <T extends GuildChannel> Bag<T> getChannelsBag(@Nonnull Class<T> clazz) {
        return new HashBag<>(getChannels(clazz));
    }

    @Nonnull
    @Override
    public List<Role> getRoles() {
        if (guild == null) {
            return List.of();
        }
        if (cachedRoles != null) {
            return cachedRoles;
        }

        DataObject roleMap = resolved.optObject("roles").orElseGet(DataObject::empty);

        return cachedRoles = values.stream()
                .filter(roleMap::hasKey)
                .map(roleMap::getObject)
                .map(json -> {
                    if (!guild.isDetached()) {
                        return guild.getRoleById(json.getUnsignedLong("id"));
                    }
                    return interactionEntityBuilder.createRole(guild, json);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Nonnull
    @Override
    public Bag<Role> getRolesBag() {
        return new HashBag<>(getRoles());
    }

    @Nonnull
    @Override
    public List<CustomEmoji> getCustomEmojis() {
        return List.of();
    }

    @Nonnull
    @Override
    public Bag<CustomEmoji> getCustomEmojisBag() {
        return Bag.emptyBag();
    }

    @Nonnull
    @Override
    public List<SlashCommandReference> getSlashCommands() {
        return List.of();
    }

    @Nonnull
    @Override
    public Bag<SlashCommandReference> getSlashCommandsBag() {
        return Bag.emptyBag();
    }

    @Nonnull
    @Override
    public List<Member> getMembers() {
        if (guild == null) {
            return List.of();
        }
        if (cachedMembers != null) {
            return cachedMembers;
        }

        DataObject memberMap = resolved.optObject("members").orElseGet(DataObject::empty);
        DataObject userMap = resolved.optObject("users").orElseGet(DataObject::empty);

        return cachedMembers = values.stream()
                .map(id -> memberMap.optObject(id).map(m -> m.put("id", id)).orElse(null))
                .filter(Objects::nonNull)
                .map(json -> json.put("user", userMap.getObject(json.getString("id"))))
                .map(json -> interactionEntityBuilder.createMember(guild, json))
                .filter(Objects::nonNull)
                .filter(member -> {
                    if (!member.isDetached()) {
                        jda.getEntityBuilder().updateMemberCache((MemberImpl) member);
                    }
                    return true;
                })
                .toList();
    }

    @Nonnull
    @Override
    public Bag<Member> getMembersBag() {
        return new HashBag<>(getMembers());
    }

    @Nonnull
    @Override
    public List<IMentionable> getMentions(@Nonnull Message.MentionType... types) {
        if (types.length == 0) {
            return getMentions(Message.MentionType.values());
        }
        List<IMentionable> mentions = new ArrayList<>();
        // Convert to set to avoid duplicates
        EnumSet<Message.MentionType> set = EnumSet.of(types[0], types);
        for (Message.MentionType type : set) {
            switch (type) {
                case USER:
                    List<Member> members = getMembers();
                    List<User> users = getUsers();
                    mentions.addAll(members);
                    users.stream()
                            .filter(u -> members.stream().noneMatch(m -> m.getIdLong() == u.getIdLong()))
                            .forEach(mentions::add);
                    break;
                case ROLE:
                    mentions.addAll(getRoles());
                    break;
                case CHANNEL:
                    mentions.addAll(getChannels());
                    break;
                default:
                    break;
            }
        }

        mentions.sort(Comparator.comparingInt(it -> values.indexOf(it.getId())));
        return Collections.unmodifiableList(mentions);
    }

    @Override
    public boolean isMentioned(@Nonnull IMentionable mentionable, @Nonnull Message.MentionType... types) {
        Checks.notNull(types, "Mention Types");
        if (types.length == 0) {
            return isMentioned(mentionable, Message.MentionType.values());
        }

        String id = mentionable.getId();
        for (Message.MentionType type : types) {
            switch (type) {
                case USER:
                    if (mentionable instanceof UserSnowflake) {
                        boolean mentioned = resolved.optObject("users")
                                .map(obj -> obj.hasKey(id))
                                .orElse(false);
                        if (mentioned) {
                            return true;
                        }
                    }
                    break;
                case ROLE:
                    DataObject rolesObj = resolved.optObject("roles").orElse(null);
                    if (rolesObj != null) {
                        if (mentionable instanceof Member member) {
                            for (String roleId : rolesObj.keys()) {
                                if (member.hasRole(roleId)) {
                                    return true;
                                }
                            }
                        } else if (mentionable instanceof User) {
                            Member member = getMembers().stream()
                                    .filter(it -> it.getIdLong() == mentionable.getIdLong())
                                    .findFirst()
                                    .orElse(null);
                            if (member != null) {
                                for (String roleId : rolesObj.keys()) {
                                    if (member.hasRole(roleId)) {
                                        return true;
                                    }
                                }
                            }
                        } else if (mentionable instanceof Role) {
                            if (rolesObj.hasKey(id)) {
                                return true;
                            }
                        }
                    }
                    break;
                case CHANNEL:
                    if (mentionable instanceof GuildChannel && getChannels().contains(mentionable)) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }
}
