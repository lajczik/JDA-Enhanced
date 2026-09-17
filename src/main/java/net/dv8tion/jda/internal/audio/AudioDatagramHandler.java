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

package net.dv8tion.jda.internal.audio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import net.dv8tion.jda.internal.utils.JDALogger;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class AudioDatagramHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final Logger LOG = JDALogger.getLog(AudioDatagramHandler.class);

    private final AudioConnection audioConnection;
    private volatile CompletableFuture<InetSocketAddress> discoveryFuture;

    public AudioDatagramHandler(AudioConnection audioConnection) {
        this.audioConnection = audioConnection;
    }

    public void setDiscoveryFuture(CompletableFuture<InetSocketAddress> discoveryFuture) {
        this.discoveryFuture = discoveryFuture;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf content = packet.content();
        CompletableFuture<InetSocketAddress> future = this.discoveryFuture;

        // Check if this packet is an IP discovery response (74 bytes, type 2 at index 0-1)
        if (future != null && !future.isDone() && content.readableBytes() >= 74) {
            int type = content.getUnsignedShort(0);
            if (type == 2) {
                try {
                    int nullIndex = content.indexOf(8, 72, (byte) 0);
                    int ipLen = (nullIndex != -1 ? nullIndex : 72) - 8;
                    String ourIP = content.toString(8, ipLen, StandardCharsets.UTF_8);
                    int ourPort = content.getUnsignedShortLE(72);
                    future.complete(new InetSocketAddress(ourIP, ourPort));
                    return;
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    return;
                }
            }
        }

        // Otherwise, process as incoming audio packet
        audioConnection.handleReceivedPacket(content);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.debug("Exception caught in AudioDatagramHandler", cause);
    }
}
