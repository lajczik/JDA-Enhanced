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

package net.dv8tion.jda.test.requests;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.utils.AttachedFile;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MediaType;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.internal.requests.Requester;
import net.dv8tion.jda.internal.utils.FutureUtil;
import net.dv8tion.jda.internal.utils.IOUtil;
import net.dv8tion.jda.internal.utils.requestbody.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestBodyTest {

    @Test
    void testByteBufRequestBody() throws IOException {
        byte[] payload = "Hello Netty ByteBuf!".getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.copiedBuffer(payload);
        ByteBufRequestBody body = new ByteBufRequestBody(buf, MediaType.TEXT_PLAIN);

        assertThat(body.contentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(body.contentLength()).isEqualTo(payload.length);
        assertThat(body.toBytes()).isEqualTo(payload);

        // Test getByteBuf with allocator
        ByteBuf retrieved = body.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(retrieved.readableBytes()).isEqualTo(payload.length);
            byte[] readBytes = new byte[retrieved.readableBytes()];
            retrieved.getBytes(retrieved.readerIndex(), readBytes);
            assertThat(readBytes).isEqualTo(payload);
        } finally {
            retrieved.release();
        }

        // Test writeTo(ByteBuf) advances writerIndex
        ByteBuf target = Unpooled.buffer();
        try {
            body.writeTo(target);
            assertThat(target.readableBytes()).isEqualTo(payload.length);
            byte[] targetBytes = new byte[target.readableBytes()];
            target.readBytes(targetBytes);
            assertThat(targetBytes).isEqualTo(payload);
        } finally {
            target.release();
        }

        // Test writeTo(OutputStream)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        body.writeTo(baos);
        assertThat(baos.toByteArray()).isEqualTo(payload);

        // Test getInputStream()
        try (InputStream stream = body.getInputStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(payload);
        }

        // Test withType
        ByteBufRequestBody newTypeBody = body.withType(MediaType.JSON);
        assertThat(newTypeBody.contentType()).isEqualTo(MediaType.JSON);
        assertThat(newTypeBody.toBytes()).isEqualTo(payload);

        // Release bodies
        newTypeBody.close();
        body.close();
        assertThat(buf.refCnt()).isEqualTo(0);
    }

    @Test
    void testMultipartBodyCompositeByteBuf() throws IOException {
        byte[] fileData = "File content inside multipart".getBytes(StandardCharsets.UTF_8);
        ByteBufRequestBody fileBody = new ByteBufRequestBody(Unpooled.wrappedBuffer(fileData), MediaType.OCTET);

        MultipartBody multipart = new MultipartBody.Builder("test_boundary_123")
                .addFormDataPart("payload_json", "{\"name\":\"bot\"}")
                .addFormDataPart("files[0]", "file.txt", fileBody)
                .build();

        assertThat(multipart.contentType()).isEqualTo(MediaType.FORM);
        assertThat(multipart.contentTypeHeader()).contains("multipart/form-data; boundary=");

        long calculatedLength = multipart.contentLength();
        assertThat(calculatedLength).isGreaterThan(0);

        ByteBuf composite = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(composite).isInstanceOf(CompositeByteBuf.class);
            assertThat(composite.readableBytes()).isEqualTo((int) calculatedLength);
        } finally {
            composite.release();
        }

        // Test writeTo(ByteBuf)
        ByteBuf target = Unpooled.buffer();
        try {
            multipart.writeTo(target);
            assertThat(target.readableBytes()).isEqualTo((int) calculatedLength);
        } finally {
            target.release();
        }

        // Test writeTo(OutputStream)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        multipart.writeTo(baos);
        assertThat(baos.size()).isEqualTo((int) calculatedLength);
    }

    @Test
    void testByteBufRequestBodyFromStream() throws IOException {
        byte[] data = "Buffered stream data test".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteBufRequestBody body = IOUtil.createRequestBody(MediaType.OCTET, bais);

        assertThat(body.contentLength()).isEqualTo(data.length);
        assertThat(body.toBytes()).isEqualTo(data);

        ByteBuf buf1 = body.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        ByteBuf buf2 = body.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(buf1.readableBytes()).isEqualTo(data.length);
            assertThat(buf2.readableBytes()).isEqualTo(data.length);
        } finally {
            buf1.release();
            buf2.release();
        }

        ByteBuf target = Unpooled.buffer();
        try {
            body.writeTo(target);
            assertThat(target.readableBytes()).isEqualTo(data.length);
        } finally {
            target.release();
        }
    }

    @Test
    void testJsonRequestBody() throws IOException {
        DataObject obj = DataObject.empty().put("name", "Antigravity").put("version", 2);
        JsonRequestBody body = new JsonRequestBody(obj.toMap());

        assertThat(body.contentType()).isEqualTo(MediaType.JSON);
        assertThat(body.contentLength()).isGreaterThan(0);

        byte[] bytes = body.toBytes();
        DataObject parsed = DataObject.fromJson(Unpooled.wrappedBuffer(bytes));
        assertThat(parsed.getString("name")).isEqualTo("Antigravity");
        assertThat(parsed.getInt("version")).isEqualTo(2);

        ByteBuf target = Unpooled.buffer();
        try {
            body.writeTo(target);
            assertThat(target.readableBytes()).isEqualTo(bytes.length);
        } finally {
            target.release();
        }

        DataArray array = DataArray.empty().add("val1").add("val2");
        JsonRequestBody arrayBody = new JsonRequestBody(array.toList());
        assertThat(arrayBody.contentLength()).isGreaterThan(0);
        DataArray parsedArray = DataArray.fromJson(Unpooled.wrappedBuffer(arrayBody.toBytes()));
        assertThat(parsedArray.getString(0)).isEqualTo("val1");
        assertThat(parsedArray.getString(1)).isEqualTo("val2");
    }

    @Test
    void testDataSupplierBody() throws IOException {
        byte[] data = "Supplier stream data".getBytes(StandardCharsets.UTF_8);
        DataSupplierBody body = new DataSupplierBody(MediaType.OCTET, () -> new ByteArrayInputStream(data));

        assertThat(body.toBytes()).isEqualTo(data);

        ByteBuf buf = body.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(buf.readableBytes()).isEqualTo(data.length);
        } finally {
            buf.release();
        }

        ByteBuf target = Unpooled.buffer();
        try {
            body.writeTo(target);
            assertThat(target.readableBytes()).isEqualTo(data.length);
        } finally {
            target.release();
        }
    }

    @Test
    void testResponseByteBufHandling() {
        String json = "{\"id\":12345,\"username\":\"tester\"}";
        ByteBuf buf = Unpooled.copiedBuffer(json.getBytes(StandardCharsets.UTF_8));

        Response response = new Response(200, "OK", -1, buf, null, "https://discord.com", Set.of());

        assertThat(response.isOk()).isTrue();
        assertThat(response.getByteBuf()).isSameAs(buf);

        DataObject parsedObj = response.getObject();
        assertThat(parsedObj.getLong("id")).isEqualTo(12345L);
        assertThat(parsedObj.getString("username")).isEqualTo("tester");

        // Calling getObject() multiple times returns cached DataObject
        assertThat(response.getObject()).isSameAs(parsedObj);

        // Test getBody() returns an InputStream that can be read
        InputStream stream = response.getBody();
        assertThat(stream).isNotNull();

        // Close response releases byteBuf
        assertThat(buf.refCnt()).isEqualTo(1);
        response.close();
        assertThat(buf.refCnt()).isEqualTo(0);

        // Idempotent close
        response.close();
    }

    @Test
    void testMediaTypeEnum() {
        assertThat(MediaType.JSON.getValue()).isEqualTo("application/json; charset=utf-8");
        assertThat(MediaType.JSON.type()).isEqualTo("application");
        assertThat(MediaType.JSON.subtype()).isEqualTo("json");

        assertThat(MediaType.parse("image/png")).isEqualTo(MediaType.PNG);
        assertThat(MediaType.parse("IMAGE/PNG; charset=utf-8")).isEqualTo(MediaType.PNG);
        assertThat(MediaType.parse(null)).isNull();
        assertThat(MediaType.parse("")).isNull();
        assertThat(MediaType.parse("unknown/custom")).isEqualTo(MediaType.OCTET);

        assertThat(MediaType.fromExtension("png")).isEqualTo(MediaType.PNG);
        assertThat(MediaType.fromExtension("APNG")).isEqualTo(MediaType.PNG);
        assertThat(MediaType.fromExtension("gif")).isEqualTo(MediaType.GIF);
        assertThat(MediaType.fromExtension("jpg")).isEqualTo(MediaType.JPEG);
        assertThat(MediaType.fromExtension("webp")).isEqualTo(MediaType.WEBP);
        assertThat(MediaType.fromExtension("json")).isEqualTo(MediaType.JSON);
        assertThat(MediaType.fromExtension("txt")).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(MediaType.fromExtension("ogg")).isEqualTo(MediaType.AUDIO_OGG);
        assertThat(MediaType.fromExtension("unknown")).isEqualTo(MediaType.OCTET);
    }

    @Test
    void testFileUploadInMultipartBodyLifecycle() throws IOException {
        byte[] data = "some image bytes".getBytes(StandardCharsets.UTF_8);
        try (FileUpload upload = FileUpload.fromData(data, "rank.png")) {
            MultipartBody.Builder builder = new MultipartBody.Builder();
            upload.addPart(builder, 0);
            MultipartBody multipart = builder.build();

            ByteBuf buf = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
            try {
                assertThat(buf.readableBytes()).isGreaterThan(0);
            } finally {
                buf.release();
            }

            multipart.close();
        }
    }

    @Test
    void testFileUploadReusableAcrossMultipartBodiesAfterClose() throws IOException {
        byte[] data = "some image bytes".getBytes(StandardCharsets.UTF_8);
        try (FileUpload upload = FileUpload.fromData(data, "rank.png")) {
            MultipartBody.Builder builder1 = new MultipartBody.Builder();
            upload.addPart(builder1, 0);
            MultipartBody multipart1 = builder1.build();

            ByteBuf buf1 = multipart1.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
            buf1.release();

            // When request 1 finishes, multipart1 is closed
            multipart1.close();

            // Reusing the same FileUpload for a second request/channel or retry should
            // still work!
            MultipartBody.Builder builder2 = new MultipartBody.Builder();
            upload.addPart(builder2, 0);
            MultipartBody multipart2 = builder2.build();

            ByteBuf buf2 = multipart2.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
            try {
                assertThat(buf2.readableBytes()).isGreaterThan(0);
            } finally {
                buf2.release();
            }
        }
    }

    @Test
    void testEmptyBodyCannotBeClosed() throws IOException {
        Requester.EMPTY_BODY.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        if (Requester.EMPTY_BODY instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
        ByteBuf buf = Requester.EMPTY_BODY.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(buf.readableBytes()).isEqualTo(0);
        } finally {
            buf.release();
        }
    }

    @Test
    void testFileUploadReusableInContentEmbedsFilesMessage() throws IOException {
        byte[] fileBytes = "attachment content".getBytes(StandardCharsets.UTF_8);
        try (FileUpload upload = FileUpload.fromData(fileBytes, "cat.png")) {
            DataObject payloadJson = DataObject.empty()
                    .put("content", "hello world")
                    .put("embeds", DataArray.empty().add(DataObject.empty().put("title", "embed")));

            // Simulating first channel sending embeds + content + file
            MultipartBody body1 = AttachedFile.createMultipartBody(Collections.singleton(upload), payloadJson)
                    .build();
            ByteBuf buf1 = body1.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
            buf1.release();

            // Request 1 completes and cleanup is called
            body1.close();

            // Simulating second channel sending embeds + content + file with the same
            // FileUpload
            MultipartBody body2 = AttachedFile.createMultipartBody(Collections.singleton(upload), payloadJson)
                    .build();
            ByteBuf buf2 = body2.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
            try {
                assertThat(buf2.readableBytes()).isGreaterThan(0);
            } finally {
                buf2.release();
            }
        }
    }

    @Test
    void testIconFromCompositeByteBuf() {
        CompositeByteBuf composite = Unpooled.compositeBuffer();
        composite.addComponent(true, Unpooled.copiedBuffer("Hello ".getBytes(StandardCharsets.UTF_8)));
        composite.addComponent(true, Unpooled.copiedBuffer("Netty!".getBytes(StandardCharsets.UTF_8)));
        assertThat(composite.nioBufferCount()).isGreaterThan(1);

        Icon icon = Icon.from(composite, Icon.IconType.PNG);
        assertThat(icon).isNotNull();
        assertThat(icon.getEncoding()).startsWith("data:image/png;base64,");
        // composite is automatically released by Icon.from
        assertThat(composite.refCnt()).isEqualTo(0);
    }

    @Test
    void testMultipartBodyMultipleByteBufRetrieval() throws IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder("test_bnd");
        builder.addFormDataPart("field", "value");
        MultipartBody multipart = builder.build();

        // Should be able to retrieve ByteBuf multiple times without headerBuf being
        // exhausted or released
        ByteBuf buf1 = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(buf1.readableBytes()).isGreaterThan(0);
        } finally {
            buf1.release();
        }

        ByteBuf buf2 = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(buf2.readableBytes()).isGreaterThan(0);
        } finally {
            buf2.release();
        }

        multipart.close();
    }

    @Test
    void testResponseGetStringAfterClose() {
        ByteBuf buf = Unpooled.copiedBuffer("{\"message\":\"success\"}".getBytes(StandardCharsets.UTF_8));
        Response response = new Response(200, "OK", -1, buf, null, "https://discord.com", Set.of());

        // Close before calling getString
        response.close();
        assertThat(buf.refCnt()).isEqualTo(0);

        // Calling getString() after close must not crash with
        // IllegalReferenceCountException
        assertThat(response.getString()).isEqualTo("N/A");
    }

    @Test
    void testByteBufRequestBodyIdempotentClose() {
        ByteBuf buf = Unpooled.copiedBuffer("test data".getBytes(StandardCharsets.UTF_8));
        ByteBufRequestBody body = new ByteBufRequestBody(buf, MediaType.OCTET);

        assertThat(buf.refCnt()).isEqualTo(1);
        body.close();
        assertThat(buf.refCnt()).isEqualTo(0);

        // Subsequent close calls must be safe and idempotent
        body.close();
        body.close();
    }

    @Test
    void testFutureUtilCancellationReleasesBuffer() {
        CompletableFuture<ByteBuf> sourceFuture = new CompletableFuture<>();
        CompletableFuture<String> downstream = FutureUtil.thenApplyCancellable(sourceFuture, buf -> {
            throw new RuntimeException("Processing error");
        });

        ByteBuf buf = Unpooled.copiedBuffer("payload".getBytes(StandardCharsets.UTF_8));
        assertThat(buf.refCnt()).isEqualTo(1);

        // When applyFunction throws an exception, FutureUtil must release buf
        // immediately
        sourceFuture.complete(buf);
        assertThat(buf.refCnt()).isEqualTo(0);
        assertThat(downstream.isCompletedExceptionally()).isTrue();
    }

    @Test
    void testIOUtilReadIntoByteBufPreAllocation() throws IOException {
        byte[] data = "Hello World with Preallocated Buffer!".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteBuf buf = IOUtil.readIntoByteBuf(bais, UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(buf.readableBytes()).isEqualTo(data.length);
            assertThat(buf.toString(StandardCharsets.UTF_8)).isEqualTo("Hello World with Preallocated Buffer!");
        } finally {
            buf.release();
        }
    }

    @Test
    void testFileUploadReferenceCountingEarlyClose() throws IOException {
        ByteBuf rawBuf = Unpooled.copiedBuffer("early close test".getBytes(StandardCharsets.UTF_8));
        FileUpload upload = FileUpload.fromData(rawBuf, "test.txt");

        MultipartBody.Builder builder = new MultipartBody.Builder("bnd");
        upload.addPart(builder, 0);
        MultipartBody multipart = builder.build();

        // User closes FileUpload early (e.g. exit try-with-resources before async
        // request executes)
        upload.close();

        // MultipartBody holds its own retained reference, so getByteBuf must still
        // succeed!
        ByteBuf retrieved = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(retrieved.readableBytes()).isGreaterThan(0);
        } finally {
            retrieved.release();
        }

        // When request completes and cleans up MultipartBody, the underlying buffer
        // should be released
        multipart.close();
        assertThat(rawBuf.refCnt()).isEqualTo(0);
    }

    @Test
    void testFileUploadMultipleRequestsReferenceCountingLifecycle() throws IOException {
        ByteBuf rawBuf = Unpooled.copiedBuffer("shared file".getBytes(StandardCharsets.UTF_8));
        FileUpload upload = FileUpload.fromData(rawBuf, "shared.txt");

        MultipartBody.Builder b1 = new MultipartBody.Builder("bnd1");
        upload.addPart(b1, 0);
        MultipartBody mb1 = b1.build();

        MultipartBody.Builder b2 = new MultipartBody.Builder("bnd2");
        upload.addPart(b2, 0);
        MultipartBody mb2 = b2.build();

        // Close upload early
        upload.close();

        // mb1 can read
        ByteBuf buf1 = mb1.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        buf1.release();

        // Request 1 finishes and cleans up mb1
        mb1.close();

        // mb2 can still read because it holds its own reference!
        ByteBuf buf2 = mb2.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertThat(buf2.readableBytes()).isGreaterThan(0);
        } finally {
            buf2.release();
        }

        // Request 2 finishes and cleans up mb2
        mb2.close();

        // Now all references are released and buffer is fully deallocated
        assertThat(rawBuf.refCnt()).isEqualTo(0);
    }

    @Test
    void testFileUploadTryWithResources() throws IOException {
        ByteBuf rawBuf = Unpooled.buffer(64);
        rawBuf.writeBytes("try with resources".getBytes(StandardCharsets.UTF_8));
        assertThat(rawBuf.refCnt()).isEqualTo(1);

        try (FileUpload upload = FileUpload.fromData(rawBuf, "try.txt")) {
            upload.getRequestBody(MediaType.OCTET);
            assertThat(rawBuf.refCnt()).isEqualTo(1);
        }

        assertThat(rawBuf.refCnt()).isEqualTo(0);
    }

    @Test
    void testFileUploadGarbageCollectionCleanup() throws Exception {
        ByteBuf rawBuf = Unpooled.buffer(64);
        rawBuf.writeBytes("gc test".getBytes(StandardCharsets.UTF_8));
        assertThat(rawBuf.refCnt()).isEqualTo(1);

        allocateAndAbandonFileUpload(rawBuf);

        for (int i = 0; i < 50 && rawBuf.refCnt() > 0; i++) {
            System.gc();
            Thread.sleep(20);
        }

        assertThat(rawBuf.refCnt()).isEqualTo(0);
    }

    private void allocateAndAbandonFileUpload(ByteBuf buf) {
        FileUpload upload = FileUpload.fromData(buf, "abandoned.txt");
        upload.getRequestBody(MediaType.OCTET);
    }

    @Test
    void testMultipartBodyRetainAndReleaseLifecycle() throws IOException {
        MultipartBody multipart = new MultipartBody.Builder("test_bnd")
                .addFormDataPart("field", "value")
                .build();

        assertThat(multipart.refCnt()).isEqualTo(1);
        multipart.retain();
        assertThat(multipart.refCnt()).isEqualTo(2);

        multipart.release();
        assertThat(multipart.refCnt()).isEqualTo(1);

        // Can still read
        ByteBuf buf = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        buf.release();

        // Final release
        multipart.release();
        assertThat(multipart.refCnt()).isEqualTo(0);

        // Subsequent read throws IllegalStateException
        assertThrows(IllegalStateException.class, () -> multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT));
    }

    @Test
    void testRealMessageWithContentEmbedAndFile() throws IOException {
        byte[] fileData = "MY IMPORTANT FILE DATA 12345".getBytes(StandardCharsets.UTF_8);
        FileUpload upload = FileUpload.fromData(fileData, "cat.png");

        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent("Hello world");
        builder.addFiles(upload);

        RequestBody body;
        try (MessageCreateData data = builder.build()) {
            DataObject json = data.toData();
            body = AttachedFile.createMultipartBody(data.getAllDistinctFiles(), new JsonRequestBody(json.toMap()))
                    .build();
        }

        long calculatedLength = body.contentLength();
        assertThat(calculatedLength).isGreaterThan(0);

        ByteBuf buf = body.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        assertThat(buf.readableBytes()).isEqualTo((int) calculatedLength);

        String fullString = buf.toString(StandardCharsets.UTF_8);
        assertThat(fullString).contains("MY IMPORTANT FILE DATA 12345");
        // Ensure payload_json is serialized before the file attachment
        int payloadJsonIndex = fullString.indexOf("name=\"payload_json\"");
        int fileIndex = fullString.indexOf("name=\"files[0]\"");
        assertThat(payloadJsonIndex).isLessThan(fileIndex);

        buf.release();
        if (body instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void testMessageWithInputStreamFileUpload() throws IOException {
        byte[] fileData = "STREAM FILE DATA 12345".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream stream = new ByteArrayInputStream(fileData);
        FileUpload upload = FileUpload.fromData(stream, "stream.png");

        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.setContent("Hello stream");
        builder.addFiles(upload);

        RequestBody body;
        try (MessageCreateData data = builder.build()) {
            DataObject json = data.toData();
            body = AttachedFile.createMultipartBody(data.getAllDistinctFiles(), new JsonRequestBody(json.toMap()))
                    .build();
        }

        long calculatedLength = body.contentLength();
        assertThat(calculatedLength).isGreaterThan(0);

        ByteBuf buf = body.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        assertThat(buf.readableBytes()).isEqualTo((int) calculatedLength);

        String fullString = buf.toString(StandardCharsets.UTF_8);
        assertThat(fullString).contains("STREAM FILE DATA 12345");
        int payloadJsonIndex = fullString.indexOf("name=\"payload_json\"");
        int fileIndex = fullString.indexOf("name=\"files[0]\"");
        assertThat(payloadJsonIndex).isLessThan(fileIndex);

        buf.release();
        if (body instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void testUserScenarioWeeklyResetTask() throws IOException {
        String csv = "Index;Name\n1;Admin";
        FileUpload upload = FileUpload.fromData(csv.getBytes(StandardCharsets.UTF_8), "administracja.csv");

        MessageCreateBuilder mcb =
                new MessageCreateBuilder().setContent("Szablon").addFiles(upload);

        MessageCreateData mcd = mcb.build();

        // 1st channel (supervisor)
        MessageCreateBuilder builder1 = new MessageCreateBuilder().applyData(mcd);
        MessageCreateData d1 = builder1.build();
        RequestBody body1 = AttachedFile.createMultipartBody(
                        d1.getAllDistinctFiles(),
                        new JsonRequestBody(d1.toData().toMap()))
                .build();

        ByteBuf buf1 = body1.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        String s1 = buf1.toString(StandardCharsets.UTF_8);
        buf1.release();

        // Request 1 completes and cleanup is called
        ((MultipartBody) body1).close();

        // 2nd channel (text channel tc)
        MessageCreateBuilder builder2 = new MessageCreateBuilder().applyData(mcd);
        MessageCreateData d2 = builder2.build();
        RequestBody body2 = AttachedFile.createMultipartBody(
                        d2.getAllDistinctFiles(),
                        new JsonRequestBody(d2.toData().toMap()))
                .build();

        ByteBuf buf2 = body2.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        String s2 = buf2.toString(StandardCharsets.UTF_8);
        buf2.release();

        ((MultipartBody) body2).close();

        assertThat(s1).contains("1;Admin");
        assertThat(s2).contains("1;Admin");
    }

    @Test
    void testSingleUseFileUploadAutoClosesOnMultipartClose() throws IOException {
        String data = "Hello Single Use";
        FileUpload upload = FileUpload.fromData(data.getBytes(StandardCharsets.UTF_8), "single.txt")
                .asSingleUse();

        assertThat(upload.isSingleUse()).isTrue();
        assertThat(upload.isCloseOnUse()).isTrue();
        assertThat(upload.isClosed()).isFalse();

        MultipartBody multipart =
                AttachedFile.createMultipartBody(Collections.singleton(upload)).build();

        // While multipart is active, upload should not yet be closed
        assertThat(upload.isClosed()).isFalse();

        // Read data to ensure byte buffer was accessible
        ByteBuf buf = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("Hello Single Use");
        buf.release();

        // Now request finishes / multipart is closed
        multipart.close();

        // Upload should now be automatically closed
        assertThat(upload.isClosed()).isTrue();

        // Further attempt to get request body must throw IllegalStateException
        assertThatThrownBy(() -> upload.getRequestBody(MediaType.TEXT_PLAIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void testDefaultFileUploadDoesNotCloseOnUse() throws IOException {
        String data = "Hello Reusable";
        FileUpload upload = FileUpload.fromData(data.getBytes(StandardCharsets.UTF_8), "reusable.txt");

        assertThat(upload.isSingleUse()).isFalse();
        assertThat(upload.isCloseOnUse()).isFalse();
        assertThat(upload.isClosed()).isFalse();

        // First use
        MultipartBody multipart1 =
                AttachedFile.createMultipartBody(Collections.singleton(upload)).build();
        ByteBuf buf1 = multipart1.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        assertThat(buf1.toString(StandardCharsets.UTF_8)).contains("Hello Reusable");
        buf1.release();
        multipart1.close();

        // Upload must NOT be closed and must be reusable
        assertThat(upload.isClosed()).isFalse();

        // Second use
        MultipartBody multipart2 =
                AttachedFile.createMultipartBody(Collections.singleton(upload)).build();
        ByteBuf buf2 = multipart2.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        assertThat(buf2.toString(StandardCharsets.UTF_8)).contains("Hello Reusable");
        buf2.release();
        multipart2.close();

        assertThat(upload.isClosed()).isFalse();

        // Explicit close via AutoCloseable
        upload.close();
        assertThat(upload.isClosed()).isTrue();
    }

    @Test
    void testCloseOnUseFluentAndToggle() {
        FileUpload upload = FileUpload.fromData("sample".getBytes(StandardCharsets.UTF_8), "sample.txt");
        assertThat(upload.isSingleUse()).isFalse();
        assertThat(upload.isCloseOnUse()).isFalse();

        upload.closeOnUse();
        assertThat(upload.isSingleUse()).isTrue();
        assertThat(upload.isCloseOnUse()).isTrue();

        upload.setCloseOnUse(false);
        assertThat(upload.isSingleUse()).isFalse();
        assertThat(upload.isCloseOnUse()).isFalse();

        upload.asSingleUse(true);
        assertThat(upload.isSingleUse()).isTrue();
        assertThat(upload.isCloseOnUse()).isTrue();
    }

    @Test
    void testTryWithResourcesEarlyCloseKeepsInFlightMultipartValid() throws IOException {
        String content = "In-flight safety check";
        MultipartBody multipart;
        try (FileUpload upload = FileUpload.fromData(content.getBytes(StandardCharsets.UTF_8), "safe.txt")) {
            multipart = AttachedFile.createMultipartBody(Collections.singleton(upload))
                    .build();
            // try block exits here, upload.close() is called
        }

        // Multipart should still be able to read byte buffer without IllegalReferenceCountException
        ByteBuf buf = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("In-flight safety check");
        buf.release();
        multipart.close();
    }

    @Test
    void testIOUtilReadIntoByteBufWithPath() throws IOException {
        Path tempFile = Files.createTempFile("jda-test-path-nio", ".txt");
        try {
            byte[] content =
                    "IOUtil readIntoByteBuf direct NIO transfer content 12345".getBytes(StandardCharsets.UTF_8);
            Files.write(tempFile, content);

            ByteBuf retrieved = IOUtil.readIntoByteBuf(tempFile, UnpooledByteBufAllocator.DEFAULT);
            try {
                assertThat(retrieved.readableBytes()).isEqualTo(content.length);
                byte[] readBytes = new byte[retrieved.readableBytes()];
                retrieved.getBytes(retrieved.readerIndex(), readBytes);
                assertThat(readBytes).isEqualTo(content);
            } finally {
                retrieved.release();
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testFileUploadFromDataPathAndFileLifecycle() throws IOException {
        Path tempFile = Files.createTempFile("jda-test-fileupload", ".txt");
        try {
            byte[] fileBytes = "Sample content for FileUpload Path & File test".getBytes(StandardCharsets.UTF_8);
            Files.write(tempFile, fileBytes);

            // Test FileUpload.fromData(Path)
            try (FileUpload uploadPath = FileUpload.fromData(tempFile, "from-path.txt")) {
                assertThat(uploadPath.getName()).isEqualTo("from-path.txt");
                assertThat(uploadPath.isClosed()).isFalse();

                try (InputStream stream = uploadPath.getData()) {
                    assertThat(stream.readAllBytes()).isEqualTo(fileBytes);
                }

                RequestBody body = uploadPath.getRequestBody(MediaType.TEXT_PLAIN);
                assertThat(body).isInstanceOf(ByteBufRequestBody.class);
                assertThat(body.contentLength()).isEqualTo(fileBytes.length);

                MultipartBody multipart = new MultipartBody.Builder("bnd_path")
                        .addFormDataPart("files[0]", uploadPath.getName(), body)
                        .build();
                assertThat(multipart.contentLength()).isGreaterThan(fileBytes.length);

                ByteBuf comp = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
                try {
                    assertThat(comp.toString(StandardCharsets.UTF_8))
                            .contains("Sample content for FileUpload Path & File test");
                } finally {
                    comp.release();
                }
                multipart.close();
            }

            // Test FileUpload.fromData(File)
            File f = tempFile.toFile();
            try (FileUpload uploadFile = FileUpload.fromData(f)) {
                assertThat(uploadFile.getName())
                        .isEqualTo(tempFile.getFileName().toString());
                assertThat(uploadFile.isClosed()).isFalse();

                RequestBody body = uploadFile.getRequestBody(MediaType.OCTET);
                assertThat(body).isInstanceOf(ByteBufRequestBody.class);
                assertThat(body.contentLength()).isEqualTo(fileBytes.length);

                MultipartBody multipart = new MultipartBody.Builder("bnd_file")
                        .addFormDataPart("files[0]", uploadFile.getName(), body)
                        .build();

                ByteBuf comp = multipart.getByteBuf(UnpooledByteBufAllocator.DEFAULT);
                try {
                    assertThat(comp.toString(StandardCharsets.UTF_8))
                            .contains("Sample content for FileUpload Path & File test");
                } finally {
                    comp.release();
                }
                multipart.close();
            }

            // Ensure no lingering file locks on Windows
            assertThat(Files.deleteIfExists(tempFile)).isTrue();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testIOUtilReadIntoByteBufWithFileInputStream() throws IOException {
        Path tempFile = Files.createTempFile("jda-test-fis", ".bin");
        try {
            byte[] data = "FileInputStream NIO Channel read test data".getBytes(StandardCharsets.UTF_8);
            Files.write(tempFile, data);

            try (FileInputStream fis = new FileInputStream(tempFile.toFile())) {
                ByteBuf buf = IOUtil.readIntoByteBuf(fis, UnpooledByteBufAllocator.DEFAULT);
                try {
                    assertThat(buf.readableBytes()).isEqualTo(data.length);
                    byte[] readBytes = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), readBytes);
                    assertThat(readBytes).isEqualTo(data);
                } finally {
                    buf.release();
                }
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
