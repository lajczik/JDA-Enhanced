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

import com.sun.jna.ptr.PointerByReference;
import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramChannel;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.dv8tion.jda.api.audio.*;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import net.dv8tion.jda.api.audio.factory.IAudioSendFactory;
import net.dv8tion.jda.api.audio.factory.IAudioSendSystem;
import net.dv8tion.jda.api.audio.factory.IPacketProvider;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.ExceptionEvent;
import net.dv8tion.jda.api.utils.MiscUtil;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.managers.AudioManagerImpl;
import net.dv8tion.jda.internal.utils.IOUtil;
import net.dv8tion.jda.internal.utils.JDALogger;
import net.dv8tion.jda.internal.utils.ResizingByteBuf;
import org.slf4j.Logger;
import tomp2p.opuswrapper.Opus;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nonnull;

public class AudioConnection {
    public static final Logger LOG = JDALogger.getLog(AudioConnection.class);

    public static final long MAX_UINT_32 = 4294967295L;

    static final ByteBuffer silenceBytes = ByteBuffer.wrap(new byte[] {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE});
    private static boolean printedError = false;
    protected final ReentrantLock readyLock = new ReentrantLock();
    protected final Condition readyCondvar = readyLock.newCondition();
    private final ResizingByteBuf decryptBuffer = new ResizingByteBuf(1024);
    private final Int2LongMap ssrcMap;
    private final Int2ObjectMap<Decoder> opusDecoders = new Int2ObjectOpenHashMap<>();
    private final HashMap<User, Queue<AudioData>> combinedQueue = new HashMap<>();
    private final String threadIdentifier;
    private final AudioWebSocket webSocket;
    private final JDAImpl api;
    protected volatile DatagramChannel udpChannel;
    private ShortBuffer opusInputBuffer = null;
    private ByteBuffer opusOutputBuffer = null;
    private AudioChannel channel;
    private PointerByReference opusEncoder;
    private ScheduledExecutorService combinedAudioExecutor;
    private IAudioSendSystem sendSystem;
    private PacketProvider packetProvider;
    private Thread receiveThread;
    private long queueTimeout;
    private boolean shutdown = false;

    private volatile AudioSendHandler sendHandler = null;
    private volatile AudioReceiveHandler receiveHandler = null;

    @SuppressWarnings("unused") // it is used by a nested class!
    private volatile boolean couldReceive = false;

    private volatile int speakingMode = SpeakingMode.VOICE.getRaw();

    public AudioConnection(
            AudioManagerImpl manager, String endpoint, String sessionId, String token, AudioChannel channel) {
        this.api = (JDAImpl) channel.getJDA();
        this.channel = channel;
        Int2LongOpenHashMap ssrcMapInit = new Int2LongOpenHashMap();
        ssrcMapInit.defaultReturnValue(0L);
        this.ssrcMap = ssrcMapInit;
        JDAImpl api = (JDAImpl) channel.getJDA();
        this.threadIdentifier = api.getIdentifierString() + " AudioConnection Guild: "
                + channel.getGuild().getId();

        this.webSocket = new AudioWebSocket(
                this,
                manager.getListenerProxy(),
                endpoint,
                channel.getGuild(),
                sessionId,
                token,
                manager.isAutoReconnect());

        DaveSession daveSession = manager.getJDA()
                .getAudioModuleConfig()
                .getDaveSessionFactory()
                .createDaveSession(webSocket, manager.getJDA().getSelfUser().getIdLong(), channel.getIdLong());
        webSocket.setDaveSession(daveSession);
    }

    /* Used by AudioManagerImpl */

    public void startConnection() {
        webSocket.startConnection();
    }

    public ConnectionStatus getConnectionStatus() {
        return webSocket.getConnectionStatus();
    }

    public void setAutoReconnect(boolean shouldReconnect) {
        webSocket.setAutoReconnect(shouldReconnect);
    }

    public void setSendingHandler(AudioSendHandler handler) {
        this.sendHandler = handler;
        if (webSocket.isReady()) {
            setupSendSystem();
        }
    }

    public void setReceivingHandler(AudioReceiveHandler handler) {
        this.receiveHandler = handler;
        if (webSocket.isReady()) {
            setupReceiveSystem();
        }
    }

    public void setSpeakingMode(EnumSet<SpeakingMode> mode) {
        int raw = SpeakingMode.getRaw(mode);
        if (raw != this.speakingMode && webSocket.isReady()) {
            setSpeaking(raw);
        }
        this.speakingMode = raw;
    }

    public void setQueueTimeout(long queueTimeout) {
        this.queueTimeout = queueTimeout;
    }

    public AudioChannel getChannel() {
        return channel;
    }

    public void setChannel(AudioChannel channel) {
        this.channel = channel;
    }

    public JDAImpl getJDA() {
        return api;
    }

    public Guild getGuild() {
        return getChannel().getGuild();
    }

    public void close(ConnectionStatus closeStatus) {
        shutdown();
        webSocket.close(closeStatus);
    }

    public synchronized void shutdown() {
        shutdown = true;
        if (sendSystem != null) {
            sendSystem.shutdown();
            sendSystem = null;
        }
        if (packetProvider != null) {
            packetProvider.release();
            packetProvider = null;
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        if (udpChannel != null) {
            udpChannel.close();
            udpChannel = null;
        }
        if (combinedAudioExecutor != null) {
            combinedAudioExecutor.shutdownNow();
            combinedAudioExecutor = null;
        }
        if (opusEncoder != null) {
            Opus.INSTANCE.opus_encoder_destroy(opusEncoder);
            opusEncoder = null;
        }

        decryptBuffer.release();

        opusDecoders.values().forEach(Decoder::close);
        opusDecoders.clear();

        MiscUtil.locked(readyLock, readyCondvar::signalAll);
    }

    public AudioWebSocket getWebSocket() {
        return webSocket;
    }

    /* Used by AudioWebSocket */

    protected void prepareReady() {
        Thread.ofVirtual()
                .name(threadIdentifier + " Ready Thread")
                .uncaughtExceptionHandler((thread, throwable) -> {
                    LOG.error("Uncaught exception in Audio ready-thread", throwable);
                    JDAImpl api = getJDA();
                    api.handleEvent(new ExceptionEvent(api, throwable, true));
                })
                .start(() -> {
                    getJDA().setContext();

                    boolean ready = MiscUtil.locked(readyLock, () -> {
                        long timeout = getGuild().getAudioManager().getConnectTimeout();
                        while (!webSocket.isReady()) {
                            try {
                                boolean activated = readyCondvar.await(timeout, TimeUnit.MILLISECONDS);
                                if (!activated) {
                                    webSocket.close(ConnectionStatus.ERROR_CONNECTION_TIMEOUT);
                                    shutdown = true;
                                }
                                if (shutdown) {
                                    return false;
                                }
                            } catch (InterruptedException e) {
                                LOG.error("AudioConnection ready thread got interrupted while sleeping", e);
                                return false;
                            }
                        }

                        return true;
                    });

                    if (ready) {
                        setupSendSystem();
                        setupReceiveSystem();
                    }
                });
    }

    protected void removeUserSSRC(long userId) {
        for (Int2LongMap.Entry entry : ssrcMap.int2LongEntrySet()) {
            if (entry.getLongValue() == userId) {
                int ssrc = entry.getIntKey();
                ssrcMap.remove(ssrc);
                Decoder decoder = opusDecoders.remove(ssrc);
                if (decoder != null) { // cleanup decoder
                    decoder.close();
                }
                return;
            }
        }
    }

    protected void updateUserSSRC(int ssrc, long userId) {
        if (ssrcMap.containsKey(ssrc)) {
            long previousId = ssrcMap.get(ssrc);
            if (previousId != userId) {
                // Different User already existed with this ssrc. What should we do? Just
                // replace?
                // Probably should nuke the old opusDecoder.
                // Log for now and see if any user report the error.
                LOG.error(
                        "Yeah.. So.. JDA received a UserSSRC update for an ssrc that already had a User set. Inform"
                                + " devs.\n"
                                + "ChannelId: {} SSRC: {} oldId: {} newId: {}",
                        channel.getId(),
                        ssrc,
                        previousId,
                        userId);
            }
        } else {
            ssrcMap.put(ssrc, userId);

            // Only create a decoder if we are actively handling received audio.
            if (receiveHandler != null && AudioNatives.ensureOpus()) {
                opusDecoders.put(ssrc, new Decoder(ssrc));
            }
        }
    }

    /* Internals */

    private synchronized void setupSendSystem() {
        if (udpChannel != null && udpChannel.isActive() && sendHandler != null && sendSystem == null) {
            setSpeaking(speakingMode);
            IAudioSendFactory factory = getJDA().getAudioSendFactory();
            packetProvider = new PacketProvider();
            sendSystem = factory.createSendSystem(packetProvider);
            sendSystem.setContextMap(getJDA().getContextMap());
            sendSystem.start();
        } else if (sendHandler == null && sendSystem != null) {
            sendSystem.shutdown();
            sendSystem = null;
            if (packetProvider != null) {
                packetProvider.release();
                packetProvider = null;
            }

            if (opusEncoder != null) {
                Opus.INSTANCE.opus_encoder_destroy(opusEncoder);
                opusEncoder = null;
            }
        }
    }

    private synchronized void setupReceiveSystem() {
        if (receiveHandler != null && receiveHandler.canReceiveCombined()) {
            setupCombinedExecutor();
        } else if (receiveHandler == null || !receiveHandler.canReceiveCombined()) {
            if (combinedAudioExecutor != null) {
                combinedAudioExecutor.shutdownNow();
                combinedAudioExecutor = null;
            }
            if (receiveHandler == null) {
                opusDecoders.values().forEach(Decoder::close);
                opusDecoders.clear();
            }
        }
    }

    public void handleReceivedPacket(ByteBuf buffer) {
        AudioReceiveHandler handler = this.receiveHandler;
        boolean shouldDecode = handler != null && (handler.canReceiveUser() || handler.canReceiveCombined());
        boolean canReceive = handler != null
                && (handler.canReceiveUser() || handler.canReceiveCombined() || handler.canReceiveEncoded());
        if (canReceive && webSocket.getSecretKey() != null) {
            couldReceive = true;

            AudioPacket audioPacket = new AudioPacket(buffer);
            int ssrc = audioPacket.getSSRC();
            long userId = ssrcMap.getOrDefault(ssrc, 0L);
            if (userId == 0L) {
                return;
            }

            AudioPacket decryptedPacket = audioPacket.asDecryptAudioPacket(webSocket.crypto, userId, decryptBuffer);
            if (decryptedPacket == null) {
                return;
            }

            Decoder decoder = opusDecoders.get(ssrc);
            if (decoder == null) {
                if (AudioNatives.ensureOpus()) {
                    opusDecoders.put(ssrc, decoder = new Decoder(ssrc));
                } else if (!handler.canReceiveEncoded()) {
                    LOG.error("Unable to decode audio due to missing opus binaries!");
                    return;
                }
            }
            OpusPacket opusPacket = new OpusPacket(decryptedPacket, userId, decoder);
            if (handler.canReceiveEncoded()) {
                handler.handleEncodedAudio(opusPacket);
            }
            if (!shouldDecode || !opusPacket.canDecode()) {
                return;
            }

            User user = getJDA().getUserById(userId);
            if (user == null) {
                LOG.warn("Received audio data with a known SSRC, but the userId associate with the SSRC"
                        + " is unknown to JDA! You likely need to cache members.");
                return;
            }
            short[] decodedAudio = opusPacket.decode();
            if (decodedAudio == null) {
                return;
            }
            if (handler.canReceiveUser()) {
                handler.handleUserAudio(new UserAudio(user, decodedAudio));
            }
            if (handler.canReceiveCombined() && handler.includeUserInCombinedAudio(user)) {
                Queue<AudioData> queue = combinedQueue.get(user);
                if (queue == null) {
                    queue = new ConcurrentLinkedQueue<>();
                    combinedQueue.put(user, queue);
                }
                queue.add(new AudioData(decodedAudio));
            }
        } else {
            couldReceive = false;
        }
    }

    private synchronized void setupCombinedExecutor() {
        if (combinedAudioExecutor == null) {
            combinedAudioExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                    .daemon(true)
                    .name(threadIdentifier + " Combined Thread")
                    .uncaughtExceptionHandler((thread, throwable) -> {
                        LOG.error(
                                "I have no idea how, but there was an uncaught exception in the combinedAudioExecutor",
                                throwable);
                        JDAImpl api = getJDA();
                        api.handleEvent(new ExceptionEvent(api, throwable, true));
                    })
                    .factory());
            combinedAudioExecutor.scheduleAtFixedRate(
                    () -> {
                        getJDA().setContext();
                        try {
                            List<User> users = new ArrayList<>();
                            List<short[]> audioParts = new ArrayList<>();
                            if (receiveHandler != null && receiveHandler.canReceiveCombined()) {
                                long currentTime = System.currentTimeMillis();
                                for (Map.Entry<User, Queue<AudioData>> entry : combinedQueue.entrySet()) {
                                    User user = entry.getKey();
                                    Queue<AudioData> queue = entry.getValue();

                                    if (queue.isEmpty()) {
                                        continue;
                                    }

                                    AudioData audioData = queue.poll();
                                    // Make sure the audio packet is younger than 100ms
                                    while (audioData != null && currentTime - audioData.time > queueTimeout) {
                                        audioData = queue.poll();
                                    }

                                    // If none of the audio packets were younger than 100ms, then
                                    // there is nothing to add.
                                    if (audioData == null) {
                                        continue;
                                    }
                                    users.add(user);
                                    audioParts.add(audioData.data);
                                }

                                if (!audioParts.isEmpty()) {
                                    int audioLength = audioParts.stream()
                                            .mapToInt(it -> it.length)
                                            .max()
                                            .getAsInt();
                                    short[] mix = new short[1920]; // 960 PCM samples for each channel
                                    int sample;
                                    for (int i = 0; i < audioLength; i++) {
                                        sample = 0;
                                        for (Iterator<short[]> iterator = audioParts.iterator(); iterator.hasNext(); ) {
                                            short[] audio = iterator.next();
                                            if (i < audio.length) {
                                                sample += audio[i];
                                            } else {
                                                iterator.remove();
                                            }
                                        }
                                        if (sample > Short.MAX_VALUE) {
                                            mix[i] = Short.MAX_VALUE;
                                        } else if (sample < Short.MIN_VALUE) {
                                            mix[i] = Short.MIN_VALUE;
                                        } else {
                                            mix[i] = (short) sample;
                                        }
                                    }
                                    receiveHandler.handleCombinedAudio(new CombinedAudio(users, mix));
                                } else {
                                    // No audio to mix, provide 20 MS of silence.
                                    // (960 PCM samples for each channel)
                                    receiveHandler.handleCombinedAudio(new CombinedAudio(List.of(), new short[1920]));
                                }
                            }
                        } catch (Exception e) {
                            LOG.error("There was some unexpected exception in the combinedAudioExecutor!", e);
                        }
                    },
                    0,
                    20,
                    TimeUnit.MILLISECONDS);
        }
    }

    private ByteBuffer encodeToOpus(ByteBuffer rawAudio) {
        ShortBuffer inputShorts;
        if (rawAudio.isDirect()) {
            inputShorts = rawAudio.asShortBuffer();
        } else {
            int samples = rawAudio.remaining() / 2;
            if (opusInputBuffer == null || opusInputBuffer.capacity() < samples) {
                opusInputBuffer = ByteBuffer.allocateDirect(samples * 2).asShortBuffer();
            } else {
                opusInputBuffer.clear();
            }
            opusInputBuffer.put(rawAudio.asShortBuffer());
            ((Buffer) opusInputBuffer).flip();
            inputShorts = opusInputBuffer;
        }

        if (opusOutputBuffer == null) {
            opusOutputBuffer = ByteBuffer.allocateDirect(4096);
        } else {
            opusOutputBuffer.clear();
        }

        int result = Opus.INSTANCE.opus_encode(
                opusEncoder, inputShorts, OpusPacket.OPUS_FRAME_SIZE, opusOutputBuffer, opusOutputBuffer.capacity());
        if (result <= 0) {
            LOG.error("Received error code from opus_encode(...): {}", result);
            return null;
        }

        ((Buffer) opusOutputBuffer).position(0).limit(result);
        return opusOutputBuffer;
    }

    private void setSpeaking(int raw) {
        DataObject obj = DataObject.empty()
                .put("speaking", raw)
                .put("ssrc", webSocket.getSSRC())
                .put("delay", 0);
        webSocket.send(VoiceCode.USER_SPEAKING_UPDATE, obj);
    }

    private static class AudioData {
        private final long time;
        private final short[] data;

        private AudioData(short[] data) {
            this.time = System.currentTimeMillis();
            this.data = data;
        }
    }

    private class PacketProvider implements IPacketProvider {
        private final ResizingByteBuf buffer = new ResizingByteBuf(2048);
        private char seq = 0; // Sequence of audio packets. Used to determine the order of the packets.
        private int timestamp = 0; // Used to sync up our packets within the same timeframe of other people
        // talking.
        private ByteBuffer temporaryDirectBuffer = null;
        private ByteBuffer datagramBuffer = null;

        @Nonnull
        @Override
        public String getIdentifier() {
            return threadIdentifier;
        }

        @Nonnull
        @Override
        public AudioChannel getConnectedChannel() {
            return getChannel();
        }

        @Override
        public DatagramChannel getDatagramChannel() {
            return udpChannel;
        }

        @Nonnull
        @Override
        public DatagramSocket getUdpSocket() {
            throw new UnsupportedOperationException(
                    "JDA Audio uses Netty DatagramChannel instead of java.net.DatagramSocket. Use IPacketProvider.getDatagramChannel().");
        }

        @Nonnull
        @Override
        public InetSocketAddress getSocketAddress() {
            return webSocket.getAddress();
        }

        @Override
        public DatagramPacket getNextPacket(boolean unused) {
            ByteBuffer buffer = getNextPacketRaw(unused);
            return buffer == null ? null : getDatagramPacket(buffer);
        }

        private boolean provideNextPacket() {
            try {
                if (sendHandler != null && sendHandler.canProvide()) {
                    ByteBuffer rawAudio = sendHandler.provide20MsAudio();
                    if (rawAudio != null && rawAudio.hasRemaining()) {
                        if (!sendHandler.isOpus()) {
                            rawAudio = encodeAudio(rawAudio);
                            if (rawAudio == null) {
                                return false;
                            }
                        }

                        loadEncryptedPacketData(ensureDirect(rawAudio));

                        if (seq + 1 > Character.MAX_VALUE) {
                            seq = 0;
                        } else {
                            seq++;
                        }
                        timestamp += OpusPacket.OPUS_FRAME_SIZE;
                        return true;
                    }
                }
            } catch (Exception e) {
                LOG.error("There was an error while getting next audio packet", e);
            }
            return false;
        }

        @Override
        public ByteBuffer getNextPacketRaw(boolean unused) {
            return provideNextPacket() ? buffer.nioBuffer() : null;
        }

        @Override
        public ByteBuf getNextPacketByteBuf(boolean unused) {
            return provideNextPacket() ? buffer.buffer().retainedSlice() : null;
        }

        public void release() {
            buffer.release();
        }

        private ByteBuffer ensureDirect(ByteBuffer buffer) {
            if (buffer.isDirect()) {
                return buffer;
            }

            if (temporaryDirectBuffer == null) {
                temporaryDirectBuffer = ByteBuffer.allocateDirect(buffer.remaining());
            }

            temporaryDirectBuffer = IOUtil.replace(temporaryDirectBuffer, buffer);
            return temporaryDirectBuffer;
        }

        private ByteBuffer encodeAudio(ByteBuffer rawAudio) {
            if (opusEncoder == null) {
                if (!AudioNatives.ensureOpus()) {
                    if (!printedError) {
                        LOG.error("Unable to process PCM audio without opus binaries!");
                    }
                    printedError = true;
                    return null;
                }
                IntBuffer error = IntBuffer.allocate(1);
                opusEncoder = Opus.INSTANCE.opus_encoder_create(
                        OpusPacket.OPUS_SAMPLE_RATE, OpusPacket.OPUS_CHANNEL_COUNT, Opus.OPUS_APPLICATION_AUDIO, error);
                if (error.get() != Opus.OPUS_OK && opusEncoder == null) {
                    LOG.error("Received error status from opus_encoder_create(...): {}", error.get());
                    return null;
                }
            }
            return encodeToOpus(rawAudio);
        }

        private DatagramPacket getDatagramPacket(ByteBuffer b) {
            if (datagramBuffer == null) {
                datagramBuffer = ByteBuffer.allocate(b.remaining());
            }

            datagramBuffer = IOUtil.replace(datagramBuffer, b);

            byte[] data = datagramBuffer.array();
            int offset = datagramBuffer.arrayOffset() + datagramBuffer.position();
            int length = datagramBuffer.remaining();
            return new DatagramPacket(data, offset, length, webSocket.getAddress());
        }

        private void loadEncryptedPacketData(ByteBuffer rawAudio) {
            AudioPacket packet = new AudioPacket(seq, timestamp, webSocket.getSSRC(), rawAudio);
            packet.asEncryptedPacket(webSocket.crypto, buffer);
        }

        @Override
        public void onConnectionError(@Nonnull ConnectionStatus status) {
            LOG.warn("IAudioSendSystem reported a connection error of: {}", status);
            LOG.warn("Shutting down AudioConnection.");
            webSocket.close(status);
        }

        @Override
        public void onConnectionLost() {
            LOG.warn("Closing AudioConnection due to inability to send audio packets.");
            LOG.warn("Cannot send audio packet because JDA cannot navigate the route to Discord.\n"
                    + "Are you sure you have internet connection? It is likely that you've lost connection.");
            webSocket.close(ConnectionStatus.ERROR_LOST_CONNECTION);
        }
    }
}
