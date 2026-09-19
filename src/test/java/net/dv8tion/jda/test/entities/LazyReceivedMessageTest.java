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

package net.dv8tion.jda.test.entities;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.entities.*;
import net.dv8tion.jda.internal.utils.cache.MemberCacheViewImpl;
import net.dv8tion.jda.internal.utils.cache.SnowflakeCacheViewImpl;
import net.dv8tion.jda.test.Constants;
import net.dv8tion.jda.test.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class LazyReceivedMessageTest extends IntegrationTest {
    @Mock
    private GuildImpl guild;

    private DataObject createSampleMessagePayload() {
        return DataObject.fromJson("""
                {
                  "id": "123456789012345678",
                  "channel_id": "987654321098765432",
                  "guild_id": "125227483518861312",
                  "type": 0,
                  "flags": 0,
                  "content": "Hello world with accessories!",
                  "tts": false,
                  "pinned": false,
                  "mention_everyone": false,
                  "nonce": "test-nonce",
                  "author": {
                    "id": "86699011792191488",
                    "username": "minn",
                    "discriminator": "0",
                    "avatar": null,
                    "public_flags": 0
                  },
                  "mentions": [],
                  "mention_roles": [],
                  "attachments": [
                    {
                      "id": "111111111111111111",
                      "filename": "test.png",
                      "size": 1024,
                      "url": "https://cdn.discordapp.com/attachments/1/test.png",
                      "proxy_url": "https://media.discordapp.net/attachments/1/test.png",
                      "width": 100,
                      "height": 100
                    }
                  ],
                  "embeds": [
                    {
                      "title": "Embed Title",
                      "description": "Embed description",
                      "type": "rich",
                      "color": 16711680,
                      "fields": [
                        {
                          "name": "Field1",
                          "value": "Value1",
                          "inline": true
                        }
                      ]
                    }
                  ],
                  "reactions": [
                    {
                      "count": 5,
                      "me": false,
                      "me_burst": false,
                      "emoji": {
                        "id": null,
                        "name": "👍"
                      }
                    }
                  ],
                  "components": [
                    {
                      "type": 1,
                      "components": [
                        {
                          "type": 2,
                          "style": 1,
                          "label": "Click me",
                          "custom_id": "btn_click"
                        }
                      ]
                    }
                  ]
                }
                """);
    }

    private void setupMockJDA() {
        doReturn(new SnowflakeCacheViewImpl<>(User.class, User::getName))
                .when(jda)
                .getUsersView();
        doReturn(new SelfUserImpl(Constants.BUTLER_USER_ID, jda)).when(jda).getSelfUser();
        MemberCacheViewImpl memberCache = mock(MemberCacheViewImpl.class);
        doReturn(memberCache).when(guild).getMembersView();
    }

    @Test
    void testEagerVsLazyParsingParity() {
        setupMockMockEnvironment(false);
        EntityBuilder eagerBuilder = new EntityBuilder(jda);
        DataObject data = createSampleMessagePayload();

        ReceivedMessage eagerMessage = eagerBuilder.createMessageWithLookup(data, guild, false);
        assertThat(eagerMessage).isNotInstanceOf(LazyReceivedMessage.class);

        setupMockMockEnvironment(true);
        EntityBuilder lazyBuilder = new EntityBuilder(jda);
        ReceivedMessage lazyMessage = lazyBuilder.createMessageWithLookup(data, guild, false);
        assertThat(lazyMessage).isInstanceOf(LazyReceivedMessage.class);

        // Verify all fields are identical
        assertThat(lazyMessage.getId()).isEqualTo(eagerMessage.getId());
        assertThat(lazyMessage.getContentRaw()).isEqualTo(eagerMessage.getContentRaw());

        // Attachments
        assertThat(lazyMessage.getAttachments()).hasSize(1);
        assertThat(lazyMessage.getAttachments().get(0).getFileName())
                .isEqualTo(eagerMessage.getAttachments().get(0).getFileName());

        // Embeds
        assertThat(lazyMessage.getEmbeds()).hasSize(1);
        assertThat(lazyMessage.getEmbeds().get(0).getTitle())
                .isEqualTo(eagerMessage.getEmbeds().get(0).getTitle());
        assertThat(lazyMessage.getEmbeds().get(0).getFields().get(0).getName())
                .isEqualTo(eagerMessage.getEmbeds().get(0).getFields().get(0).getName());

        // Reactions
        assertThat(lazyMessage.getReactions()).hasSize(1);
        assertThat(lazyMessage.getReactions().get(0).getEmoji().getName())
                .isEqualTo(eagerMessage.getReactions().get(0).getEmoji().getName());

        // Components
        assertThat(lazyMessage.getComponents()).hasSize(1);
        assertThat(lazyMessage.getComponents().get(0)).isInstanceOf(ActionRow.class);
        ActionRow lazyRow = (ActionRow) lazyMessage.getComponents().get(0);
        ActionRow eagerRow = (ActionRow) eagerMessage.getComponents().get(0);
        assertThat(lazyRow.getButtons().get(0).getLabel())
                .isEqualTo(eagerRow.getButtons().get(0).getLabel());
    }

    @Test
    void testLazyMessageThreadSafety() throws Exception {
        setupMockMockEnvironment(true);
        EntityBuilder lazyBuilder = new EntityBuilder(jda);
        DataObject data = createSampleMessagePayload();
        LazyReceivedMessage lazyMessage = (LazyReceivedMessage) lazyBuilder.createMessageWithLookup(data, guild, false);

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<List<MessageEmbed>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(lazyMessage::getEmbeds));
        }

        List<MessageEmbed> firstResult = futures.get(0).get(5, TimeUnit.SECONDS);
        for (Future<List<MessageEmbed>> future : futures) {
            List<MessageEmbed> result = future.get(5, TimeUnit.SECONDS);
            // Verify all threads received identical instance (cached)
            assertThat(result).isSameAs(firstResult);
        }

        executor.shutdown();
    }

    @Test
    void testJDABuilderAndShardManagerConfiguration() {
        JDABuilder jdaBuilder = JDABuilder.createDefault("token");
        assertThat(jdaBuilder.setLazyMessageParsing(true)).isSameAs(jdaBuilder);

        DefaultShardManagerBuilder shardBuilder = DefaultShardManagerBuilder.createDefault("token");
        assertThat(shardBuilder.setLazyMessageParsing(true)).isSameAs(shardBuilder);
    }

    private void setupMockMockEnvironment(boolean lazy) {
        setupMockJDA();
        doReturn(lazy).when(jda).isLazyMessages();
    }
}
