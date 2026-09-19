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

import net.dv8tion.jda.internal.utils.HashBag;

import java.util.Collection;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Defines a collection that counts the number of occurrences of each element.
 *
 * @param <E>
 *        The type of elements in this bag
 */
public interface Bag<E> extends Collection<E> {
    /**
     * Creates an empty, immutable {@link Bag}.
     *
     * @param <E> The element type
     * @return An empty bag
     */
    @Nonnull
    static <E> Bag<E> emptyBag() {
        return new HashBag<>();
    }

    /**
     * Returns the number of occurrences (cardinality) of the given object in this bag.
     *
     * @param  object
     *         The object to search for
     *
     * @return The number of occurrences of the object, or 0 if not found
     */
    int getCount(@Nullable Object object);

    /**
     * Adds {@code nCopies} copies of the specified object to the bag.
     *
     * @param  object
     *         The object to add
     * @param  nCopies
     *         The number of copies to add
     *
     * @return True, if the bag was modified as a result of this call
     */
    boolean add(@Nonnull E object, int nCopies);

    /**
     * Removes {@code nCopies} copies of the specified object from the bag.
     *
     * @param  object
     *         The object to remove
     * @param  nCopies
     *         The number of copies to remove
     *
     * @return True, if the bag was modified as a result of this call
     */
    boolean remove(@Nullable Object object, int nCopies);

    /**
     * Returns an unmodifiable {@link Set} of unique elements in this bag.
     *
     * @return An unmodifiable set of unique elements
     */
    @Nonnull
    Set<E> uniqueSet();
}
