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

import io.netty.util.concurrent.FastThreadLocalThread;

import javax.annotation.Nonnull;

/**
 * Dedicated {@link FastThreadLocalThread} for Netty HttpClient EventLoop workers.
 * <p>Overrides {@link #run()} so that profilers, flame graphs, and thread dumps
 * clearly identify HttpClient execution roots as {@code HttpClientThread.run()}.
 */
public class HttpClientThread extends FastThreadLocalThread {
    public HttpClientThread(@Nonnull Runnable target, @Nonnull String name) {
        super(target, name);
        setDaemon(true);
    }

    @Override
    public void run() {
        super.run();
    }
}
