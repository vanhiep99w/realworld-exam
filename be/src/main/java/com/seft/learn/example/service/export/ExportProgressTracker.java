package com.seft.learn.example.service.export;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ExportProgressTracker {

    private final JdbcTemplate jdbcTemplate;

    private static final int UPDATE_INTERVAL = 10000;

    public boolean shouldUpdate(long processedRows) {
        return processedRows % UPDATE_INTERVAL == 0;
    }

    public void updateProgress(UUID jobId, long processedRows) {
        jdbcTemplate.update(
            "UPDATE export_jobs SET processed_records = ? WHERE id = ?",
            processedRows, jobId
        );
    }

    public void updateMetrics(UUID jobId, long fileSizeBytes, double rowsPerSecond, long durationMs) {
        jdbcTemplate.update(
            "UPDATE export_jobs SET file_size_bytes = ?, rows_per_second = ?, duration_ms = ? WHERE id = ?",
            fileSizeBytes, rowsPerSecond, durationMs, jobId
        );
    }
}
