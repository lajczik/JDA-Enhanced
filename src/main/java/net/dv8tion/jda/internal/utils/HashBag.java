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

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.dv8tion.jda.api.utils.Bag;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import javax.annotation.Nonnull;

public class HashBag<E> extends AbstractCollection<E> implements Bag<E> {
    private final Object2IntOpenHashMap<E> map;
    private int size;

    public HashBag() {
        this.map = new Object2IntOpenHashMap<>(50, 0.5f);
        this.map.defaultReturnValue(0);
        this.size = 0;
    }

    public HashBag(Collection<? extends E> collection) {
        this();
        if (collection != null) {
            addAll(collection);
        }
    }

    @Override
    public int getCount(Object object) {
        return map.getInt(object);
    }

    @Override
    public boolean add(E object) {
        return add(object, 1);
    }

    @Override
    public boolean add(@NotNull E object, int nCopies) {
        if (nCopies <= 0) {
            return false;
        }
        map.addTo(object, nCopies);
        size += nCopies;
        return true;
    }

    @Override
    public boolean remove(Object object) {
        return remove(object, 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean remove(Object object, int nCopies) {
        if (nCopies <= 0) {
            return false;
        }

        int currentCount = map.getInt(object);
        if (currentCount == 0) {
            return false;
        }

        if (currentCount <= nCopies) {
            map.removeInt(object);
            size -= currentCount;
        } else {
            map.addTo((E) object, -nCopies);
            size -= nCopies;
        }

        return true;
    }

    @Nonnull
    @Override
    public Set<E> uniqueSet() {
        return Collections.unmodifiableSet(map.keySet());
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    @Override
    public void clear() {
        map.clear();
        size = 0;
    }

    @Nonnull
    @Override
    public Iterator<E> iterator() {
        return new Iterator<>() {
            private final ObjectIterator<Object2IntMap.Entry<E>> entryIterator =
                    map.object2IntEntrySet().fastIterator();
            private Object2IntMap.Entry<E> currentEntry;
            private E currentKey;
            private int currentCount = 0;
            private boolean canRemove = false;

            @Override
            public boolean hasNext() {
                return currentCount > 0 || entryIterator.hasNext();
            }

            @Override
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                if (currentCount == 0) {
                    currentEntry = entryIterator.next();
                    currentKey = currentEntry.getKey();
                    currentCount = currentEntry.getIntValue();
                }
                currentCount--;
                canRemove = true;
                return currentKey;
            }

            @Override
            public void remove() {
                if (!canRemove) {
                    throw new IllegalStateException();
                }
                canRemove = false;
                size--;
                int count = currentEntry.getIntValue();
                if (count == 1) {
                    entryIterator.remove();
                } else {
                    currentEntry.setValue(count - 1);
                }
            }
        };
    }
}
