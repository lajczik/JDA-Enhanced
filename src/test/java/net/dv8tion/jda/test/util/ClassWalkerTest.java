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

package net.dv8tion.jda.test.util;

import net.dv8tion.jda.internal.utils.ClassWalker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ClassWalkerTest {
    interface InterfaceA {}

    interface InterfaceB extends InterfaceA {}

    static class BaseClass {}

    static class SubClass extends BaseClass implements InterfaceB {}

    @Test
    void testClassWalkerWalk() {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> clazz : ClassWalker.walk(SubClass.class)) {
            hierarchy.add(clazz);
        }

        assertThat(hierarchy).contains(SubClass.class, InterfaceB.class, InterfaceA.class, BaseClass.class);
        // Repeated calls should yield the exact same cached hierarchy
        List<Class<?>> hierarchy2 = new ArrayList<>();
        for (Class<?> clazz : ClassWalker.walk(SubClass.class)) {
            hierarchy2.add(clazz);
        }
        assertThat(hierarchy2).isEqualTo(hierarchy);
    }

    @Test
    void testClassWalkerRange() {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> clazz : ClassWalker.range(SubClass.class, BaseClass.class)) {
            hierarchy.add(clazz);
        }

        assertThat(hierarchy).contains(SubClass.class, InterfaceB.class, InterfaceA.class);
        assertThat(hierarchy).doesNotContain(BaseClass.class, Object.class);
    }
}
