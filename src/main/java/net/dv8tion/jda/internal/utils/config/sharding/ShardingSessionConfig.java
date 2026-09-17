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

package net.dv8tion.jda.internal.utils.config.sharding;

import net.dv8tion.jda.api.hooks.VoiceDispatchInterceptor;
import net.dv8tion.jda.api.utils.SessionController;
import net.dv8tion.jda.internal.utils.config.SessionConfig;
import net.dv8tion.jda.internal.utils.config.flags.ConfigFlag;
import net.dv8tion.jda.internal.utils.config.flags.ShardingConfigFlag;

import java.util.EnumSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ShardingSessionConfig extends SessionConfig {
    private final EnumSet<ShardingConfigFlag> shardingFlags;

    public ShardingSessionConfig(
            @Nullable SessionController sessionController,
            @Nullable VoiceDispatchInterceptor interceptor,
            EnumSet<ConfigFlag> flags,
            EnumSet<ShardingConfigFlag> shardingFlags,
            int maxReconnectDelay,
            int largeThreshold) {
        super(sessionController, interceptor, flags, maxReconnectDelay, largeThreshold);
        this.shardingFlags = shardingFlags;
    }

    @Nonnull
    public static ShardingSessionConfig getDefault() {
        return new ShardingSessionConfig(
                null, null, ConfigFlag.getDefault(), ShardingConfigFlag.getDefault(), 900, 250);
    }

    public EnumSet<ShardingConfigFlag> getShardingFlags() {
        return this.shardingFlags;
    }

    public SessionConfig toSessionConfig() {
        return new SessionConfig(
                getSessionController(),
                getVoiceDispatchInterceptor(),
                getFlags(),
                getMaxReconnectDelay(),
                getLargeThreshold());
    }
}
