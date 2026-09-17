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

package net.dv8tion.jda.api.utils.data;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Enum constants representing possible types for a {@link DataObject} value.
 */
public enum DataType {
    INT,
    FLOAT,
    STRING,
    OBJECT,
    ARRAY,
    BOOLEAN,
    NULL,
    UNKNOWN;

    /**
     * Assumes the type of the provided value through instance checks.
     *
     * @param  value
     *         The value to test
     *
     * @return The DataType constant or {@link #UNKNOWN}
     */
    @Nonnull
    public static DataType getType(@Nullable Object value) {
        return switch (value) {
            case null -> NULL;
            case Integer i -> INT;
            case Long l -> INT;
            case Short s -> INT;
            case Byte b -> INT;
            case Double d -> FLOAT;
            case Float f -> FLOAT;
            case String s -> STRING;
            case Boolean b -> BOOLEAN;
            case List<?> list -> ARRAY;
            case Map<?, ?> map -> OBJECT;
            default -> UNKNOWN;
        };
    }

    /**
     * Tests whether the type for the provided value is
     * the one represented by this enum constant.
     *
     * @param  value
     *         The value to check
     *
     * @return True, if the value is of this type
     */
    public boolean isType(@Nullable Object value) {
        return switch (this) {
            case INT ->
                value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
            case FLOAT -> value instanceof Double || value instanceof Float;
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case ARRAY -> value instanceof List;
            case OBJECT -> value instanceof Map;
            case NULL -> value == null;
            case UNKNOWN -> false;
        };
    }
}
