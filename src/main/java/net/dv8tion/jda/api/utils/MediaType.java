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

import net.dv8tion.jda.internal.utils.Checks;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents a media type (MIME type) for HTTP requests and attachments.
 */
public enum MediaType {
    JSON("application/json; charset=utf-8"),
    TEXT_PLAIN("text/plain; charset=utf-8"),
    OCTET("application/octet-stream"),
    PNG("image/png"),
    GIF("image/gif"),
    JPEG("image/jpeg"),
    WEBP("image/webp"),
    AUDIO_OGG("audio/ogg"),
    FORM("multipart/form-data"),
    MIXED("multipart/mixed"),
    ALTERNATIVE("multipart/alternative"),
    DIGEST("multipart/digest"),
    PARALLEL("multipart/parallel");

    private final String value;
    private final String type;
    private final String subtype;

    MediaType(@Nonnull String value) {
        this.value = value;
        String raw = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String[] parts = raw.split("/", 2);
        this.type = parts[0];
        this.subtype = parts.length > 1 ? parts[1] : "";
    }

    @Nullable
    public static MediaType parse(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        for (MediaType mediaType : values()) {
            String candidate = mediaType.value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (candidate.equals(normalized)) {
                return mediaType;
            }
        }
        return OCTET;
    }

    @Nonnull
    public static MediaType fromExtension(@Nonnull String extension) {
        Checks.notNull(extension, "Extension");
        switch (extension.toLowerCase(Locale.ROOT)) {
            case "png":
            case "apng":
                return PNG;
            case "gif":
                return GIF;
            case "jpg":
            case "jpeg":
                return JPEG;
            case "webp":
                return WEBP;
            case "json":
                return JSON;
            case "txt":
                return TEXT_PLAIN;
            case "ogg":
            case "oga":
                return AUDIO_OGG;
            default:
                return OCTET;
        }
    }

    @Nonnull
    public String type() {
        return type;
    }

    @Nonnull
    public String subtype() {
        return subtype;
    }

    @Nonnull
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
