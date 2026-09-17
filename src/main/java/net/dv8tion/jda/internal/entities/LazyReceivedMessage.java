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
import net.dv8tion.jda.api.components.MessageTopLevelComponentUnion;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.messages.MessagePoll;
import net.dv8tion.jda.api.entities.messages.MessageSnapshot;
import net.dv8tion.jda.api.entities.sticker.StickerItem;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An implementation of {@link Message} that defers deserialization
 * of heavy child entities (embeds, attachments, reactions, stickers, components, poll, activity, snapshots)
 * until their respective getter methods are invoked.
 */
public class LazyReceivedMessage extends ReceivedMessage {
    private final DataObject jsonObject;
    private final EntityBuilder entityBuilder;
    private final MessageReference finalReference;

    private volatile List<Attachment> lazyAttachments;
    private volatile List<MessageEmbed> lazyEmbeds;
    private volatile List<MessageReaction> lazyReactions;
    private volatile List<StickerItem> lazyStickers;
    private volatile List<MessageTopLevelComponentUnion> lazyComponents;
    private volatile List<MessageSnapshot> lazySnapshots;
    private volatile MessagePoll lazyPoll;
    private volatile boolean pollParsed;
    private volatile MessageActivity lazyActivity;
    private volatile boolean activityParsed;

    public LazyReceivedMessage(
            long id,
            long channelId,
            long guildId,
            JDA jda,
            Guild guild,
            MessageChannel channel,
            MessageType type,
            MessageReference messageReference,
            boolean fromWebhook,
            long applicationId,
            boolean tts,
            boolean pinned,
            String content,
            String nonce,
            User author,
            Member member,
            OffsetDateTime editTime,
            Mentions mentions,
            int flags,
            Message.Interaction interaction,
            Message.InteractionMetadata interactionMetadata,
            ThreadChannel startedThread,
            int position,
            DataObject jsonObject,
            EntityBuilder entityBuilder,
            MessageReference finalReference) {
        super(
                id,
                channelId,
                guildId,
                jda,
                guild,
                channel,
                type,
                messageReference,
                fromWebhook,
                applicationId,
                tts,
                pinned,
                content,
                nonce,
                author,
                member,
                null,
                null,
                editTime,
                mentions,
                null,
                null,
                null,
                null,
                null,
                null,
                flags,
                interaction,
                interactionMetadata,
                startedThread,
                position);
        this.jsonObject = jsonObject;
        this.entityBuilder = entityBuilder;
        this.finalReference = finalReference;
    }

    @Nonnull
    @Override
    public List<Attachment> getAttachments() {
        if (this.lazyAttachments == null) {
            synchronized (mutex) {
                if (this.lazyAttachments == null) {
                    this.lazyAttachments = Collections.unmodifiableList(
                            entityBuilder.map(jsonObject, "attachments", entityBuilder::createMessageAttachment));
                }
            }
        }
        return this.lazyAttachments;
    }

    @Nonnull
    @Override
    public List<MessageEmbed> getEmbeds() {
        if (this.lazyEmbeds == null) {
            synchronized (mutex) {
                if (this.lazyEmbeds == null) {
                    this.lazyEmbeds = Collections.unmodifiableList(
                            entityBuilder.map(jsonObject, "embeds", entityBuilder::createMessageEmbed));
                }
            }
        }
        return this.lazyEmbeds;
    }

    @Nonnull
    @Override
    public List<MessageReaction> getReactions() {
        if (this.lazyReactions == null) {
            synchronized (mutex) {
                if (this.lazyReactions == null) {
                    MessageChannel tmpChannel = channel;
                    long tmpChannelId = channelId;
                    long tmpId = id;
                    this.lazyReactions = Collections.unmodifiableList(entityBuilder.map(
                            jsonObject,
                            "reactions",
                            (obj) -> entityBuilder.createMessageReaction(tmpChannel, tmpChannelId, tmpId, obj)));
                }
            }
        }
        return this.lazyReactions;
    }

    @Nonnull
    @Override
    public List<StickerItem> getStickers() {
        if (this.lazyStickers == null) {
            synchronized (mutex) {
                if (this.lazyStickers == null) {
                    this.lazyStickers = Collections.unmodifiableList(
                            entityBuilder.map(jsonObject, "sticker_items", entityBuilder::createStickerItem));
                }
            }
        }
        return this.lazyStickers;
    }

    @Nonnull
    @Override
    public List<MessageTopLevelComponentUnion> getComponents() {
        if (this.lazyComponents == null) {
            synchronized (mutex) {
                if (this.lazyComponents == null) {
                    this.lazyComponents = Collections.unmodifiableList(entityBuilder.map(
                            jsonObject,
                            "components",
                            (obj) -> EntityBuilder.DEFAULT_COMPONENT_DESERIALIZER.deserializeAs(
                                    MessageTopLevelComponentUnion.class, obj)));
                }
            }
        }
        return this.lazyComponents;
    }

    @Nonnull
    @Override
    public List<MessageSnapshot> getMessageSnapshots() {
        if (this.lazySnapshots == null) {
            synchronized (mutex) {
                if (this.lazySnapshots == null) {
                    if (finalReference != null) {
                        this.lazySnapshots = Collections.unmodifiableList(entityBuilder.map(
                                jsonObject,
                                "message_snapshots",
                                (obj) ->
                                        entityBuilder.createMessageSnapshot(finalReference, obj.getObject("message"))));
                    } else {
                        this.lazySnapshots = List.of();
                    }
                }
            }
        }
        return this.lazySnapshots;
    }

    @Nullable
    @Override
    public MessagePoll getPoll() {
        if (!this.pollParsed) {
            synchronized (mutex) {
                if (!this.pollParsed) {
                    this.lazyPoll = jsonObject
                            .optObject("poll")
                            .map(EntityBuilder::createMessagePoll)
                            .orElse(null);
                    this.pollParsed = true;
                }
            }
        }
        return this.lazyPoll;
    }

    @Nullable
    @Override
    public MessageActivity getActivity() {
        if (!this.activityParsed) {
            synchronized (mutex) {
                if (!this.activityParsed) {
                    if (!jsonObject.isNull("activity")) {
                        this.lazyActivity = EntityBuilder.createMessageActivity(jsonObject);
                    }
                    this.activityParsed = true;
                }
            }
        }
        return this.lazyActivity;
    }
}
