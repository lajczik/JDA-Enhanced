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

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.dv8tion.jda.api.utils.Result;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.JDAImpl;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URI;
import java.time.*;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This class has major inspiration from <a href="https://commons.apache.org/proper/commons-lang/" target="_blank">Lang 3</a>
 *
 * <p>Specifically StringUtils.java and ExceptionUtils.java
 */
public final class Helpers {
    private static final Consumer<?> EMPTY_CONSUMER = v -> {};

    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> emptyConsumer() {
        return (Consumer<T>) EMPTY_CONSUMER;
    }

    public static OffsetDateTime toOffset(long instant) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(instant), ZoneOffset.UTC);
    }

    public static long toTimestamp(String iso8601String) {
        return OffsetDateTime.parse(iso8601String).toInstant().toEpochMilli();
    }

    public static OffsetDateTime toOffsetDateTime(@Nullable TemporalAccessor temporal) {
        if (temporal == null) {
            return null;
        } else if (temporal instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        } else {
            ZoneOffset offset;
            try {
                offset = ZoneOffset.from(temporal);
            } catch (DateTimeException ignore) {
                offset = ZoneOffset.UTC;
            }
            try {
                LocalDateTime ldt = LocalDateTime.from(temporal);
                return OffsetDateTime.of(ldt, offset);
            } catch (DateTimeException ignore) {
                try {
                    Instant instant = Instant.from(temporal);
                    return OffsetDateTime.ofInstant(instant, offset);
                } catch (DateTimeException ex) {
                    throw new DateTimeException(
                            "Unable to obtain OffsetDateTime from TemporalAccessor: " + temporal + " of type "
                                    + temporal.getClass().getName(),
                            ex);
                }
            }
        }
    }

    // locale-safe String#format

    @FormatMethod
    public static String format(@FormatString String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    // ## StringUtils ##

    public static boolean isEmpty(CharSequence seq) {
        return seq == null || seq.isEmpty();
    }

    public static boolean containsWhitespace(CharSequence seq) {
        if (isEmpty(seq)) {
            return false;
        }
        for (int i = 0; i < seq.length(); i++) {
            if (Character.isWhitespace(seq.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlank(CharSequence seq) {
        if (seq == null) {
            return true;
        }
        if (seq instanceof String str) {
            return str.isBlank();
        }
        for (int i = 0; i < seq.length(); i++) {
            if (!Character.isWhitespace(seq.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static int countMatches(CharSequence seq, char c) {
        if (isEmpty(seq)) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < seq.length(); i++) {
            if (seq.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    public static String truncate(String input, int maxWidth) {
        if (input == null) {
            return null;
        }
        Checks.notNegative(maxWidth, "maxWidth");
        if (input.length() <= maxWidth) {
            return input;
        }
        if (maxWidth == 0) {
            return "";
        }
        return input.substring(0, maxWidth);
    }

    public static String rightPad(String input, int size) {
        int pads = size - input.length();
        return pads <= 0 ? input : input + " ".repeat(pads);
    }

    public static String leftPad(String input, int size) {
        int pads = size - input.length();
        return pads <= 0 ? input : " ".repeat(pads) + input;
    }

    public static boolean isNumeric(String input) {
        if (isEmpty(input)) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    public static int codePointLength(CharSequence string) {
        return (int) string.codePoints().count();
    }

    public static String[] split(String input, String match) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            int j = input.indexOf(match, i);
            if (j == -1) {
                out.add(input.substring(i));
                break;
            }

            out.add(input.substring(i, j));
            i = j + match.length();
        }

        return out.toArray(String[]::new);
    }

    @SuppressWarnings({"ReferenceEquality", "StringEquality"})
    public static boolean equals(String a, String b, boolean ignoreCase) {
        return ignoreCase ? a == b || (a != null && a.equalsIgnoreCase(b)) : Objects.equals(a, b);
    }

    // ## CollectionUtils ##

    @SuppressWarnings("ReferenceEquality")
    public static boolean deepEquals(Collection<?> first, Collection<?> second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || first.size() != second.size()) {
            return false;
        }
        for (Iterator<?> itFirst = first.iterator(), itSecond = second.iterator(); itFirst.hasNext(); ) {
            Object elementFirst = itFirst.next();
            Object elementSecond = itSecond.next();
            if (!Objects.equals(elementFirst, elementSecond)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"ReferenceEquality", "SuspiciousMethodCalls"})
    public static boolean deepEqualsUnordered(Collection<?> first, Collection<?> second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.size() == second.size() && second.containsAll(first);
    }

    public static <E extends Enum<E>> EnumSet<E> copyEnumSet(Class<E> clazz, Collection<E> col) {
        return col == null || col.isEmpty() ? EnumSet.noneOf(clazz) : EnumSet.copyOf(col);
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... elements) {
        Set<T> set = HashSet.newHashSet(elements.length);
        Collections.addAll(set, elements);
        return set;
    }

    @SafeVarargs
    public static <T> List<T> listOf(T... elements) {
        return List.of(elements);
    }

    public static Long2ObjectMap<DataObject> convertToMap(ToLongFunction<DataObject> getId, DataArray array) {
        Long2ObjectMap<DataObject> map = new Long2ObjectOpenHashMap<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            DataObject obj = array.getObject(i);
            long objId = getId.applyAsLong(obj);
            map.put(objId, obj);
        }
        return map;
    }

    public static <T> Long2ObjectMap<T> convertToMap(ToLongFunction<T> getId, Collection<T> collection) {
        Long2ObjectMap<T> map = new Long2ObjectOpenHashMap<>(collection.size());
        for (T obj : collection) {
            map.put(getId.applyAsLong(obj), obj);
        }
        return map;
    }

    public static <T> LongSet toLongSet(Collection<T> collection, ToLongFunction<T> getId) {
        LongSet set = new LongOpenHashSet(collection.size());
        for (T obj : collection) {
            set.add(getId.applyAsLong(obj));
        }
        return set;
    }

    public static LongSet toLongSet(long... values) {
        return new LongOpenHashSet(values);
    }

    public static <I, O> Function<I, Result<O>> tryMap(Function<I, O> mapper) {
        return element -> Result.defer(() -> mapper.apply(element));
    }

    public static <I, O> Stream<O> mapGracefully(Stream<I> stream, Function<I, O> mapper, String errorDescription) {
        return stream.map(tryMap(mapper))
                .peek(result -> {
                    if (result.isFailure()) {
                        JDAImpl.LOG.error(errorDescription, result.getFailure());
                    }
                })
                .filter(Result::isSuccess)
                .map(Result::get);
    }

    // ## ExceptionUtils ##

    public static <T extends Throwable> T appendCause(T throwable, Throwable cause) {
        Throwable t = throwable;

        for (int i = 0; i < 5; i++) {
            if (t.getCause() == null) {
                t.initCause(cause);
                return throwable;
            } else {
                t = t.getCause();
            }
        }

        // Exception is too deep, add it on the initial exception as suppressed
        throwable.addSuppressed(cause);
        return throwable;
    }

    public static boolean hasCause(Throwable throwable, Class<? extends Throwable> cause) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cause.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    public static <T> Collector<T, ?, List<T>> toUnmodifiableList() {
        return Collectors.toUnmodifiableList();
    }

    public static <E extends Enum<E>> Collector<E, ?, Set<E>> toUnmodifiableEnumSet(Class<E> enumType) {
        return Collectors.collectingAndThen(
                Collectors.toCollection(() -> EnumSet.noneOf(enumType)), Collections::unmodifiableSet);
    }

    @SafeVarargs
    public static <E extends Enum<E>> Set<E> unmodifiableEnumSet(E first, E... rest) {
        return Collections.unmodifiableSet(EnumSet.of(first, rest));
    }

    @Nonnull
    @Unmodifiable
    public static <E> List<E> copyAsUnmodifiableList(Collection<? extends E> items) {
        return items == null || items.isEmpty() ? List.of() : List.copyOf(items);
    }

    @Nonnull
    @SafeVarargs
    public static <E> List<E> mergeVararg(@Nonnull E first, @Nonnull E... other) {
        if (other.length == 0) {
            return List.of(first);
        }
        List<E> list = new ArrayList<>(other.length + 1);
        list.add(first);
        Collections.addAll(list, other);
        return list;
    }

    public static <T> Collector<T, ?, DataArray> toDataArray() {
        return Collector.of(DataArray::empty, DataArray::add, DataArray::addAll);
    }

    public static String durationToString(Duration duration, TimeUnit resolutionUnit) {
        long actual = resolutionUnit.convert(duration.getSeconds(), TimeUnit.SECONDS);
        String raw = actual + " " + resolutionUnit.toString().toLowerCase(Locale.ROOT);

        long days = duration.toDays();
        int hours = duration.toHoursPart();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();

        StringJoiner joiner = new StringJoiner(" ");
        if (days > 0) {
            joiner.add(days + " days");
        }
        if (hours > 0) {
            joiner.add(hours + " hours");
        }
        if (minutes > 0) {
            joiner.add(minutes + " minutes");
        }
        if (seconds > 0) {
            joiner.add(seconds + " seconds");
        }

        return raw + " (" + joiner + ")";
    }

    @Nonnull
    public static String getLastPathSegment(@Nonnull String url) {
        URI uri = URI.create(url);
        String path = uri.getPath();
        Checks.check(path != null && !path.isEmpty(), "URL '%s' is invalid", url);
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash != -1 ? path.substring(lastSlash + 1) : path;
    }
}
