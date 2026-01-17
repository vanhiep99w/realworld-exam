package com.seft.learn.example.dto;

import java.time.Instant;

public record ErrorResponse(
		int status,
		String message,
		String requestId,
		Instant timestamp
) {
	public ErrorResponse(int status, String message, String requestId) {
		this(status, message, requestId, Instant.now());
	}
}
