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

import com.google.crypto.tink.aead.internal.InsecureNonceAesGcmJce;
import com.google.crypto.tink.aead.internal.InsecureNonceXChaCha20Poly1305;
import io.netty.buffer.ByteBuf;
import net.dv8tion.jda.internal.utils.IOUtil;
import net.dv8tion.jda.internal.utils.ResizingByteBuf;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;

public interface CryptoAdapter {
    String AES_GCM_NO_PADDING = "AES_256/GCM/NOPADDING";

    AudioEncryption getMode();

    void encrypt(ResizingByteBuf output, ByteBuffer audio);

    boolean decrypt(short extensionLength, long userId, ByteBuffer packet, ResizingByteBuf decrypted);

    default void encrypt(ResizingByteBuf output, ByteBuf audio) {
        encrypt(output, audio.nioBuffer());
    }

    default boolean decrypt(short extensionLength, long userId, ByteBuf packet, ResizingByteBuf decrypted) {
        return decrypt(extensionLength, userId, packet.nioBuffer(), decrypted);
    }

    static AudioEncryption negotiate(EnumSet<AudioEncryption> supportedModes) {
        for (AudioEncryption mode : AudioEncryption.values()) {
            if (supportedModes.contains(mode) && isModeSupported(mode)) {
                return mode;
            }
        }

        return null;
    }

    static boolean isModeSupported(AudioEncryption mode) {
        switch (mode) {
            case AEAD_AES256_GCM_RTPSIZE:
                return Security.getAlgorithms("Cipher").contains(AES_GCM_NO_PADDING);
            case AEAD_XCHACHA20_POLY1305_RTPSIZE:
                return true;
            default:
                return false;
        }
    }

    static CryptoAdapter getAdapter(AudioEncryption mode, byte[] secretKey) {
        switch (mode) {
            case AEAD_AES256_GCM_RTPSIZE:
                return new CryptoAdapter.AES_GCM_Adapter(secretKey);
            case AEAD_XCHACHA20_POLY1305_RTPSIZE:
                return new XChaCha20Poly1305Adapter(secretKey);
            default:
                throw new IllegalStateException("Unsupported encryption mode: " + mode);
        }
    }

    abstract class AbstractAaedAdapter implements CryptoAdapter {
        protected static final int nonceBytes = 4;

        protected final byte[] secretKey;
        protected final byte[] nonceBuffer;
        protected final byte[] decryptNonceBuffer;
        protected final byte[] encryptAadBuffer = new byte[12];
        protected final byte[] decryptAadBuffer = new byte[12];
        protected final int tagBytes;
        protected final int paddedNonceBytes;
        protected int encryptCounter;

        protected AbstractAaedAdapter(byte[] secretKey, int tagBytes, int paddedNonceBytes) {
            this.secretKey = secretKey;
            this.tagBytes = tagBytes;
            this.paddedNonceBytes = paddedNonceBytes;
            this.nonceBuffer = new byte[paddedNonceBytes];
            this.decryptNonceBuffer = new byte[paddedNonceBytes];
            this.encryptCounter = ThreadLocalRandom.current().nextInt(1, 514);
        }

        @Override
        public void encrypt(ResizingByteBuf output, ByteBuffer audio) {
            int minimumOutputSize = audio.remaining() + this.tagBytes + nonceBytes;

            output.ensureWritable(minimumOutputSize);
            IOUtil.setIntBigEndian(nonceBuffer, 0, encryptCounter);

            try {
                encryptInternally(output.buffer(), audio, nonceBuffer);
                output.buffer().writeInt(encryptCounter++);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void encrypt(ResizingByteBuf output, ByteBuf audio) {
            int minimumOutputSize = audio.readableBytes() + this.tagBytes + nonceBytes;

            output.ensureWritable(minimumOutputSize);
            IOUtil.setIntBigEndian(nonceBuffer, 0, encryptCounter);

            try {
                encryptInternally(output.buffer(), audio, nonceBuffer);
                output.buffer().writeInt(encryptCounter++);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean decrypt(short extensionLength, long userId, ByteBuffer packet, ResizingByteBuf decrypted) {
            try {
                int headerLength = packet.position();
                packet.position(0);
                byte[] associatedData = (headerLength == 12) ? decryptAadBuffer : new byte[headerLength];
                packet.get(associatedData, 0, headerLength);
                byte[] cipherText = new byte[packet.remaining() - nonceBytes];
                packet.get(cipherText);
                byte[] nonce = decryptNonceBuffer;
                Arrays.fill(nonce, (byte) 0);
                packet.get(nonce, 0, nonceBytes);
                byte[] output = decryptInternally(cipherText, associatedData, nonce);

                decrypted.prepareWrite(output.length);
                decrypted.buffer().writeBytes(output);

                return true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean decrypt(short extensionLength, long userId, ByteBuf packet, ResizingByteBuf decrypted) {
            try {
                int headerLength = packet.readerIndex();
                byte[] associatedData = (headerLength == 12) ? decryptAadBuffer : new byte[headerLength];
                packet.getBytes(0, associatedData, 0, headerLength);
                int cipherTextLength = packet.readableBytes() - nonceBytes;
                ByteBuf cipherText = packet.readSlice(cipherTextLength);
                byte[] nonce = decryptNonceBuffer;
                Arrays.fill(nonce, (byte) 0);
                packet.readBytes(nonce, 0, nonceBytes);
                byte[] output = decryptInternally(cipherText, associatedData, nonce);

                decrypted.prepareWrite(output.length);
                decrypted.buffer().writeBytes(output);

                return true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        protected abstract void encryptInternally(ByteBuf output, ByteBuffer audio, byte[] nonce) throws Exception;

        protected void encryptInternally(ByteBuf output, ByteBuf audio, byte[] nonce) throws Exception {
            encryptInternally(output, audio.nioBuffer(), nonce);
        }

        protected abstract byte[] decryptInternally(byte[] cipherText, byte[] associatedData, byte[] nonce)
                throws Exception;

        protected byte[] decryptInternally(ByteBuf cipherText, byte[] associatedData, byte[] nonce) throws Exception {
            byte[] bytes = new byte[cipherText.readableBytes()];
            cipherText.readBytes(bytes);
            return decryptInternally(bytes, associatedData, nonce);
        }

        protected byte[] getAssociatedData(ByteBuf output) {
            int len = output.writerIndex();
            byte[] ad = (len == 12) ? encryptAadBuffer : new byte[len];
            output.getBytes(0, ad, 0, len);
            return ad;
        }

        protected byte[] getPlaintextCopy(ByteBuffer audio) {
            byte[] plaintext = new byte[audio.remaining()];
            audio.get(plaintext);
            return plaintext;
        }

        protected byte[] getPlaintextCopy(ByteBuf audio) {
            byte[] plaintext = new byte[audio.readableBytes()];
            audio.readBytes(plaintext);
            return plaintext;
        }
    }

    class AES_GCM_Adapter extends AbstractAaedAdapter implements CryptoAdapter {
        private final InsecureNonceAesGcmJce cipher;

        public AES_GCM_Adapter(byte[] secretKey) {
            super(secretKey, 16, 12);
            try {
                this.cipher = new InsecureNonceAesGcmJce(secretKey);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public AudioEncryption getMode() {
            return AudioEncryption.AEAD_AES256_GCM_RTPSIZE;
        }

        @Override
        protected void encryptInternally(ByteBuf output, ByteBuffer audio, byte[] nonce) throws Exception {
            byte[] input = getPlaintextCopy(audio);
            byte[] associatedData = getAssociatedData(output);
            output.writeBytes(cipher.encrypt(nonce, input, associatedData));
        }

        @Override
        protected void encryptInternally(ByteBuf output, ByteBuf audio, byte[] nonce) throws Exception {
            byte[] input = getPlaintextCopy(audio);
            byte[] associatedData = getAssociatedData(output);
            output.writeBytes(cipher.encrypt(nonce, input, associatedData));
        }

        @Override
        public byte[] decryptInternally(byte[] cipherText, byte[] associatedData, byte[] nonce) throws Exception {
            return cipher.decrypt(nonce, cipherText, associatedData);
        }
    }

    class XChaCha20Poly1305Adapter extends AbstractAaedAdapter implements CryptoAdapter {
        private final InsecureNonceXChaCha20Poly1305 cipher;

        public XChaCha20Poly1305Adapter(byte[] secretKey) {
            super(secretKey, 16, 24);
            try {
                this.cipher = new InsecureNonceXChaCha20Poly1305(secretKey);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public AudioEncryption getMode() {
            return AudioEncryption.AEAD_XCHACHA20_POLY1305_RTPSIZE;
        }

        @Override
        protected void encryptInternally(ByteBuf output, ByteBuffer audio, byte[] nonce) throws Exception {
            byte[] input = getPlaintextCopy(audio);
            byte[] associatedData = getAssociatedData(output);
            int written = input.length + tagBytes;
            ByteBuffer outputNio = output.nioBuffer(output.writerIndex(), output.writableBytes());
            cipher.encrypt(outputNio, nonce, input, associatedData);
            output.writerIndex(output.writerIndex() + written);
        }

        @Override
        protected void encryptInternally(ByteBuf output, ByteBuf audio, byte[] nonce) throws Exception {
            byte[] input = getPlaintextCopy(audio);
            byte[] associatedData = getAssociatedData(output);
            int written = input.length + tagBytes;
            ByteBuffer outputNio = output.nioBuffer(output.writerIndex(), output.writableBytes());
            cipher.encrypt(outputNio, nonce, input, associatedData);
            output.writerIndex(output.writerIndex() + written);
        }

        @Override
        public byte[] decryptInternally(byte[] cipherText, byte[] associatedData, byte[] nonce) throws Exception {
            return cipher.decrypt(nonce, cipherText, associatedData);
        }

        @Override
        protected byte[] decryptInternally(ByteBuf cipherText, byte[] associatedData, byte[] nonce) throws Exception {
            return cipher.decrypt(cipherText.nioBuffer(), nonce, associatedData);
        }
    }
}
