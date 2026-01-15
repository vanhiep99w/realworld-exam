package com.seft.learn.example.service.export;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class ExportMetrics {
    private final UUID jobId;
    private final Instant startTime;
    private long processedRows;
    private long bytesWritten;
    private int partsUploaded;

    public void incrementRows() {
        processedRows++;
    }

    public void addBytes(long bytes) {
        bytesWritten += bytes;
    }

    public void incrementParts() {
        partsUploaded++;
    }

    public double getRowsPerSecond() {
        long elapsed = Instant.now().toEpochMilli() - startTime.toEpochMilli();
        return elapsed > 0 ? processedRows * 1000.0 / elapsed : 0;
    }

    public long getElapsedMs() {
        return Instant.now().toEpochMilli() - startTime.toEpochMilli();
    }

    public String formatSpeed() {
        double rps = getRowsPerSecond();
        if (rps >= 1000) {
            return String.format("%.1fK rows/s", rps / 1000);
        }
        return String.format("%.0f rows/s", rps);
    }

    public String formatSize() {
        if (bytesWritten >= 1024 * 1024) {
            return String.format("%.2f MB", bytesWritten / (1024.0 * 1024));
        }
        if (bytesWritten >= 1024) {
            return String.format("%.2f KB", bytesWritten / 1024.0);
        }
        return bytesWritten + " B";
    }

    public String formatDuration() {
        long elapsed = getElapsedMs();
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        }
        return String.format("%ds", seconds);
    }
}
