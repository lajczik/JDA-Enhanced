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

package net.dv8tion.jda.internal.utils.config;

import net.dv8tion.jda.api.hooks.VoiceDispatchInterceptor;
import net.dv8tion.jda.api.utils.ConcurrentSessionController;
import net.dv8tion.jda.api.utils.SessionController;
import net.dv8tion.jda.internal.utils.config.flags.ConfigFlag;

import java.util.EnumSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SessionConfig {
    private final SessionController sessionController;
    private final VoiceDispatchInterceptor interceptor;
    private final int largeThreshold;
    private final EnumSet<ConfigFlag> flags;
    private int maxReconnectDelay;

    public SessionConfig(
            @Nullable SessionController sessionController,
            @Nullable VoiceDispatchInterceptor interceptor,
            EnumSet<ConfigFlag> flags,
            int maxReconnectDelay,
            int largeThreshold) {
        this.sessionController = sessionController == null ? new ConcurrentSessionController() : sessionController;
        this.interceptor = interceptor;
        this.flags = flags;
        this.maxReconnectDelay = maxReconnectDelay;
        this.largeThreshold = largeThreshold;
    }

    @Nonnull
    public static SessionConfig getDefault() {
        return new SessionConfig(null, null, ConfigFlag.getDefault(), 900, 250);
    }

    @Nonnull
    public SessionController getSessionController() {
        return sessionController;
    }

    @Nullable
    public VoiceDispatchInterceptor getVoiceDispatchInterceptor() {
        return interceptor;
    }

    public boolean isAutoReconnect() {
        return flags.contains(ConfigFlag.AUTO_RECONNECT);
    }

    public void setAutoReconnect(boolean autoReconnect) {
        if (autoReconnect) {
            flags.add(ConfigFlag.AUTO_RECONNECT);
        } else {
            flags.remove(ConfigFlag.AUTO_RECONNECT);
        }
    }

    public boolean isRetryOnTimeout() {
        return flags.contains(ConfigFlag.RETRY_TIMEOUT);
    }

    public boolean isBulkDeleteSplittingEnabled() {
        return flags.contains(ConfigFlag.BULK_DELETE_SPLIT);
    }

    public boolean isRawEvents() {
        return flags.contains(ConfigFlag.RAW_EVENTS);
    }

    public boolean isEventPassthrough() {
        return flags.contains(ConfigFlag.EVENT_PASSTHROUGH);
    }

    public boolean isRelativeRateLimit() {
        return flags.contains(ConfigFlag.USE_RELATIVE_RATELIMIT);
    }

    public boolean isLazyMessages() {
        return flags.contains(ConfigFlag.LAZY_MESSAGES);
    }

    public boolean isStringDeduplication() {
        return flags.contains(ConfigFlag.STRING_DEDUPLICATION);
    }

    public int getMaxReconnectDelay() {
        return maxReconnectDelay;
    }

    public int getLargeThreshold() {
        return largeThreshold;
    }

    public EnumSet<ConfigFlag> getFlags() {
        return flags;
    }
}
