package com.seft.learn.example.service;

import com.seft.learn.example.entity.ExportJob;
import com.seft.learn.example.entity.ExportJob.ExportStatus;
import com.seft.learn.example.repository.ExportJobRepository;
import com.seft.learn.example.service.export.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExportService {

    private final ExportJobRepository exportJobRepository;
    private final S3StreamingUploader s3StreamingUploader;
    private final S3PresignedUrlService s3PresignedUrlService;
    private final StreamingQueryExecutor queryExecutor;
    private final ExportProgressTracker progressTracker;

    private static final boolean GZIP_ENABLED = true;

    public UUID startExport() {
        ExportJob job = ExportJob.builder()
                .status(ExportStatus.PENDING)
                .build();
        job = exportJobRepository.save(job);

        runExportAsync(job.getId());
        return job.getId();
    }

    @Async("taskExecutor")
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public void runExportAsync(UUID jobId) {
        String s3Key = generateS3Key(jobId);
        S3StreamingUploader.StreamingUpload upload = null;
        ExportMetrics metrics = ExportMetrics.builder()
                .jobId(jobId)
                .startTime(Instant.now())
                .build();

        try {
            initializeJob(jobId);
            upload = s3StreamingUploader.startUpload(s3Key, GZIP_ENABLED);
            
            processExport(jobId, upload, metrics);
            
            long fileSize = upload.complete();
            long uncompressedSize = upload.getUncompressedBytes();
            metrics.addBytes(fileSize);
            
            completeJob(jobId, s3Key, metrics, uncompressedSize);
            logCompletion(metrics, uncompressedSize);

        } catch (Exception e) {
            handleError(jobId, upload, e);
        }
    }

    private String generateS3Key(UUID jobId) {
        return "exports/" + jobId + ".csv" + (GZIP_ENABLED ? ".gz" : "");
    }

    private void initializeJob(UUID jobId) {
        ExportJob job = exportJobRepository.findById(jobId).orElseThrow();
        job.setStatus(ExportStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setTotalRecords(queryExecutor.countUsers());
        exportJobRepository.save(job);
    }

    private void processExport(UUID jobId, S3StreamingUploader.StreamingUpload upload, ExportMetrics metrics) {
        CsvFormatter formatter = new CsvFormatter();

        try {
            upload.write(formatter.formatHeader());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write CSV header", e);
        }

        queryExecutor.streamUsers(rs -> {
            try {
                String line = formatter.formatRow(rs);
                upload.write(line);
                metrics.incrementRows();

                if (progressTracker.shouldUpdate(metrics.getProcessedRows())) {
                    progressTracker.updateProgress(jobId, metrics.getProcessedRows());
                    log.info("Export progress: jobId={}, rows={}, speed={}",
                            jobId, metrics.getProcessedRows(), metrics.formatSpeed());
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to process row", e);
            }
        });
    }

    private void completeJob(UUID jobId, String s3Key, ExportMetrics metrics, long uncompressedSize) {
        ExportJob job = exportJobRepository.findById(jobId).orElseThrow();
        job.setStatus(ExportStatus.COMPLETED);
        job.setS3Key(s3Key);
        job.setProcessedRecords(metrics.getProcessedRows());
        job.setFinishedAt(Instant.now());
        job.setFileSizeBytes(metrics.getBytesWritten());
        job.setUncompressedSizeBytes(uncompressedSize);
        job.calculateMetrics();
        exportJobRepository.save(job);

        progressTracker.updateMetrics(
                jobId,
                metrics.getBytesWritten(),
                metrics.getRowsPerSecond(),
                metrics.getElapsedMs()
        );
    }

    private void logCompletion(ExportMetrics metrics, long uncompressedSize) {
        double compressionRatio = uncompressedSize > 0 ? 
                (1 - (double) metrics.getBytesWritten() / uncompressedSize) * 100 : 0;
        log.info("Export completed: jobId={}, rows={}, size={}, uncompressed={}, compression={:.1f}%, duration={}, speed={}",
                metrics.getJobId(),
                metrics.getProcessedRows(),
                metrics.formatSize(),
                formatBytes(uncompressedSize),
                compressionRatio,
                metrics.formatDuration(),
                metrics.formatSpeed());
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        }
        if (bytes >= 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private void handleError(UUID jobId, S3StreamingUploader.@Nullable StreamingUpload upload, Exception e) {
        log.error("Export failed: jobId={}", jobId, e);
        
        if (upload != null) {
            upload.abort();
        }
        
        ExportJob job = exportJobRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.setStatus(ExportStatus.FAILED);
            String errorMessage = e.getMessage();
            job.setErrorMessage(truncateError(errorMessage != null ? errorMessage : "Unknown error"));
            job.setFinishedAt(Instant.now());
            exportJobRepository.save(job);
        }
    }

    private String truncateError(String message) {
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    public @Nullable ExportJob getJob(UUID jobId) {
        return exportJobRepository.findById(jobId).orElse(null);
    }

    public String getDownloadUrl(UUID jobId) {
        ExportJob job = exportJobRepository.findById(jobId).orElseThrow();
        if (job.getStatus() != ExportStatus.COMPLETED) {
            throw new IllegalStateException("Export not completed yet");
        }
        return s3PresignedUrlService.generatePresignedGetUrl(job.getS3Key());
    }
}
