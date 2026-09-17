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

package net.dv8tion.jda.api.hooks;

import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.utils.ClassWalker;
import net.dv8tion.jda.internal.utils.JDALogger;
import org.jetbrains.annotations.Unmodifiable;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import javax.annotation.Nonnull;

/**
 * Implementation for {@link IEventManager
 * IEventManager}
 * which checks for {@link SubscribeEvent
 * SubscribeEvent} annotations on both
 * <b>static</b> and <b>member</b> methods.
 *
 * <p>
 * Listeners for this manager do <u>not</u> need to implement
 * {@link EventListener}
 * <br>
 * Example
 * {@snippet lang = "java":
 * public class Foo {
 *     @SubscribeEvent
 *     public void onMsg(MessageReceivedEvent event) {
 *         System.out.printf("%s: %s\n", event.getAuthor().getName(), event.getMessage().getContentDisplay());
 *     }
 * }
 * }
 *
 * @see InterfacedEventManager
 * @see IEventManager
 * @see SubscribeEvent
 */
public class AnnotatedEventManager implements IEventManager {
    private static final Logger LOGGER = JDALogger.getLog(AnnotatedEventManager.class);
    private final Object mutex = new Object();
    private final Set<Object> listeners = new HashSet<>();
    private volatile Map<Class<?>, Map<Object, List<Method>>> methods = Map.of();

    @Override
    public void register(@Nonnull Object listener) {
        if (listener.getClass().isArray()) {
            for (Object o : ((Object[]) listener)) {
                register(o);
            }
            return;
        }

        synchronized (mutex) {
            if (listeners.add(listener)) {
                updateMethods();
            }
        }
    }

    @Override
    public void unregister(@Nonnull Object listener) {
        if (listener.getClass().isArray()) {
            for (Object o : ((Object[]) listener)) {
                unregister(o);
            }
            return;
        }

        synchronized (mutex) {
            if (listeners.remove(listener)) {
                updateMethods();
            }
        }
    }

    @Nonnull
    @Override
    @Unmodifiable
    public List<Object> getRegisteredListeners() {
        synchronized (mutex) {
            return List.copyOf(listeners);
        }
    }

    @Override
    public void handle(@Nonnull GenericEvent event) {
        Map<Class<?>, Map<Object, List<Method>>> methodsSnapshot = this.methods;
        for (Class<?> eventClass : ClassWalker.walk(event.getClass())) {
            Map<Object, List<Method>> listeners = methodsSnapshot.get(eventClass);
            if (listeners != null) {
                listeners.forEach((key, value) -> value.forEach(method -> {
                    try {
                        method.setAccessible(true);
                        method.invoke(key, event);
                    } catch (IllegalAccessException | InvocationTargetException e1) {
                        JDAImpl.LOG.error("Couldn't access annotated EventListener method", e1);
                    } catch (Throwable throwable) {
                        JDAImpl.LOG.error("One of the EventListeners had an uncaught exception", throwable);
                        if (throwable instanceof Error) {
                            throw (Error) throwable;
                        }
                    }
                }));
            }
        }
    }

    private void updateMethods() {
        Map<Class<?>, Map<Object, List<Method>>> newMethods = new HashMap<>();
        for (Object listener : listeners) {
            registerListenerMethods(newMethods, listener);
        }
        this.methods = Collections.unmodifiableMap(newMethods);
    }

    private void registerListenerMethods(Map<Class<?>, Map<Object, List<Method>>> targetMethods, Object listener) {
        boolean isClass = listener instanceof Class;
        Class<?> c = isClass ? (Class<?>) listener : listener.getClass();
        Method[] allMethods = c.getDeclaredMethods();
        for (Method m : allMethods) {
            if (!m.isAnnotationPresent(SubscribeEvent.class)) {
                continue;
            }
            // Skip member methods if listener is a Class
            if (isClass && !Modifier.isStatic(m.getModifiers())) {
                continue;
            }

            Class<?>[] parameterTypes = m.getParameterTypes();
            if (parameterTypes.length != 1 || !GenericEvent.class.isAssignableFrom(parameterTypes[0])) {
                LOGGER.warn(
                        "Method '{}' annotated with @{} must have at most 1 parameter, which implements GenericEvent",
                        m,
                        SubscribeEvent.class.getSimpleName());
                continue;
            }

            Class<?> eventClass = parameterTypes[0];
            targetMethods
                    .computeIfAbsent(eventClass, k -> new HashMap<>())
                    .computeIfAbsent(listener, k -> new ArrayList<>())
                    .add(m);
        }
    }
}
