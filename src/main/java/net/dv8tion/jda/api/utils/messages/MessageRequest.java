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

package net.dv8tion.jda.api.utils.messages;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.AttachedFile;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.internal.utils.Checks;

import java.io.File;
import java.util.*;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstraction of the common setters used for messages in the API.
 * <br>These setters can both be applied to {@link MessageEditRequest edit requests} and {@link MessageCreateRequest create requests} for messages in various parts of the API.
 *
 * @param <R>
 *        Return type used for chaining method calls
 *
 * @see   MessageCreateRequest
 * @see   MessageEditRequest
 * @see   AbstractMessageBuilder
 * @see   MessageCreateBuilder
 * @see   MessageEditBuilder
 */
public interface MessageRequest<R extends MessageRequest<R>> extends MessageData {
    /**
     * Returns the default {@link Message.MentionType MentionTypes} previously set by
     * {@link #setDefaultMentions(Collection) AllowedMentions.setDefaultMentions(Collection)}.
     *
     * @return Default mentions set by AllowedMentions.setDefaultMentions(Collection)
     */
    @Nonnull
    static EnumSet<Message.MentionType> getDefaultMentions() {
        return AllowedMentionsData.getDefaultMentions();
    }

    /**
     * Sets the {@link Message.MentionType MentionTypes} that should be parsed by default.
     * This just sets the default for all RestActions and can be overridden on a per-action basis using {@link #setAllowedMentions(Collection)}.
     * <br>If a message is sent with an empty Set of MentionTypes, then it will not ping any User, Role or {@code @everyone}/{@code @here},
     * while still showing up as mention tag.
     *
     * <p>If {@code null} is provided to this method, then all Types will be pingable
     * (unless whitelisting via one of the {@code mention*} methods is used).
     *
     * <p><b>Example</b><br>
     * {@snippet lang = "java":
     * // Disable EVERYONE and HERE mentions by default (to avoid mass ping)
     * EnumSet<Message.MentionType> deny = EnumSet.of(Message.MentionType.EVERYONE, Message.MentionType.HERE);
     * MessageRequest.setDefaultMentions(EnumSet.complementOf(deny));
     *}
     *
     * @param  allowedMentions
     *         MentionTypes that are allowed to being parsed and pinged. {@code null} to disable and allow all mentions.
     */
    static void setDefaultMentions(@Nullable Collection<Message.MentionType> allowedMentions) {
        AllowedMentionsData.setDefaultMentions(allowedMentions);
    }

    /**
     * Whether V2 components are used by default, this is {@code false} by default.
     * <br>When enabled, {@link #useComponentsV2()} gets called for every message builder.
     *
     * <p>This can be overwritten with {@link #useComponentsV2(boolean)} on each builder instance.
     *
     * @return {@code true} if every message will use Components V2 by default, {@code false} if not
     */
    static boolean isDefaultUseComponentsV2() {
        return AbstractMessageBuilder.isDefaultUseComponentsV2;
    }

    /**
     * Sets whether V2 components will be used by default, this is {@code false} by default.
     * <br>When enabled, {@link #useComponentsV2()} gets called for every message builder.
     *
     * <p>This can be overwritten with {@link #useComponentsV2(boolean)} on each builder instance.
     *
     * @param  use
     *         {@code true} to enable V2 components by default, {@code false} to disabled them by default.
     */
    static void setDefaultUseComponentsV2(boolean use) {
        AbstractMessageBuilder.isDefaultUseComponentsV2 = use;
    }

    /**
     * Returns the default mention behavior for replies.
     * <br>If this is {@code true} then all replies will mention the author of the target message by default.
     * You can specify this individually with {@link #mentionRepliedUser(boolean)} for each message.
     *
     * <p>Default: <b>true</b>
     *
     * @return True, if replies mention by default
     */
    static boolean isDefaultMentionRepliedUser() {
        return AllowedMentionsData.isDefaultMentionRepliedUser();
    }

    /**
     * Sets the default value for {@link #mentionRepliedUser(boolean)}
     *
     * <p>Default: <b>true</b>
     *
     * @param mention True, if replies should mention by default
     */
    static void setDefaultMentionRepliedUser(boolean mention) {
        AllowedMentionsData.setDefaultMentionRepliedUser(mention);
    }

    /**
     * The message content, which shows above embeds and attachments.
     *
     * @param  content
     *         The content (up to {@value Message#MAX_CONTENT_LENGTH} characters)
     *
     * @throws IllegalArgumentException
     *         If the content is longer than {@value Message#MAX_CONTENT_LENGTH} characters
     *
     * @return The same instance for chaining
     */
    @Nonnull
    R setContent(@Nullable String content);

    /**
     * The {@link MessageEmbed MessageEmbeds} that should be attached to the message.
     * <br>You can use {@link Collections#emptyList()} to remove all embeds from the message.
     *
     * <p>This requires {@link Permission#MESSAGE_EMBED_LINKS Permission.MESSAGE_EMBED_LINKS} in the channel.
     *
     * @param  embeds
     *         The embeds to attach to the message (up to {@value Message#MAX_EMBED_COUNT})
     *
     * @throws IllegalArgumentException
     *         If null or more than {@value Message#MAX_EMBED_COUNT} embeds are provided
     *
     * @return The same instance for chaining
     *
     * @see    Collections#emptyList()
     */
    @Nonnull
    R setEmbeds(@Nonnull Collection<? extends MessageEmbed> embeds);

    /**
     * The {@link MessageEmbed MessageEmbeds} that should be attached to the message.
     * <br>You can use {@code new MessageEmbed[0]} to remove all embeds from the message.
     *
     * <p>This requires {@link Permission#MESSAGE_EMBED_LINKS Permission.MESSAGE_EMBED_LINKS} in the channel.
     *
     * @param  embeds
     *         The embeds to attach to the message (up to {@value Message#MAX_EMBED_COUNT})
     *
     * @throws IllegalArgumentException
     *         If null or more than {@value Message#MAX_EMBED_COUNT} embeds are provided
     *
     * @return The same instance for chaining
     */
    @Nonnull
    default R setEmbeds(@Nonnull MessageEmbed... embeds) {
        return setEmbeds(Arrays.asList(embeds));
    }

    /**
     * The {@link MessageTopLevelComponent MessageTopLevelComponents} that should be attached to the message.
     * <br>You can use {@link Collections#emptyList()} to remove all components from the message.
     *
     * <p><b>Example: Set action rows</b><br>
     * {@snippet lang = "java":
     * final List<MessageTopLevelComponent> list = new ArrayList<>();
     * list.add(ActionRow.of(selectMenu)); // first row
     * list.add(ActionRow.of(button1, button2)); // second row (shows below the first)
     *
     * channel.sendMessage("Content here")
     *   .setComponents(list)
     *   .queue();
     *}
     *
     * <p><b>Example: Remove action rows</b><br>
     * {@snippet lang = "java":
     * channel.sendMessage("Content here")
     *    .setComponents(List.of())
     *    .queue();
     *}
     *
     * @param  components
     *         The {@link MessageTopLevelComponent MessageTopLevelComponents} to set, can be empty to remove components,
     *         can contain up to {@value Message#MAX_COMPONENT_COUNT} V1 components.
     *         There are no limits for {@linkplain MessageRequest#isUsingComponentsV2() V2 components}
     *         outside the {@linkplain Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE total tree size} ({@value Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE}).
     *
     * @throws IllegalArgumentException
     *         <ul>
     *             <li>If {@code null} is provided</li>
     *             <li>If any of the provided components are not {@linkplain Component.Type#isMessageCompatible() compatible with messages}</li>
     *         </ul>
     *
     * @return The same instance for chaining
     */
    @Nonnull
    R setComponents(@Nonnull Collection<? extends MessageTopLevelComponent> components);

    /**
     * The {@link MessageTopLevelComponent MessageTopLevelComponents} that should be attached to the message.
     * <br>You can call this method without anything to remove all components from the message.
     *
     * <p><b>Example: Set action rows</b><br>
     * {@snippet lang = "java":
     * channel.sendMessage("Content here")
     *   .setComponents(
     *     ActionRow.of(selectMenu), // first row
     *     ActionRow.of(button1, button2)) // second row (shows below the first)
     *   .queue();
     *}
     *
     * <p><b>Example: Remove action rows</b><br>
     * {@snippet lang = "java":
     * channel.sendMessage("Content here")
     *   .setComponents()
     *   .queue();
     *}
     *
     * @param  components
     *         The {@link MessageTopLevelComponent MessageTopLevelComponents} to set, can be empty to remove components,
     *         can contain up to {@value Message#MAX_COMPONENT_COUNT} V1 components.
     *         There are no limits for {@linkplain MessageRequest#isUsingComponentsV2() V2 components}
     *         outside the {@linkplain Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE total tree size} ({@value Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE}).
     *
     * @throws IllegalArgumentException
     *         <ul>
     *             <li>If {@code null} is provided</li>
     *             <li>If any of the provided components are not {@linkplain Component.Type#isMessageCompatible() compatible with messages}</li>
     *         </ul>
     *
     * @return The same instance for chaining
     */
    @Nonnull
    default R setComponents(@Nonnull MessageTopLevelComponent... components) {
        return setComponents(Arrays.asList(components));
    }

    /**
     * The {@link ComponentTree} of {@link MessageTopLevelComponent MessageTopLevelComponents} that should be attached to the message.
     * <br>You can call this method without anything to remove all components from the message.
     *
     * @param  tree
     *         The new {@link ComponentTree} to set, can be empty to remove components,
     *         can contain up to {@value Message#MAX_COMPONENT_COUNT} V1 components.
     *         There are no limits for {@linkplain MessageRequest#isUsingComponentsV2() V2 components}
     *         outside the {@linkplain Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE total tree size} ({@value Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE}).
     *
     * @throws IllegalArgumentException
     *         <ul>
     *             <li>If {@code null} is provided</li>
     *             <li>If any of the provided components are not {@linkplain Component.Type#isMessageCompatible() compatible with messages}</li>
     *         </ul>
     *
     * @return The same instance for chaining
     *
     * @see    MessageComponentTree MessageComponentTree
     */
    @Nonnull
    default R setComponents(@Nonnull ComponentTree<? extends MessageTopLevelComponent> tree) {
        Checks.notNull(tree, "ComponentTree");
        return setComponents(tree.getComponents());
    }

    /**
     * Sets whether this message is allowed to use V2 components, this is disabled by default.
     *
     * <p>Using V2 components removes the top-level component limit,
     * and allows more components in total ({@value Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE}).
     * <br>They also allow you to use a larger choice of components,
     * such as any component extending {@link MessageTopLevelComponent},
     * as long as they are {@linkplain Component.Type#isMessageCompatible() compatible}.
     * <br>The character limit for the messages also gets changed to {@value Message#MAX_CONTENT_LENGTH_COMPONENT_V2}.
     *
     * <p>This, however, comes with a few drawbacks:
     * <ul>
     *     <li>You cannot send content, embeds, polls or stickers</li>
     *     <li>It does not support voice messages</li>
     *     <li>It does not support previewing files</li>
     *     <li>URLs don't create embeds</li>
     *     <li>You cannot switch this message back to not using Components V2 (you can however upgrade a message to V2)</li>
     * </ul>
     *
     * <p>A default value can be set in {@link #setDefaultUseComponentsV2(boolean)}.
     *
     * @param  use
     *         {@code true} to enable V2 components, {@code false} to disabled them.
     *
     * @return The same instance for chaining
     *
     * @see    MessageTopLevelComponent
     * @see    #setDefaultUseComponentsV2(boolean)
     */
    @Nonnull
    R useComponentsV2(boolean use);

    /**
     * Enables using V2 components, this is disabled by default.
     * <br>This is a shortcut for {@code useComponentV2(true)}
     *
     * <p>Using V2 components removes the top-level component limit,
     * and allows more components in total ({@value Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE}).
     * <br>They also allow you to use a larger choice of components,
     * such as any component extending {@link MessageTopLevelComponent},
     * as long as they are {@linkplain Component.Type#isMessageCompatible() compatible}.
     * <br>The character limit for the messages also gets changed to {@value Message#MAX_CONTENT_LENGTH_COMPONENT_V2}.
     *
     * <p>This, however, comes with a few drawbacks:
     * <ul>
     *     <li>You cannot send content, embeds, polls or stickers</li>
     *     <li>It does not support voice messages</li>
     *     <li>It does not support previewing files</li>
     *     <li>URLs don't create embeds</li>
     *     <li>You cannot switch this message back to not using Components V2 (you can however upgrade a message to V2)</li>
     * </ul>
     *
     * @return The same instance for chaining
     *
     * @see    MessageTopLevelComponent
     * @see    #setDefaultUseComponentsV2(boolean)
     */
    @Nonnull
    default R useComponentsV2() {
        return useComponentsV2(true);
    }

    /**
     * Set whether embeds should be suppressed on this message.
     * <br>This also includes rich embeds added via {@link #setEmbeds(MessageEmbed...)}.
     *
     * <p>Default: false
     *
     * @param  suppress
     *         True, if all embeds should be suppressed
     *
     * @return The same instance for chaining
     */
    @Nonnull
    R setSuppressEmbeds(boolean suppress);

    /**
     * The {@link FileUpload FileUploads} that should be attached to the message.
     * <br>This will replace all the existing attachments on the message, if this is an edit request.
     * You can use {@link MessageEditRequest#setAttachments(Collection)} to keep existing attachments, instead of this method.
     *
     * <p><b>Resource Handling Note:</b> Once the request is handed off to the requester, for example when you call {@link RestAction#queue()},
     * the requester will automatically clean up all opened files by itself. You are only responsible to close them yourself if it is never handed off properly.
     * For instance, if an exception occurs after using {@link FileUpload#fromData(File)}, before calling {@link RestAction#queue()}.
     * You can safely use a try-with-resources to handle this, since {@link FileUpload#close()} becomes ineffective once the request is handed off.
     *
     * <p><b>Example</b><br>
     * Create an embed with a custom image, uploaded alongside the message:
     * {@snippet lang = "java":
     * MessageEmbed embed = new EmbedBuilder()
     *         .setDescription("Image of a cute cat")
     *         .setImage("attachment://cat.png") // here "cat.png" is the name used in the FileUpload.fromData factory method
     *         .build();
     *
     * // The name here will be "cat.png" to discord, what the file is called on your computer is irrelevant and only used to read the data of the image.
     * FileUpload file = FileUpload.fromData(new File("mycat-final-copy.png"), "cat.png"); // Opens the file called "cat.png" and provides the data used for sending
     *
     * channel.sendMessageEmbeds(embed)
     *        .setFiles(file)
     *        .queue();
     *}
     *
     * @param  files
     *         The {@link FileUpload FileUploads} to attach to the message,
     *         null or an empty list will set the attachments to an empty list and remove them from the message
     *
     * @throws IllegalArgumentException
     *         If null is provided inside the collection
     *
     * @return The same instance for chaining
     */
    @Nonnull
    R setFiles(@Nullable Collection<? extends FileUpload> files);

    /**
     * The {@link FileUpload FileUploads} that should be attached to the message.
     * <br>This will replace all the existing attachments on the message, if this is an edit request.
     * You can use {@link MessageEditRequest#setAttachments(AttachedFile...)} to keep existing attachments, instead of this method.
     *
     * <p><b>Resource Handling Note:</b> Once the request is handed off to the requester, for example when you call {@link RestAction#queue()},
     * the requester will automatically clean up all opened files by itself. You are only responsible to close them yourself if it is never handed off properly.
     * For instance, if an exception occurs after using {@link FileUpload#fromData(File)}, before calling {@link RestAction#queue()}.
     * You can safely use a try-with-resources to handle this, since {@link FileUpload#close()} becomes ineffective once the request is handed off.
     *
     * <p><b>Example</b><br>
     * Create an embed with a custom image, uploaded alongside the message:
     * {@snippet lang = "java":
     * MessageEmbed embed = new EmbedBuilder()
     *         .setDescription("Image of a cute cat")
     *         .setImage("attachment://cat.png") // here "cat.png" is the name used in the FileUpload.fromData factory method
     *         .build();
     *
     * // The name here will be "cat.png" to discord, what the file is called on your computer is irrelevant and only used to read the data of the image.
     * FileUpload file = FileUpload.fromData(new File("mycat-final-copy.png"), "cat.png"); // Opens the file called "cat.png" and provides the data used for sending
     *
     * channel.sendMessageEmbeds(embed)
     *        .setFiles(file)
     *        .queue();
     *}
     *
     * @param  files
     *         The {@link FileUpload FileUploads} to attach to the message,
     *         null or an empty list will set the attachments to an empty list and remove them from the message
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     */
    @Nonnull
    default R setFiles(@Nonnull FileUpload... files) {
        Checks.noneNull(files, "Files");
        return setFiles(Arrays.asList(files));
    }

    // Allowed Mentions Methods

    /**
     * Whether to mention the user, when replying to a message.
     * <br>This only matters in combination with {@link MessageCreateAction#setMessageReference(Message) MessageCreateAction.setMessageReference(...)}!
     *
     * <p>This is true by default but can be configured using {@link #setDefaultMentionRepliedUser(boolean)}!
     *
     * @param  mention
     *         True, to mention the author in the referenced message
     *
     * @return The same instance for chaining
     */
    @Nonnull
    @CheckReturnValue
    R mentionRepliedUser(boolean mention);

    /**
     * Sets the {@link Message.MentionType MentionTypes} that should be parsed.
     * <br>If a message is sent with an empty Set of MentionTypes, then it will not ping any User, Role or {@code @everyone}/{@code @here},
     * while still showing up as mention tag.
     *
     * <p>If {@code null} is provided to this method, then all Types will be mentionable
     * (unless whitelisting via one of the {@code mention*} methods is used).
     *
     * <p>Note: A default for this can be set using {@link #setDefaultMentions(Collection) AllowedMentions.setDefaultMentions(Collection)}.
     *
     * @param  allowedMentions
     *         MentionTypes that are allowed to being parsed and mentioned.
     *         All other mention types will not be mentioned by this message.
     *         You can pass {@code null} or {@code EnumSet.allOf(MentionType.class)} to allow all mentions.
     *
     * @return The same instance for chaining
     */
    @Nonnull
    @CheckReturnValue
    R setAllowedMentions(@Nullable Collection<Message.MentionType> allowedMentions);

    /**
     * Used to provide a whitelist for {@link User Users}, {@link Member Members}
     * and {@link Role Roles} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     * <br>On other types of {@link IMentionable}, this does nothing.
     *
     * <p><b>Note:</b> When a User/Member is whitelisted this way, then parsing of User mentions is automatically disabled (same applies to Roles).
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  mentions
     *         Users, Members and Roles that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    R mention(@Nonnull Collection<? extends IMentionable> mentions);

    /**
     * Used to provide a whitelist for {@link User Users}, {@link Member Members}
     * and {@link Role Roles} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     * <br>On other types of {@link IMentionable}, this does nothing.
     *
     * <p><b>Note:</b> When a User/Member is whitelisted this way, then parsing of User mentions is automatically disabled (same applies to Roles).
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  mentions
     *         Users, Members and Roles that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R mention(@Nonnull IMentionable... mentions) {
        Checks.notNull(mentions, "Mentions");
        return mention(Arrays.asList(mentions));
    }

    /**
     * Sets the {@link User Users} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     * <br>This will replace any previously configured user mentions.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable, or {@code null} to clear
     *
     * @throws IllegalArgumentException
     *         If the collection contains null elements
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionUsers(Collection)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    R setMentionUsers(@Nullable Collection<String> userIds);

    /**
     * Sets the {@link User Users} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     * <br>This will replace any previously configured user mentions.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable. Providing no arguments will clear the whitelisted users.
     *
     * @throws IllegalArgumentException
     *         If null is provided or any element is null
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionUsers(String...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R setMentionUsers(@Nonnull String... userIds) {
        if (userIds == null) {
            return setMentionUsers((Collection<String>) null);
        }
        if (userIds.length == 0) {
            return setMentionUsers(Collections.emptyList());
        }
        if (userIds.length == 1) {
            Checks.notNull(userIds[0], "User ID");
            return setMentionUsers(Collections.singletonList(userIds[0]));
        }
        Checks.noneNull(userIds, "User IDs");
        return setMentionUsers(Arrays.asList(userIds));
    }

    /**
     * Sets the {@link User Users} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     * <br>This will replace any previously configured user mentions.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable. Providing no arguments will clear the whitelisted users.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionUsers(long...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R setMentionUsers(@Nonnull long... userIds) {
        Checks.notNull(userIds, "UserId array");
        if (userIds.length == 0) {
            return setMentionUsers(Collections.emptyList());
        }
        if (userIds.length == 1) {
            return setMentionUsers(Collections.singletonList(Long.toUnsignedString(userIds[0])));
        }
        List<String> stringIds = new ArrayList<>(userIds.length);
        for (long userId : userIds) {
            stringIds.add(Long.toUnsignedString(userId));
        }
        return setMentionUsers(stringIds);
    }

    /**
     * Sets the {@link Role Roles} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     * <br>This will replace any previously configured role mentions.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable, or {@code null} to clear
     *
     * @throws IllegalArgumentException
     *         If the collection contains null elements
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionRoles(Collection)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    R setMentionRoles(@Nullable Collection<String> roleIds);

    /**
     * Sets the {@link Role Roles} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     * <br>This will replace any previously configured role mentions.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable. Providing no arguments will clear the whitelisted roles.
     *
     * @throws IllegalArgumentException
     *         If null is provided or any element is null
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionRoles(String...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R setMentionRoles(@Nonnull String... roleIds) {
        if (roleIds == null) {
            return setMentionRoles((Collection<String>) null);
        }
        if (roleIds.length == 0) {
            return setMentionRoles(Collections.emptyList());
        }
        if (roleIds.length == 1) {
            Checks.notNull(roleIds[0], "Role ID");
            return setMentionRoles(Collections.singletonList(roleIds[0]));
        }
        Checks.noneNull(roleIds, "Role IDs");
        return setMentionRoles(Arrays.asList(roleIds));
    }

    /**
     * Sets the {@link Role Roles} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     * <br>This will replace any previously configured role mentions.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable. Providing no arguments will clear the whitelisted roles.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionRoles(long...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R setMentionRoles(@Nonnull long... roleIds) {
        Checks.notNull(roleIds, "RoleId array");
        if (roleIds.length == 0) {
            return setMentionRoles(Collections.emptyList());
        }
        if (roleIds.length == 1) {
            return setMentionRoles(Collections.singletonList(Long.toUnsignedString(roleIds[0])));
        }
        List<String> stringIds = new ArrayList<>(roleIds.length);
        for (long roleId : roleIds) {
            stringIds.add(Long.toUnsignedString(roleId));
        }
        return setMentionRoles(stringIds);
    }

    /**
     * Adds the provided {@link User Users} to the whitelist of users that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided or any element is null
     *
     * @return The same instance for chaining
     *
     * @see    #setMentionUsers(Collection)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    R addMentionUsers(@Nonnull Collection<String> userIds);

    /**
     * Adds the provided {@link User Users} to the whitelist of users that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided or any element is null
     *
     * @return The same instance for chaining
     *
     * @see    #setMentionUsers(String...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R addMentionUsers(@Nonnull String... userIds) {
        Checks.notNull(userIds, "User IDs");
        if (userIds.length == 0) {
            return addMentionUsers(Collections.emptyList());
        }
        if (userIds.length == 1) {
            Checks.notNull(userIds[0], "User ID");
            return addMentionUsers(Collections.singletonList(userIds[0]));
        }
        Checks.noneNull(userIds, "User IDs");
        return addMentionUsers(Arrays.asList(userIds));
    }

    /**
     * Adds the provided {@link User Users} to the whitelist of users that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #setMentionUsers(long...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R addMentionUsers(@Nonnull long... userIds) {
        Checks.notNull(userIds, "UserId array");
        if (userIds.length == 0) {
            return addMentionUsers(Collections.emptyList());
        }
        if (userIds.length == 1) {
            return addMentionUsers(Collections.singletonList(Long.toUnsignedString(userIds[0])));
        }
        List<String> stringIds = new ArrayList<>(userIds.length);
        for (long userId : userIds) {
            stringIds.add(Long.toUnsignedString(userId));
        }
        return addMentionUsers(stringIds);
    }

    /**
     * Adds the provided {@link Role Roles} to the whitelist of roles that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided or any element is null
     *
     * @return The same instance for chaining
     *
     * @see    #setMentionRoles(Collection)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    R addMentionRoles(@Nonnull Collection<String> roleIds);

    /**
     * Adds the provided {@link Role Roles} to the whitelist of roles that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided or any element is null
     *
     * @return The same instance for chaining
     *
     * @see    #setMentionRoles(String...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R addMentionRoles(@Nonnull String... roleIds) {
        Checks.notNull(roleIds, "Role IDs");
        if (roleIds.length == 0) {
            return addMentionRoles(Collections.emptyList());
        }
        if (roleIds.length == 1) {
            Checks.notNull(roleIds[0], "Role ID");
            return addMentionRoles(Collections.singletonList(roleIds[0]));
        }
        Checks.noneNull(roleIds, "Role IDs");
        return addMentionRoles(Arrays.asList(roleIds));
    }

    /**
     * Adds the provided {@link Role Roles} to the whitelist of roles that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #setMentionRoles(long...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     */
    @Nonnull
    @CheckReturnValue
    default R addMentionRoles(@Nonnull long... roleIds) {
        Checks.notNull(roleIds, "RoleId array");
        if (roleIds.length == 0) {
            return addMentionRoles(Collections.emptyList());
        }
        if (roleIds.length == 1) {
            return addMentionRoles(Collections.singletonList(Long.toUnsignedString(roleIds[0])));
        }
        List<String> stringIds = new ArrayList<>(roleIds.length);
        for (long roleId : roleIds) {
            stringIds.add(Long.toUnsignedString(roleId));
        }
        return addMentionRoles(stringIds);
    }

    /**
     * Used to provide a whitelist of {@link User Users} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionUsers(Collection)
     * @see    #setMentionUsers(Collection)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     *
     * @deprecated This method is confusing because it only adds users rather than setting them.
     *             Use {@link #addMentionUsers(Collection)} to add users, or {@link #setMentionUsers(Collection)} to set/replace them.
     */
    @Deprecated(since = "6.7.0-enhanced")
    @Nonnull
    @CheckReturnValue
    default R mentionUsers(@Nonnull Collection<String> userIds) {
        return addMentionUsers(userIds);
    }

    /**
     * Used to provide a whitelist of {@link User Users} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionUsers(String...)
     * @see    #setMentionUsers(String...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     *
     * @deprecated This method is confusing because it only adds users rather than setting them.
     *             Use {@link #addMentionUsers(String...)} to add users, or {@link #setMentionUsers(String...)} to set/replace them.
     */
    @Deprecated(since = "6.7.0-enhanced")
    @Nonnull
    @CheckReturnValue
    default R mentionUsers(@Nonnull String... userIds) {
        return addMentionUsers(userIds);
    }

    /**
     * Used to provide a whitelist of {@link User Users} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a User is whitelisted this way, then parsing of User mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  userIds
     *         Ids of Users that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionUsers(long...)
     * @see    #setMentionUsers(long...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     *
     * @deprecated This method is confusing because it only adds users rather than setting them.
     *             Use {@link #addMentionUsers(long...)} to add users, or {@link #setMentionUsers(long...)} to set/replace them.
     */
    @Deprecated(since = "6.7.0-enhanced")
    @Nonnull
    @CheckReturnValue
    default R mentionUsers(@Nonnull long... userIds) {
        return addMentionUsers(userIds);
    }

    /**
     * Used to provide a whitelist of {@link Role Roles} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionRoles(Collection)
     * @see    #setMentionRoles(Collection)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     *
     * @deprecated This method is confusing because it only adds roles rather than setting them.
     *             Use {@link #addMentionRoles(Collection)} to add roles, or {@link #setMentionRoles(Collection)} to set/replace them.
     */
    @Deprecated(since = "6.7.0-enhanced")
    @Nonnull
    @CheckReturnValue
    default R mentionRoles(@Nonnull Collection<String> roleIds) {
        return addMentionRoles(roleIds);
    }

    /**
     * Used to provide a whitelist of {@link Role Roles} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionRoles(String...)
     * @see    #setMentionRoles(String...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     *
     * @deprecated This method is confusing because it only adds roles rather than setting them.
     *             Use {@link #addMentionRoles(String...)} to add roles, or {@link #setMentionRoles(String...)} to set/replace them.
     */
    @Deprecated(since = "6.7.0-enhanced")
    @Nonnull
    @CheckReturnValue
    default R mentionRoles(@Nonnull String... roleIds) {
        return addMentionRoles(roleIds);
    }

    /**
     * Used to provide a whitelist of {@link Role Roles} that should be pinged,
     * even when they would not be pinged otherwise according to the Set of allowed mention types.
     *
     * <p><b>Note:</b> When a Role is whitelisted this way, then parsing of Role mentions is automatically disabled.
     * <br>Also note that whitelisting users or roles implicitly disables parsing of other mentions, if not otherwise set via
     * {@link #setDefaultMentions(Collection)} or {@link #setAllowedMentions(Collection)}.
     *
     * @param  roleIds
     *         Ids of Roles that should be explicitly whitelisted to be pingable.
     *
     * @throws IllegalArgumentException
     *         If null is provided
     *
     * @return The same instance for chaining
     *
     * @see    #addMentionRoles(long...)
     * @see    #setMentionRoles(long...)
     * @see    #setAllowedMentions(Collection)
     * @see    #setDefaultMentions(Collection)
     *
     * @deprecated This method is confusing because it only adds roles rather than setting them.
     *             Use {@link #addMentionRoles(long...)} to add roles, or {@link #setMentionRoles(long...)} to set/replace them.
     */
    @Deprecated(since = "6.7.0-enhanced")
    @Nonnull
    @CheckReturnValue
    default R mentionRoles(@Nonnull long... roleIds) {
        return addMentionRoles(roleIds);
    }

    /**
     * Applies all the data of the provided {@link Message} and attempts to copy it.
     * <br>This cannot copy the file attachments of the message, they must be manually downloaded and provided to {@link #setFiles(FileUpload...)}.
     * <br>The {@link #setAllowedMentions(Collection) allowed mentions} are not updated to reflect the provided message, and might mention users that the message did not.
     *
     * <p>For edit requests, this will set {@link MessageEditRequest#setReplace(boolean)} to {@code true}, and replace the existing message completely.
     *
     * @param  message
     *         The message to copy the data from
     *
     * @throws IllegalArgumentException
     *         If null is provided or the message is a system message
     *
     * @return The same instance for chaining
     */
    @Nonnull
    default R applyMessage(@Nonnull Message message) {
        Checks.notNull(message, "Message");
        Checks.check(!message.getType().isSystem(), "Cannot copy a system message");
        List<MessageEmbed> embeds = message.getEmbeds().stream()
                .filter(e -> e.getType() == EmbedType.RICH)
                .toList();
        return setContent(message.getContentRaw())
                .setEmbeds(embeds)
                .setComponents(message.getComponents())
                .useComponentsV2(message.isUsingComponentsV2())
                .setSuppressEmbeds(message.isSuppressedEmbeds());
    }
}
