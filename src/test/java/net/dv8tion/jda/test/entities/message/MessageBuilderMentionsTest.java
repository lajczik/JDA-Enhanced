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

package net.dv8tion.jda.test.entities.message;

import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

public class MessageBuilderMentionsTest {
    @Test
    void testMentionRolesAddAndSet() {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent("Test");

        // addMentionedRoles adds roles incrementally
        builder.addMentionedRoles("1", "2");
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("1", "2");

        builder.addMentionedRoles("3");
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("1", "2", "3");

        // setMentionedRoles replaces roles
        builder.setMentionedRoles("4", "5");
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("4", "5");

        // setMentionedRoles with long array
        builder.setMentionedRoles(6L, 7L);
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("6", "7");

        // addMentionedRoles with long array
        builder.addMentionedRoles(8L);
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("6", "7", "8");

        // setMentionedRoles(null) clears
        builder.setMentionedRoles((Collection<String>) null);
        assertThat(builder.getMentionedRoles()).isEmpty();

        // setMentionedRoles empty vararg clears
        builder.addMentionedRoles("9");
        assertThat(builder.getMentionedRoles()).containsExactly("9");
    }

    @Test
    void testMentionUsersAddAndSet() {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent("Test");

        // addMentionedUsers adds users incrementally
        builder.addMentionedUsers("10", "20");
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("10", "20");

        builder.addMentionedUsers("30");
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("10", "20", "30");

        // setMentionedUsers replaces users
        builder.setMentionedUsers("40", "50");
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("40", "50");

        // setMentionedUsers with long array
        builder.setMentionedUsers(60L, 70L);
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("60", "70");

        // addMentionedUsers with long array
        builder.addMentionedUsers(80L);
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("60", "70", "80");

        // setMentionedUsers(null) clears
        builder.setMentionedUsers((Collection<String>) null);
        assertThat(builder.getMentionedUsers()).isEmpty();

        // setMentionedUsers empty vararg clears
        builder.addMentionedUsers("90");
        assertThat(builder.getMentionedUsers()).containsExactly("90");
    }

    @Test
    @SuppressWarnings("deprecation")
    void testDeprecatedMentionMethodsDelegateToAdd() {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent("Test");

        builder.mentionRoles("1", "2");
        builder.mentionRoles("3");
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("1", "2", "3");

        builder.mentionUsers("10", "20");
        builder.mentionUsers("30");
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("10", "20", "30");
    }

    @Test
    void testMessageEditBuilderConfiguredMentions() {
        MessageEditBuilder builder = new MessageEditBuilder();
        builder.setMentionedRoles("1");
        try (MessageEditData data = builder.build()) {
            assertThat(data.toData().hasKey("allowed_mentions")).isTrue();
            assertThat(data.getMentionedRoles()).containsExactly("1");
        }

        builder = new MessageEditBuilder();
        builder.setMentionedUsers("10");
        try (MessageEditData data = builder.build()) {
            assertThat(data.toData().hasKey("allowed_mentions")).isTrue();
            assertThat(data.getMentionedUsers()).containsExactly("10");
        }

        builder = new MessageEditBuilder();
        builder.addMentionedRoles("2");
        try (MessageEditData data = builder.build()) {
            assertThat(data.toData().hasKey("allowed_mentions")).isTrue();
            assertThat(data.getMentionedRoles()).containsExactly("2");
        }

        builder = new MessageEditBuilder();
        builder.addMentionedUsers("20");
        try (MessageEditData data = builder.build()) {
            assertThat(data.toData().hasKey("allowed_mentions")).isTrue();
            assertThat(data.getMentionedUsers()).containsExactly("20");
        }
    }
}
