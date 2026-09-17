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

package net.dv8tion.jda.internal.utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nonnull;

public class ClassWalker implements Iterable<Class<?>> {
    private static final ConcurrentMap<ClassPair, List<Class<?>>> CACHE = new ConcurrentHashMap<>();

    private final Class<?> clazz;
    private final Class<?> end;

    private ClassWalker(Class<?> clazz) {
        this(clazz, Object.class);
    }

    private ClassWalker(Class<?> clazz, Class<?> end) {
        this.clazz = clazz;
        this.end = end;
    }

    public static ClassWalker range(Class<?> start, Class<?> end) {
        return new ClassWalker(start, end);
    }

    public static ClassWalker walk(Class<?> start) {
        return new ClassWalker(start);
    }

    private static List<Class<?>> computeHierarchy(ClassPair pair) {
        List<Class<?>> result = new ArrayList<>();
        Set<Class<?>> done = new HashSet<>();
        Deque<Class<?>> work = new ArrayDeque<>();

        work.addLast(pair.start);
        done.add(pair.end);

        while (!work.isEmpty()) {
            Class<?> current = work.removeFirst();
            done.add(current);
            result.add(current);
            for (Class<?> parent : current.getInterfaces()) {
                if (!done.contains(parent)) {
                    work.addLast(parent);
                }
            }

            Class<?> parent = current.getSuperclass();
            if (parent != null && !done.contains(parent)) {
                work.addLast(parent);
            }
        }
        return List.copyOf(result);
    }

    @Nonnull
    public List<Class<?>> getHierarchy() {
        return CACHE.computeIfAbsent(new ClassPair(clazz, end), ClassWalker::computeHierarchy);
    }

    @Nonnull
    @Override
    public Iterator<Class<?>> iterator() {
        return getHierarchy().iterator();
    }

    private record ClassPair(Class<?> start, Class<?> end) {}
}
