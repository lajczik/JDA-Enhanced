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

package net.dv8tion.jda.api.utils;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;

import javax.annotation.Nonnull;

/**
 * Compression algorithms that can be used with JDA.
 *
 * @see JDABuilder#setCompression(Compression)
 * @see DefaultShardManagerBuilder#setCompression(Compression)
 */
public enum Compression {
    /** Don't use any compression */
    NONE(""),
    /** Use ZLIB transport compression */
    ZLIB("zlib-stream"),
    /** Use Zstandard streaming compression */
    ZSTD("zstd-stream");

    private final String key;

    Compression(String key) {
        this.key = key;
    }

    private static boolean isZstdSupported() {
        try {
            Class.forName("com.github.luben.zstd.ZstdDecompressCtx");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The key used for the gateway query to enable this compression
     *
     * @return The query key
     */
    @Nonnull
    public String getKey() {
        return key;
    }

    /**
     * Whether this compression algorithm is supported on the current classpath/runtime.
     *
     * @return True if supported, false otherwise
     */
    public boolean isSupported() {
        switch (this) {
            case NONE:
            case ZLIB:
                return true;
            case ZSTD:
                return isZstdSupported();
            default:
                return false;
        }
    }
}
