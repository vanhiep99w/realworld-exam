package com.seft.learn.example.controller;

import com.seft.learn.example.entity.ExportJob;
import com.seft.learn.example.service.ExportService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/exports")
@RequiredArgsConstructor
@CrossOrigin("*")
public class ExportController {

    private final ExportService exportService;

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> startExport() {
        UUID jobId = exportService.startExport();
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "message", "Export started",
                "statusUrl", "/exports/" + jobId
        ));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ExportJobResponse> getStatus(@PathVariable UUID jobId) {
        ExportJob job = exportService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ExportJobResponse.from(job));
    }

    @GetMapping("/{jobId}/download-url")
    public ResponseEntity<Map<String, String>> getDownloadUrl(@PathVariable UUID jobId) {
        try {
            String url = exportService.getDownloadUrl(jobId);
            return ResponseEntity.ok(Map.of("downloadUrl", url));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record ExportJobResponse(
            UUID id,
            String status,
            @Nullable Long totalRecords,
            @Nullable Long processedRecords,
            @Nullable Integer progressPercent,
            @Nullable String s3Key,
            @Nullable String errorMessage,
            String createdAt,
            @Nullable String startedAt,
            @Nullable String finishedAt,
            // Metrics
            @Nullable Long fileSizeBytes,
            @Nullable String fileSizeFormatted,
            @Nullable Long uncompressedSizeBytes,
            @Nullable String uncompressedSizeFormatted,
            @Nullable Double compressionPercent,
            @Nullable Double rowsPerSecond,
            @Nullable Long durationMs,
            @Nullable String durationFormatted
    ) {
        public static ExportJobResponse from(ExportJob job) {
            Integer percent = calculatePercent(job);
            Double compression = calculateCompression(job);
            
            return new ExportJobResponse(
                    job.getId(),
                    job.getStatus().name(),
                    job.getTotalRecords(),
                    job.getProcessedRecords(),
                    percent,
                    job.getS3Key(),
                    job.getErrorMessage(),
                    formatInstant(job.getCreatedAt()),
                    formatNullableInstant(job.getStartedAt()),
                    formatNullableInstant(job.getFinishedAt()),
                    job.getFileSizeBytes(),
                    formatFileSize(job.getFileSizeBytes()),
                    job.getUncompressedSizeBytes(),
                    formatFileSize(job.getUncompressedSizeBytes()),
                    compression,
                    job.getRowsPerSecond(),
                    job.getDurationMs(),
                    formatDuration(job.getDurationMs())
            );
        }

        private static @Nullable Integer calculatePercent(ExportJob job) {
            if (job.getTotalRecords() != null && job.getTotalRecords() > 0 && job.getProcessedRecords() != null) {
                return (int) (job.getProcessedRecords() * 100 / job.getTotalRecords());
            }
            return null;
        }

        private static @Nullable Double calculateCompression(ExportJob job) {
            if (job.getUncompressedSizeBytes() != null && job.getUncompressedSizeBytes() > 0
                    && job.getFileSizeBytes() != null) {
                return (1 - (double) job.getFileSizeBytes() / job.getUncompressedSizeBytes()) * 100;
            }
            return null;
        }

        private static String formatInstant(Instant instant) {
            return instant.toString();
        }

        private static @Nullable String formatNullableInstant(@Nullable Instant instant) {
            return instant != null ? instant.toString() : null;
        }

        private static @Nullable String formatFileSize(@Nullable Long bytes) {
            if (bytes == null) return null;
            if (bytes >= 1024 * 1024) {
                return String.format("%.2f MB", bytes / (1024.0 * 1024));
            }
            if (bytes >= 1024) {
                return String.format("%.2f KB", bytes / 1024.0);
            }
            return bytes + " B";
        }

        private static @Nullable String formatDuration(@Nullable Long ms) {
            if (ms == null) return null;
            long seconds = ms / 1000;
            long minutes = seconds / 60;
            if (minutes > 0) {
                return String.format("%dm %ds", minutes, seconds % 60);
            }
            return String.format("%ds", seconds);
        }
    }
}
