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
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

public class MessageBuilderMentionsTest {
    @Test
    void testMentionRolesAddAndSet() {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent("Test");

        // addMentionRoles adds roles incrementally
        builder.addMentionRoles("1", "2");
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("1", "2");

        builder.addMentionRoles("3");
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("1", "2", "3");

        // setMentionRoles replaces roles
        builder.setMentionRoles("4", "5");
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("4", "5");

        // setMentionRoles with long array
        builder.setMentionRoles(6L, 7L);
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("6", "7");

        // addMentionRoles with long array
        builder.addMentionRoles(8L);
        assertThat(builder.getMentionedRoles()).containsExactlyInAnyOrder("6", "7", "8");

        // setMentionRoles(null) clears
        builder.setMentionRoles((Collection<String>) null);
        assertThat(builder.getMentionedRoles()).isEmpty();

        // setMentionRoles empty vararg clears
        builder.addMentionRoles("9");
        assertThat(builder.getMentionedRoles()).containsExactly("9");
    }

    @Test
    void testMentionUsersAddAndSet() {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent("Test");

        // addMentionUsers adds users incrementally
        builder.addMentionUsers("10", "20");
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("10", "20");

        builder.addMentionUsers("30");
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("10", "20", "30");

        // setMentionUsers replaces users
        builder.setMentionUsers("40", "50");
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("40", "50");

        // setMentionUsers with long array
        builder.setMentionUsers(60L, 70L);
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("60", "70");

        // addMentionUsers with long array
        builder.addMentionUsers(80L);
        assertThat(builder.getMentionedUsers()).containsExactlyInAnyOrder("60", "70", "80");

        // setMentionUsers(null) clears
        builder.setMentionUsers((Collection<String>) null);
        assertThat(builder.getMentionedUsers()).isEmpty();

        // setMentionUsers empty vararg clears
        builder.addMentionUsers("90");
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
}
