package com.seft.learn.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class S3StreamingUploader {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucketName = "";

    private static final int PART_SIZE = 5 * 1024 * 1024; // 5MB minimum
    private static final int MAX_CONCURRENT_UPLOADS = 4;

    public StreamingUpload startUpload(String key) {
        return startUpload(key, false);
    }

    public StreamingUpload startUpload(String key, boolean gzipEnabled) {
        String contentType = "text/csv";
        String contentEncoding = gzipEnabled ? "gzip" : null;

        CreateMultipartUploadRequest.Builder requestBuilder = CreateMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType);

        if (contentEncoding != null) {
            requestBuilder.contentEncoding(contentEncoding);
        }

        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(requestBuilder.build());
        return new StreamingUpload(s3Client, bucketName, key, response.uploadId(), gzipEnabled);
    }

    @Slf4j
    public static class StreamingUpload {
        private final S3Client s3Client;
        private final String bucket;
        private final String key;
        private final String uploadId;
        private final boolean gzipEnabled;
        private final ExecutorService uploadExecutor;

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Writer writer;
        private final @Nullable GZIPOutputStream gzipStream;
        private final List<CompletableFuture<CompletedPart>> pendingUploads = new ArrayList<>();
        private final List<CompletedPart> completedParts = new CopyOnWriteArrayList<>();

        private int partNumber = 1;
        private long totalBytes = 0;
        private long uncompressedBytes = 0;

        public StreamingUpload(S3Client s3Client, String bucket, String key, String uploadId, boolean gzipEnabled) {
            this.s3Client = s3Client;
            this.bucket = bucket;
            this.key = key;
            this.uploadId = uploadId;
            this.gzipEnabled = gzipEnabled;
            this.uploadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS);

            try {
                if (gzipEnabled) {
                    this.gzipStream = new GZIPOutputStream(buffer);
                    this.writer = new OutputStreamWriter(gzipStream, StandardCharsets.UTF_8);
                } else {
                    this.gzipStream = null;
                    this.writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize gzip stream", e);
            }
        }

        public void write(String line) throws IOException {
            byte[] lineBytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            uncompressedBytes += lineBytes.length;

            writer.write(line);
            writer.write("\n");
            writer.flush();

            if (gzipEnabled && gzipStream != null) {
                // For gzip, we need to check compressed size
                // Flush gzip periodically to get accurate buffer size
            }

            if (buffer.size() >= PART_SIZE) {
                flushPartAsync();
            }
        }

        private void flushPartAsync() throws IOException {
            writer.flush();
            if (gzipEnabled && gzipStream != null) {
                // Finish current gzip block but don't close the stream
                gzipStream.flush();
            }

            byte[] data = buffer.toByteArray();
            if (data.length == 0) return;

            int currentPartNumber = partNumber++;
            totalBytes += data.length;
            buffer.reset();

            // For gzip, we need to create a new gzip stream for the next part
            // This is handled by reinitializing in the buffer reset

            CompletableFuture<CompletedPart> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return uploadPart(data, currentPartNumber);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, uploadExecutor);

            pendingUploads.add(future);

            if (pendingUploads.size() >= MAX_CONCURRENT_UPLOADS * 2) {
                waitForSomeUploads();
            }
        }

        private CompletedPart uploadPart(byte[] data, int partNum) {
            long startTime = System.currentTimeMillis();

            UploadPartResponse response = s3Client.uploadPart(
                    UploadPartRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .uploadId(uploadId)
                            .partNumber(partNum)
                            .build(),
                    RequestBody.fromBytes(data)
            );

            long duration = System.currentTimeMillis() - startTime;
            double speedMBps = duration > 0 ? (data.length / (1024.0 * 1024)) / (duration / 1000.0) : 0;
            log.info("Uploaded part {}: {} bytes in {}ms ({:.2f} MB/s)",
                    partNum, data.length, duration, speedMBps);

            CompletedPart part = CompletedPart.builder()
                    .partNumber(partNum)
                    .eTag(response.eTag())
                    .build();

            completedParts.add(part);
            return part;
        }

        private void waitForSomeUploads() {
            int waitCount = pendingUploads.size() / 2;
            List<CompletableFuture<CompletedPart>> toWait = new ArrayList<>(pendingUploads.subList(0, waitCount));

            try {
                CompletableFuture.allOf(toWait.toArray(new CompletableFuture[0])).join();
                pendingUploads.removeAll(toWait);
            } catch (CompletionException e) {
                throw new RuntimeException("Part upload failed", e.getCause());
            }
        }

        public long complete() throws IOException {
            writer.flush();
            if (gzipEnabled && gzipStream != null) {
                gzipStream.finish();
            }

            byte[] remainingData = buffer.toByteArray();
            if (remainingData.length > 0) {
                int currentPartNumber = partNumber++;
                totalBytes += remainingData.length;

                CompletableFuture<CompletedPart> future = CompletableFuture.supplyAsync(
                        () -> uploadPart(remainingData, currentPartNumber), uploadExecutor);
                pendingUploads.add(future);
            }

            try {
                CompletableFuture.allOf(pendingUploads.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException e) {
                throw new IOException("Part upload failed", e.getCause());
            } finally {
                uploadExecutor.shutdown();
            }

            List<CompletedPart> sortedParts = completedParts.stream()
                    .sorted(Comparator.comparingInt(CompletedPart::partNumber))
                    .toList();

            s3Client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .uploadId(uploadId)
                            .multipartUpload(CompletedMultipartUpload.builder()
                                    .parts(sortedParts)
                                    .build())
                            .build()
            );

            if (gzipEnabled) {
                double compressionRatio = uncompressedBytes > 0 ? 
                        (1 - (double) totalBytes / uncompressedBytes) * 100 : 0;
                log.info("Completed multipart upload: {} parts, {} bytes (compressed from {} bytes, {:.1f}% reduction)",
                        sortedParts.size(), totalBytes, uncompressedBytes, compressionRatio);
            } else {
                log.info("Completed multipart upload: {} parts, {} bytes total (parallel: {} threads)",
                        sortedParts.size(), totalBytes, MAX_CONCURRENT_UPLOADS);
            }

            return totalBytes;
        }

        public long getUncompressedBytes() {
            return uncompressedBytes;
        }

        public void abort() {
            uploadExecutor.shutdownNow();
            try {
                s3Client.abortMultipartUpload(
                        AbortMultipartUploadRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .build()
                );
            } catch (Exception e) {
                log.warn("Failed to abort multipart upload", e);
            }
        }
    }
}
