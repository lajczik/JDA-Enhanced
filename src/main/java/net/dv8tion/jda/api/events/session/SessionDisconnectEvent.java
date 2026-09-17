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

package net.dv8tion.jda.api.events.session;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.internal.requests.CloseFrameInfo;

import java.time.OffsetDateTime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Indicates that JDA has been disconnected from the remote server.
 * <br>When this event is fired JDA will try to reconnect if possible
 * unless {@link JDABuilder#setAutoReconnect(boolean) JDABuilder.setAutoReconnect(Boolean)}
 * has been provided {@code false} or the disconnect was too fatal in which case a {@link ShutdownEvent} is fired.
 *
 * <p>When reconnecting was successful either a {@link SessionRecreateEvent} <b>or</b> {@link SessionResumeEvent} is fired.
 */
public class SessionDisconnectEvent extends GenericSessionEvent {
    protected final CloseFrameInfo serverCloseFrame;
    protected final CloseFrameInfo clientCloseFrame;
    protected final boolean closedByServer;
    protected final OffsetDateTime disconnectTime;

    public SessionDisconnectEvent(
            @Nonnull JDA api,
            @Nullable CloseFrameInfo serverCloseFrame,
            @Nullable CloseFrameInfo clientCloseFrame,
            boolean closedByServer,
            @Nonnull OffsetDateTime disconnectTime) {
        super(api, SessionState.DISCONNECTED);
        this.serverCloseFrame = serverCloseFrame;
        this.clientCloseFrame = clientCloseFrame;
        this.closedByServer = closedByServer;
        this.disconnectTime = disconnectTime;
    }

    /**
     * Possibly-null {@link CloseCode}
     * representing the meaning for this DisconnectEvent
     *
     * <p><b>This is {@code null} if this disconnect did either not happen because the Service closed the session
     * (see {@link #isClosedByServer()}) or if there is no mapped CloseCode enum constant for the service close code!</b>
     *
     * @return Possibly-null {@link CloseCode}
     */
    @Nullable
    public CloseCode getCloseCode() {
        return serverCloseFrame != null ? CloseCode.from(serverCloseFrame.statusCode()) : null;
    }

    /**
     * The close frame discord sent to us
     *
     * @return The {@link CloseFrameInfo} discord sent as closing handshake, or null
     */
    @Nullable
    public CloseFrameInfo getServiceCloseFrame() {
        return serverCloseFrame;
    }

    /**
     * The close frame we sent to discord
     *
     * @return The {@link CloseFrameInfo} we sent as closing handshake, or null
     */
    @Nullable
    public CloseFrameInfo getClientCloseFrame() {
        return clientCloseFrame;
    }

    /**
     * Whether the connection was closed by discord
     *
     * @return True, if discord closed our connection
     */
    public boolean isClosedByServer() {
        return closedByServer;
    }

    /**
     * Time at which we noticed the disconnection
     *
     * @return Time of closure
     */
    @Nonnull
    public OffsetDateTime getTimeDisconnected() {
        return disconnectTime;
    }
}
