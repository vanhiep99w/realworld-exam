package com.seft.learn.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3PresignedUrlService {

	private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
			"image/jpeg", "image/png", "image/gif", "image/webp",
			"application/pdf",
			"application/zip", "application/x-zip-compressed"
	);

	private final S3Client s3Client;
	private final S3Presigner s3Presigner;

	@Value("${aws.s3.bucket}")
	private String bucketName = "";

	@Value("${aws.s3.endpoint}")
	private String endpoint = "";

	@Value("${aws.s3.region}")
	private String region = "";

	@Value("${aws.s3.access-key}")
	private String accessKey = "";

	@Value("${aws.s3.secret-key}")
	private String secretKey = "";

	public String generatePresignedPutUrl(String key, String contentType, long fileSize) {
		validateUpload(contentType, fileSize);

		PutObjectRequest putRequest = PutObjectRequest.builder()
				.bucket(bucketName)
				.key(key)
				.contentType(contentType)
				.contentLength(fileSize)
				.build();

		PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
				.signatureDuration(Duration.ofMinutes(15))
				.putObjectRequest(putRequest)
				.build();

		PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
		return presignedRequest.url().toString();
	}

	public PresignedPostResult generatePresignedPostUrl(String key, String contentType) {
		if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
			throw new IllegalArgumentException("Content type not allowed: " + contentType);
		}

		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		String dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
		String amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
		String expiration = now.plusMinutes(15)
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
		String credential = accessKey + "/" + dateStamp + "/" + region + "/s3/aws4_request";

		String policy = buildPolicy(expiration, bucketName, key, contentType, credential, amzDate);
		String base64Policy = Base64.getEncoder().encodeToString(policy.getBytes(StandardCharsets.UTF_8));
		String signature = calculateSignature(base64Policy, dateStamp);

		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("key", key);
		fields.put("Content-Type", contentType);
		fields.put("X-Amz-Credential", credential);
		fields.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
		fields.put("X-Amz-Date", amzDate);
		fields.put("Policy", base64Policy);
		fields.put("X-Amz-Signature", signature);

		String url = endpoint + "/" + bucketName;
		return new PresignedPostResult(url, fields, key);
	}

	private String buildPolicy(String expiration, String bucket, String key, 
							   String contentType, String credential, String amzDate) {
		return """
			{
				"expiration": "%s",
				"conditions": [
					{"bucket": "%s"},
					["starts-with", "$key", "%s"],
					{"Content-Type": "%s"},
					["content-length-range", 1, %d],
					{"x-amz-credential": "%s"},
					{"x-amz-algorithm": "AWS4-HMAC-SHA256"},
					{"x-amz-date": "%s"}
				]
			}
			""".formatted(expiration, bucket, key, contentType, MAX_FILE_SIZE, credential, amzDate);
	}

	private String calculateSignature(String base64Policy, String dateStamp) {
		try {
			byte[] kDate = hmacSHA256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
			byte[] kRegion = hmacSHA256(kDate, region);
			byte[] kService = hmacSHA256(kRegion, "s3");
			byte[] kSigning = hmacSHA256(kService, "aws4_request");
			byte[] signature = hmacSHA256(kSigning, base64Policy);
			return bytesToHex(signature);
		} catch (Exception e) {
			throw new RuntimeException("Failed to calculate signature", e);
		}
	}

	private byte[] hmacSHA256(byte[] key, String data) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private void validateUpload(String contentType, long fileSize) {
		if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
			throw new IllegalArgumentException("Content type not allowed: " + contentType + 
					". Allowed: " + ALLOWED_CONTENT_TYPES);
		}
		if (fileSize > MAX_FILE_SIZE) {
			throw new IllegalArgumentException("File size exceeds limit: " + fileSize + 
					" bytes. Max: " + MAX_FILE_SIZE + " bytes (10MB)");
		}
		if (fileSize <= 0) {
			throw new IllegalArgumentException("File size must be positive");
		}
	}

	public void createBucketIfNotExists() {
		try {
			s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
			log.info("Bucket {} already exists", bucketName);
		} catch (NoSuchBucketException e) {
			s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
			log.info("Created bucket {}", bucketName);
		}
		configureBucketCors();
	}

	private void configureBucketCors() {
		CORSRule corsRule = CORSRule.builder()
				.allowedOrigins("*")
				.allowedMethods("GET", "PUT", "POST", "DELETE", "HEAD")
				.allowedHeaders("*")
				.exposeHeaders("ETag")
				.maxAgeSeconds(3000)
				.build();

		s3Client.putBucketCors(PutBucketCorsRequest.builder()
				.bucket(bucketName)
				.corsConfiguration(CORSConfiguration.builder()
						.corsRules(corsRule)
						.build())
				.build());
		log.info("Configured CORS for bucket {}", bucketName);
	}

	public List<S3Object> listFiles() {
		ListObjectsV2Response response = s3Client.listObjectsV2(
				ListObjectsV2Request.builder().bucket(bucketName).build());
		return response.contents();
	}

	public String generatePresignedGetUrl(String key) {
		GetObjectRequest getRequest = GetObjectRequest.builder()
				.bucket(bucketName)
				.key(key)
				.build();

		GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
				.signatureDuration(Duration.ofMinutes(15))
				.getObjectRequest(getRequest)
				.build();

		PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
		return presignedRequest.url().toString();
	}

	public static long getMaxFileSize() {
		return MAX_FILE_SIZE;
	}

	public static Set<String> getAllowedContentTypes() {
		return ALLOWED_CONTENT_TYPES;
	}

	public record PresignedPostResult(String url, Map<String, String> fields, String key) {}
}
