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
import net.dv8tion.jda.api.audio.dave.DaveSession;
import net.dv8tion.jda.internal.utils.ResizingByteBuf;

import java.nio.ByteBuffer;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
public class DaveCryptoAdapter implements CryptoAdapter {
    protected final CryptoAdapter transportCryptoAdapter;
    protected final DaveSession daveSession;
    protected final int ssrc;

    protected ResizingByteBuf encryptBuffer = new ResizingByteBuf(512);
    protected ResizingByteBuf decryptBuffer = null;

    public DaveCryptoAdapter(CryptoAdapter transportCryptoAdapter, DaveSession daveSession, int ssrc) {
        this.transportCryptoAdapter = transportCryptoAdapter;
        this.daveSession = daveSession;
        this.ssrc = ssrc;
    }

    @Override
    public AudioEncryption getMode() {
        return transportCryptoAdapter.getMode();
    }

    @Override
    public void encrypt(ResizingByteBuf output, ByteBuffer audio) {
        int maxSize = daveSession.getMaxEncryptedFrameSize(DaveSession.MediaType.AUDIO, audio.remaining());

        encryptBuffer.prepareWrite(maxSize);
        ByteBuffer daveEncryptedBuffer = encryptBuffer.nioBuffer(0, maxSize);

        if (daveSession.encrypt(DaveSession.MediaType.AUDIO, ssrc, audio, daveEncryptedBuffer)) {
            encryptBuffer.buffer().writerIndex(daveEncryptedBuffer.remaining());
            transportCryptoAdapter.encrypt(output, encryptBuffer.buffer());
        } else {
            throw new IllegalStateException("Failed to encrypt audio");
        }
    }

    @Override
    public void encrypt(ResizingByteBuf output, ByteBuf audio) {
        encrypt(output, audio.nioBuffer());
    }

    @Override
    public boolean decrypt(short extensionLength, long userId, ByteBuffer packet, ResizingByteBuf decrypted) {
        if (decryptBuffer == null) {
            decryptBuffer = new ResizingByteBuf(1024);
        }

        boolean success = transportCryptoAdapter.decrypt(extensionLength, userId, packet, decryptBuffer);
        if (!success) {
            return false;
        }

        return decryptDave(extensionLength, userId, decrypted);
    }

    @Override
    public boolean decrypt(short extensionLength, long userId, ByteBuf packet, ResizingByteBuf decrypted) {
        if (decryptBuffer == null) {
            decryptBuffer = new ResizingByteBuf(1024);
        }

        boolean success = transportCryptoAdapter.decrypt(extensionLength, userId, packet, decryptBuffer);
        if (!success) {
            return false;
        }

        return decryptDave(extensionLength, userId, decrypted);
    }

    private boolean decryptDave(short extensionLength, long userId, ResizingByteBuf decrypted) {
        ByteBuf transportDecrypted = decryptBuffer.buffer();
        handleRTPHeaderExtension(transportDecrypted, extensionLength);

        int outputSize = daveSession.getMaxDecryptedFrameSize(
                DaveSession.MediaType.AUDIO, userId, transportDecrypted.readableBytes());

        decrypted.prepareWrite(outputSize);
        ByteBuffer decryptedNio = decrypted.nioBuffer(0, outputSize);
        ByteBuffer transportNio = transportDecrypted.nioBuffer();

        boolean daveSuccess = daveSession.decrypt(DaveSession.MediaType.AUDIO, userId, transportNio, decryptedNio);
        if (daveSuccess) {
            decrypted.buffer().writerIndex(decryptedNio.remaining());
        }
        return daveSuccess;
    }

    private void handleRTPHeaderExtension(ByteBuf decrypted, short extensionLength) {
        if (extensionLength == 0) {
            return;
        }

        int length = ((int) extensionLength) & 0xFFFF;
        int offset = decrypted.readerIndex() + 4 * length;
        decrypted.readerIndex(Math.min(offset, decrypted.writerIndex()));
    }
}
