package com.seft.learn.example.exception;

import com.seft.learn.example.dto.ErrorResponse;
import com.seft.learn.example.filter.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
		String message = ex.getMessage() != null ? ex.getMessage() : "Bad request";
		log.warn("Bad request: {}", message);
		return buildErrorResponse(HttpStatus.BAD_REQUEST, message);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
		log.error("Unexpected error", ex);
		return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
	}

	private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, String message) {
		String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
		ErrorResponse error = new ErrorResponse(status.value(), message, requestId);
		return ResponseEntity.status(status).body(error);
	}
}
