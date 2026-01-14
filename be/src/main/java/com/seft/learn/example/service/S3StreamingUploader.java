package com.seft.learn.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class S3StreamingUploader {

	private final S3Client s3Client;

	@Value("${aws.s3.bucket}")
	private String bucketName;

	private static final int PART_SIZE = 5 * 1024 * 1024; // 5MB minimum for multipart

	public StreamingUpload startUpload(String key) {
		CreateMultipartUploadResponse response = s3Client.createMultipartUpload(
				CreateMultipartUploadRequest.builder()
						.bucket(bucketName)
						.key(key)
						.contentType("text/csv")
						.build()
		);
		return new StreamingUpload(s3Client, bucketName, key, response.uploadId());
	}

	@RequiredArgsConstructor
	public static class StreamingUpload {
		private final S3Client s3Client;
		private final String bucket;
		private final String key;
		private final String uploadId;

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final Writer writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8);
		private final List<CompletedPart> completedParts = new ArrayList<>();
		private int partNumber = 1;

		public void write(String line) throws IOException {
			writer.write(line);
			writer.write("\n");

			// Flush khi buffer đủ 5MB
			if (buffer.size() >= PART_SIZE) {
				flushPart();
			}
		}

		private void flushPart() throws IOException {
			writer.flush();
			byte[] data = buffer.toByteArray();
			if (data.length == 0) return;

			UploadPartResponse response = s3Client.uploadPart(
					UploadPartRequest.builder()
							.bucket(bucket)
							.key(key)
							.uploadId(uploadId)
							.partNumber(partNumber)
							.build(),
					RequestBody.fromBytes(data)
			);

			completedParts.add(CompletedPart.builder()
					.partNumber(partNumber)
					.eTag(response.eTag())
					.build());

			log.info("Uploaded part {}: {} bytes", partNumber, data.length);
			partNumber++;
			buffer.reset();
		}

		public void complete() throws IOException {
			// Upload phần còn lại
			flushPart();

			s3Client.completeMultipartUpload(
					CompleteMultipartUploadRequest.builder()
							.bucket(bucket)
							.key(key)
							.uploadId(uploadId)
							.multipartUpload(CompletedMultipartUpload.builder()
									.parts(completedParts)
									.build())
							.build()
			);
			log.info("Completed multipart upload: {} parts", completedParts.size());
		}

		public void abort() {
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
