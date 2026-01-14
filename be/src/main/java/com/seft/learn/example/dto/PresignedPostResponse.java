package com.seft.learn.example.dto;

import java.util.Map;

public record PresignedPostResponse(String url, Map<String, String> fields, String key) {
}
