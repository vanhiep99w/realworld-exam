package com.seft.learn.example.controller;

import com.seft.learn.example.dto.PresignedPostResponse;
import com.seft.learn.example.dto.PresignedUrlResponse;
import com.seft.learn.example.dto.S3FileDto;
import com.seft.learn.example.service.S3PresignedUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/s3")
@RequiredArgsConstructor
@CrossOrigin("*")
public class S3Controller {

	private final S3PresignedUrlService s3Service;

	@GetMapping("/presigned-url/put")
	public ResponseEntity<?> getPresignedPutUrl(
			@RequestParam String key,
			@RequestParam(defaultValue = "application/octet-stream") String contentType,
			@RequestParam long fileSize) {
		try {
			String url = s3Service.generatePresignedPutUrl(key, contentType, fileSize);
			return ResponseEntity.ok(new PresignedUrlResponse(url, key));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/presigned-url/post")
	public ResponseEntity<?> getPresignedPostUrl(
			@RequestParam String key,
			@RequestParam String contentType) {
		try {
			var result = s3Service.generatePresignedPostUrl(key, contentType);
			return ResponseEntity.ok(new PresignedPostResponse(result.url(), result.fields(), result.key()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/presigned-url/get")
	public ResponseEntity<?> getPresignedGetUrl(@RequestParam String key) {
		try {
			String url = s3Service.generatePresignedGetUrl(key);
			return ResponseEntity.ok(new PresignedUrlResponse(url, key));
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/upload-constraints")
	public Map<String, Object> getUploadConstraints() {
		return Map.of(
				"maxFileSize", S3PresignedUrlService.getMaxFileSize(),
				"allowedContentTypes", S3PresignedUrlService.getAllowedContentTypes()
		);
	}

	@GetMapping("/files")
	public List<S3FileDto> listFiles() {
		return s3Service.listFiles().stream()
				.map(obj -> new S3FileDto(
						obj.key(),
						obj.size(),
						obj.lastModified().toString()))
				.toList();
	}
}
