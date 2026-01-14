package com.seft.learn.example.controller;

import com.seft.learn.example.entity.ExportJob;
import com.seft.learn.example.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/exports")
@RequiredArgsConstructor
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
            Long totalRecords,
            Long processedRecords,
            Integer progressPercent,
            String s3Key,
            String errorMessage,
            String createdAt,
            String startedAt,
            String finishedAt
    ) {
        public static ExportJobResponse from(ExportJob job) {
            Integer percent = null;
            if (job.getTotalRecords() != null && job.getTotalRecords() > 0 && job.getProcessedRecords() != null) {
                percent = (int) (job.getProcessedRecords() * 100 / job.getTotalRecords());
            }
            return new ExportJobResponse(
                    job.getId(),
                    job.getStatus().name(),
                    job.getTotalRecords(),
                    job.getProcessedRecords(),
                    percent,
                    job.getS3Key(),
                    job.getErrorMessage(),
                    job.getCreatedAt() != null ? job.getCreatedAt().toString() : null,
                    job.getStartedAt() != null ? job.getStartedAt().toString() : null,
                    job.getFinishedAt() != null ? job.getFinishedAt().toString() : null
            );
        }
    }
}
