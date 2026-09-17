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

package net.dv8tion.jda.internal.utils.concurrent;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

public class CountingThreadFactory implements ThreadFactory {
    private final Supplier<String> identifier;
    private final AtomicLong count = new AtomicLong(1);
    private final boolean daemon;
    private final boolean virtual;
    private final BiFunction<Runnable, String, ? extends Thread> threadCreator;

    public CountingThreadFactory(@Nonnull Supplier<String> identifier, @Nonnull String specifier) {
        this(identifier, specifier, true);
    }

    public CountingThreadFactory(@Nonnull Supplier<String> identifier, @Nonnull String specifier, boolean daemon) {
        this(identifier, specifier, daemon, false);
    }

    public CountingThreadFactory(
            @Nonnull Supplier<String> identifier, @Nonnull String specifier, boolean daemon, boolean virtual) {
        if (specifier.isEmpty()) {
            this.identifier = identifier;
        } else {
            this.identifier = () -> identifier.get() + " " + specifier;
        }
        this.daemon = daemon;
        this.virtual = virtual;
        this.threadCreator = null;
    }

    public CountingThreadFactory(
            @Nonnull Supplier<String> identifier,
            @Nonnull String specifier,
            @Nonnull BiFunction<Runnable, String, ? extends Thread> threadCreator) {
        if (specifier.isEmpty()) {
            this.identifier = identifier;
        } else {
            this.identifier = () -> identifier.get() + " " + specifier;
        }
        this.daemon = false;
        this.virtual = false;
        this.threadCreator = threadCreator;
    }

    public boolean isVirtual() {
        return virtual;
    }

    public boolean isDaemon() {
        return daemon;
    }

    @Nonnull
    @Override
    public Thread newThread(@Nonnull Runnable r) {
        String name = identifier.get() + "-Worker " + count.getAndIncrement();
        if (threadCreator != null) {
            return threadCreator.apply(r, name);
        } else if (virtual) {
            return Thread.ofVirtual().name(name).unstarted(r);
        } else {
            return Thread.ofPlatform().name(name).daemon(daemon).unstarted(r);
        }
    }
}
