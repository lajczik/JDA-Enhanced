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

package net.dv8tion.jda.internal.requests;

import javax.annotation.Nullable;

/**
 * Lightweight holder for WebSocket close frame information (status code and reason),
 * replacing the use of {@code CloseWebSocketFrame} for field storage.
 *
 * <p>Unlike {@code CloseWebSocketFrame}, this record does not extend {@code ReferenceCounted}
 * and does not allocate a {@code ByteBuf}, eliminating memory leaks and unnecessary GC pressure
 * from storing close frame metadata as long-lived fields.
 *
 * @param statusCode the close status code
 * @param reason     the close reason, or null
 */
public record CloseFrameInfo(int statusCode, @Nullable String reason) {}
