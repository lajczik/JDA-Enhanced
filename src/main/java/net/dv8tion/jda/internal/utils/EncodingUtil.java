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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EncodingUtil {
    private static final Pattern CODEPOINT_SPLIT_PATTERN = Pattern.compile("\\s*U\\+\\s*");
    private static final String PATH_SEGMENT_SAFE =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@";
    private static final String QUERY_PARAM_SAFE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";

    private static final boolean[] PATH_SEGMENT_SAFE_CHARS = new boolean[128];
    private static final boolean[] QUERY_PARAM_SAFE_CHARS = new boolean[128];
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    static {
        for (int i = 0; i < PATH_SEGMENT_SAFE.length(); i++) {
            char c = PATH_SEGMENT_SAFE.charAt(i);
            if (c < 128) {
                PATH_SEGMENT_SAFE_CHARS[c] = true;
            }
        }
        for (int i = 0; i < QUERY_PARAM_SAFE.length(); i++) {
            char c = QUERY_PARAM_SAFE.charAt(i);
            if (c < 128) {
                QUERY_PARAM_SAFE_CHARS[c] = true;
            }
        }
    }

    public static String encodePathSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return segment;
        }
        boolean needsEncoding = false;
        int len = segment.length();
        for (int i = 0; i < len; i++) {
            char c = segment.charAt(i);
            if (c >= 128 || !PATH_SEGMENT_SAFE_CHARS[c]) {
                needsEncoding = true;
                break;
            }
        }
        if (!needsEncoding) {
            return segment;
        }

        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 128 && PATH_SEGMENT_SAFE_CHARS[v]) {
                sb.append((char) v);
            } else {
                sb.append('%').append(HEX_DIGITS[v >>> 4]).append(HEX_DIGITS[v & 0xF]);
            }
        }
        return sb.toString();
    }

    public static String encodeQueryParam(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        boolean needsEncoding = false;
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c >= 128 || !QUERY_PARAM_SAFE_CHARS[c]) {
                needsEncoding = true;
                break;
            }
        }
        if (!needsEncoding) {
            return value;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 128 && QUERY_PARAM_SAFE_CHARS[v]) {
                sb.append((char) v);
            } else {
                sb.append('%').append(HEX_DIGITS[v >>> 4]).append(HEX_DIGITS[v & 0xF]);
            }
        }
        return sb.toString();
    }

    public static String encodeUTF8(String chars) {
        return URLEncoder.encode(chars, StandardCharsets.UTF_8);
    }

    public static String encodeCodepointsUTF8(String input) {
        if (!input.startsWith("U+")) {
            throw new IllegalArgumentException("Invalid format");
        }
        String[] codePoints = CODEPOINT_SPLIT_PATTERN.split(input.substring(2));
        StringBuilder encoded = new StringBuilder();
        for (String part : codePoints) {
            String utf16 = decodeCodepoint(part, 16);
            String urlEncoded = encodeUTF8(utf16);
            encoded.append(urlEncoded);
        }
        return encoded.toString();
    }

    public static String decodeCodepoint(String codepoint) {
        if (!codepoint.startsWith("U+")) {
            throw new IllegalArgumentException("Invalid format");
        }
        return decodeCodepoint(codepoint.substring(2), 16);
    }

    public static String encodeCodepoints(String unicode) {
        return unicode.codePoints()
                .mapToObj(code -> "U+" + Integer.toHexString(code))
                .collect(Collectors.joining());
    }

    private static String decodeCodepoint(String hex, int radix) {
        int codePoint = Integer.parseUnsignedInt(hex, radix);
        return Character.toString(codePoint);
    }

    /**
     * Encodes a unicode correctly based on being in codepoint notation or not.
     *
     * @param  unicode Provided unicode in the form of <code>\​uXXXX</code> or <code>U+XXXX</code>
     *
     * @return Never-null String containing the encoded unicode
     */
    public static String encodeReaction(String unicode) {
        if (unicode.startsWith("U+") || unicode.startsWith("u+")) {
            return encodeCodepointsUTF8(unicode);
        } else {
            return encodeUTF8(unicode);
        }
    }
}
