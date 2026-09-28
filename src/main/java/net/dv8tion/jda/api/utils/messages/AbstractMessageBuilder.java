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

import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.MessageTopLevelComponentUnion;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.AttachedFile;
import net.dv8tion.jda.internal.components.utils.ComponentsUtil;
import net.dv8tion.jda.internal.utils.Checks;

import java.util.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstract builder implementation of {@link MessageRequest}.
 *
 * <p>This builder cannot be instantiated directly. You should use {@link MessageCreateBuilder} or {@link MessageEditBuilder} instead.
 *
 * @param <T>
 *        The result type used for {@link #build()}
 * @param <R>
 *        The return type used for method chaining
 *
 * @see   MessageCreateBuilder
 * @see   MessageEditBuilder
 */
@SuppressWarnings({"unchecked", "InlineMeSuggester"})
public abstract class AbstractMessageBuilder<T, R extends AbstractMessageBuilder<T, R>> implements MessageRequest<R> {
    protected static boolean isDefaultUseComponentsV2 = false;

    protected final List<MessageEmbed> embeds = new ArrayList<>(Message.MAX_EMBED_COUNT);
    protected final List<MessageTopLevelComponentUnion> components = new ArrayList<>(Message.MAX_COMPONENT_COUNT);
    protected final StringBuilder content = new StringBuilder(Message.MAX_CONTENT_LENGTH);
    protected AllowedMentionsData mentions = new AllowedMentionsData();
    protected int messageFlags;

    protected AbstractMessageBuilder() {
        useComponentsV2(isDefaultUseComponentsV2);
    }

    @Nonnull
    @Override
    public String getContent() {
        return content.toString();
    }

    @Nonnull
    @Override
    public R setContent(@Nullable String content) {
        if (content != null) {
            content = content.trim();
            Checks.notLonger(content, Message.MAX_CONTENT_LENGTH, "Content");
            this.content.setLength(0);
            this.content.append(content);
        } else {
            this.content.setLength(0);
        }
        return (R) this;
    }

    @Nonnull
    @Override
    public R mentionRepliedUser(boolean mention) {
        mentions.mentionRepliedUser(mention);
        return (R) this;
    }

    @Nonnull
    @Override
    public R mention(@Nonnull Collection<? extends IMentionable> mentions) {
        this.mentions.mention(mentions);
        return (R) this;
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
     * @return The same builder instance for chaining
     *
     * @see    #addMentionUsers(Collection)
     * @see    #setAllowedMentions(Collection)
     */
    @Nonnull
    @Override
    public R setMentionUsers(@Nullable Collection<String> userIds) {
        this.mentions.setMentionUsers(userIds);
        return (R) this;
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
     * @return The same builder instance for chaining
     *
     * @see    #addMentionRoles(Collection)
     * @see    #setAllowedMentions(Collection)
     */
    @Nonnull
    @Override
    public R setMentionRoles(@Nullable Collection<String> roleIds) {
        this.mentions.setMentionRoles(roleIds);
        return (R) this;
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
     * @return The same builder instance for chaining
     *
     * @see    #setMentionUsers(Collection)
     * @see    #setAllowedMentions(Collection)
     */
    @Nonnull
    @Override
    public R addMentionUsers(@Nonnull Collection<String> userIds) {
        this.mentions.addMentionUsers(userIds);
        return (R) this;
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
     * @return The same builder instance for chaining
     *
     * @see    #setMentionRoles(Collection)
     * @see    #setAllowedMentions(Collection)
     */
    @Nonnull
    @Override
    public R addMentionRoles(@Nonnull Collection<String> roleIds) {
        this.mentions.addMentionRoles(roleIds);
        return (R) this;
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
     * @return The same builder instance for chaining
     *
     * @see    #addMentionUsers(Collection)
     * @see    #setMentionUsers(Collection)
     * @see    #setAllowedMentions(Collection)
     *
     * @deprecated This method is confusing because it only adds users rather than setting them.
     *             Use {@link #addMentionUsers(Collection)} to add users, or {@link #setMentionUsers(Collection)} to set/replace them.
     */
    @Nonnull
    @Override
    @Deprecated(since = "6.7.0-enhanced")
    public R mentionUsers(@Nonnull Collection<String> userIds) {
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
     * @return The same builder instance for chaining
     *
     * @see    #addMentionRoles(Collection)
     * @see    #setMentionRoles(Collection)
     * @see    #setAllowedMentions(Collection)
     *
     * @deprecated This method is confusing because it only adds roles rather than setting them.
     *             Use {@link #addMentionRoles(Collection)} to add roles, or {@link #setMentionRoles(Collection)} to set/replace them.
     */
    @Nonnull
    @Override
    @Deprecated(since = "6.7.0-enhanced")
    public R mentionRoles(@Nonnull Collection<String> roleIds) {
        return addMentionRoles(roleIds);
    }

    @Nonnull
    @Override
    public Set<String> getMentionedUsers() {
        return mentions.getMentionedUsers();
    }

    @Nonnull
    @Override
    public Set<String> getMentionedRoles() {
        return mentions.getMentionedRoles();
    }

    @Nonnull
    @Override
    public EnumSet<Message.MentionType> getAllowedMentions() {
        return mentions.getAllowedMentions();
    }

    @Nonnull
    @Override
    public R setAllowedMentions(@Nullable Collection<Message.MentionType> allowedMentions) {
        mentions.setAllowedMentions(allowedMentions);
        return (R) this;
    }

    @Override
    public boolean isMentionRepliedUser() {
        return mentions.isMentionRepliedUser();
    }

    @Nonnull
    @Override
    public List<MessageEmbed> getEmbeds() {
        return Collections.unmodifiableList(embeds);
    }

    @Nonnull
    @Override
    public R setEmbeds(@Nonnull Collection<? extends MessageEmbed> embeds) {
        Checks.noneNull(embeds, "Embeds");
        Checks.check(
                embeds.size() <= Message.MAX_EMBED_COUNT,
                "Cannot send more than %d embeds in a message!",
                Message.MAX_EMBED_COUNT);
        this.embeds.clear();
        this.embeds.addAll(embeds);
        return (R) this;
    }

    @Nonnull
    @Override
    public R useComponentsV2(boolean use) {
        int flag = Message.MessageFlag.IS_COMPONENTS_V2.getValue();
        if (use) {
            this.messageFlags |= flag;
        } else {
            this.messageFlags &= ~flag;
        }
        return (R) this;
    }

    @Nonnull
    @Override
    public List<MessageTopLevelComponentUnion> getComponents() {
        return Collections.unmodifiableList(components);
    }

    @Nonnull
    @Override
    public R setComponents(@Nonnull Collection<? extends MessageTopLevelComponent> components) {
        Checks.noneNull(components, "MessageTopLevelComponents");
        Checks.checkComponents(
                "Provided component is invalid for messages!", components, Component::isMessageCompatible);

        List<MessageTopLevelComponentUnion> componentsAsUnions =
                ComponentsUtil.membersToUnion(components, MessageTopLevelComponentUnion.class);

        this.components.clear();
        this.components.addAll(componentsAsUnions);
        return (R) this;
    }

    @Override
    public boolean isUsingComponentsV2() {
        return (messageFlags & Message.MessageFlag.IS_COMPONENTS_V2.getValue()) != 0;
    }

    @Override
    public boolean isSuppressEmbeds() {
        return (this.messageFlags & Message.MessageFlag.EMBEDS_SUPPRESSED.getValue()) != 0;
    }

    @Nonnull
    @Override
    public R setSuppressEmbeds(boolean suppress) {
        int flag = Message.MessageFlag.EMBEDS_SUPPRESSED.getValue();
        if (suppress) {
            this.messageFlags |= flag;
        } else {
            this.messageFlags &= ~flag;
        }
        return (R) this;
    }

    /**
     * The flags set on this message.
     *
     * @return The currently set message flags
     */
    public long getMessageFlagsRaw() {
        return messageFlags;
    }

    /**
     * Whether this builder is considered empty, this checks for all <em>required</em> fields of the request type.
     * <br>On a create request, this checks for {@link #setContent(String) content}, {@link #setEmbeds(Collection) embeds}, {@link #setComponents(Collection) components}, and {@link #setFiles(Collection) files}.
     * <br>An edit request is only considered empty if no setters were called. And never empty, if the builder is a {@link MessageEditRequest#setReplace(boolean) replace request}.
     *
     * @return True, if the builder state is empty
     */
    public abstract boolean isEmpty();

    /**
     * Whether this builder has a valid state to build.
     * <br>If this is {@code false}, then {@link #build()} throws an {@link IllegalStateException}.
     * You can check the exception docs on {@link #build()} for specifics.
     *
     * @return True, if the builder is in a valid state
     */
    public abstract boolean isValid();

    /**
     * Builds a validated instance of this builder's state, which can then be used for requests.
     *
     * @throws IllegalStateException
     *         For {@link MessageCreateBuilder}
     *         <ul>
     *             <li>If the builder is {@link #isEmpty() empty}</li>
     *             <li>If the content set is longer than {@value Message#MAX_CONTENT_LENGTH}</li>
     *             <li>If more than {@value Message#MAX_EMBED_COUNT} embeds are set</li>
     *             <li>When using components V1, if more than {@value Message#MAX_COMPONENT_COUNT} top-level components are set</li>
     *             <li>When {@linkplain #isUsingComponentsV2() using components V2}, if more than {@value Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE} total components are set</li>
     *         </ul>
     *         For {@link MessageEditBuilder}
     *         <ul>
     *             <li>If the content set is longer than {@value Message#MAX_CONTENT_LENGTH}</li>
     *             <li>If more than {@value Message#MAX_EMBED_COUNT} embeds are set</li>
     *             <li>When using components V1, if more than {@value Message#MAX_COMPONENT_COUNT} top-level components are set</li>
     *             <li>When {@linkplain #isUsingComponentsV2() using components V2}, if more than {@value Message#MAX_COMPONENT_COUNT_IN_COMPONENT_TREE} total components are set</li>
     *         </ul>
     *
     * @return The validated data instance
     */
    @Nonnull
    public abstract T build();

    /**
     * Clears this builder's state, resetting it to the initial state identical to creating a new instance.
     *
     * <p><b>WARNING:</b> This will remove all the files added to the builder, but will not close them.
     * You can use {@link #closeFiles()} <em>before</em> calling {@code clear()} to close the files explicitly.
     *
     * @return The same builder instance for chaining
     */
    @Nonnull
    public R clear() {
        this.embeds.clear();
        this.components.clear();
        this.content.setLength(0);
        this.mentions.clear();
        this.messageFlags = 0;
        return (R) this;
    }

    /**
     * Closes and removes all {@link net.dv8tion.jda.api.utils.FileUpload FileUploads} added to this builder.
     *
     * <p>This will keep any {@link net.dv8tion.jda.api.utils.AttachmentUpdate AttachmentUpdates} added to this builder, as those do not require closing.
     * You can use {@link MessageEditRequest#setAttachments(AttachedFile...)} to remove them as well.
     *
     * @return The same builder instance for chaining
     */
    @Nonnull
    public abstract R closeFiles();
}
