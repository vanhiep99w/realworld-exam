package com.seft.learn.example.service;

import com.seft.learn.example.entity.ExportJob;
import com.seft.learn.example.entity.ExportJob.ExportStatus;
import com.seft.learn.example.repository.ExportJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
	private final JdbcTemplate jdbcTemplate;
	private final S3StreamingUploader s3StreamingUploader;
	private final S3PresignedUrlService s3PresignedUrlService;

	private static final int FETCH_SIZE = 5000;
	private static final int PROGRESS_UPDATE_INTERVAL = 10000;

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
		String s3Key = "exports/" + jobId + ".csv";
		S3StreamingUploader.StreamingUpload upload = null;

		try {
			ExportJob job = exportJobRepository.findById(jobId).orElseThrow();
			job.setStatus(ExportStatus.RUNNING);
			job.setStartedAt(Instant.now());

			Long totalRecords = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM users", Long.class);
			job.setTotalRecords(totalRecords != null ? totalRecords : 0L);
			exportJobRepository.save(job);

			// Start streaming upload to S3
			upload = s3StreamingUploader.startUpload(s3Key);
			final S3StreamingUploader.StreamingUpload finalUpload = upload;

			// Write header
			finalUpload.write("\"id\",\"email\",\"name\",\"created_at\"");

			final long[] processed = {0};
			final UUID finalJobId = jobId;

			jdbcTemplate.query(con -> {
				var ps = con.prepareStatement(
						"SELECT id, email, name, created_at FROM users",
						java.sql.ResultSet.TYPE_FORWARD_ONLY,
						java.sql.ResultSet.CONCUR_READ_ONLY
				);
				ps.setFetchSize(FETCH_SIZE);
				return ps;
			}, rs -> {
				try {
					String line = String.format("\"%s\",\"%s\",\"%s\",\"%s\"",
							escapeCsv(rs.getString("id")),
							escapeCsv(rs.getString("email")),
							escapeCsv(rs.getString("name")),
							escapeCsv(rs.getString("created_at"))
					);
					finalUpload.write(line);

					processed[0]++;
					if (processed[0] % PROGRESS_UPDATE_INTERVAL == 0) {
						updateProgress(finalJobId, processed[0]);
						log.info("Export progress: jobId={}, processed={}", finalJobId, processed[0]);
					}
				} catch (IOException e) {
					throw new RuntimeException("Failed to write to S3 stream", e);
				}
			});

			// Complete multipart upload
			upload.complete();

			job = exportJobRepository.findById(jobId).orElseThrow();
			job.setStatus(ExportStatus.COMPLETED);
			job.setS3Key(s3Key);
			job.setProcessedRecords(job.getTotalRecords());
			job.setFinishedAt(Instant.now());
			exportJobRepository.save(job);

			log.info("Export completed: jobId={}, s3Key={}, totalRecords={}",
					jobId, s3Key, job.getTotalRecords());

		} catch (Exception e) {
			log.error("Export failed: jobId={}", jobId, e);
			if (upload != null) {
				upload.abort();
			}
			ExportJob job = exportJobRepository.findById(jobId).orElse(null);
			if (job != null) {
				job.setStatus(ExportStatus.FAILED);
				String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
				job.setErrorMessage(errorMsg.substring(0, Math.min(errorMsg.length(), 1000)));
				job.setFinishedAt(Instant.now());
				exportJobRepository.save(job);
			}
		}
	}

	@Transactional
	public void updateProgress(UUID jobId, long processed) {
		exportJobRepository.updateProgress(jobId, processed);
	}

	private String escapeCsv(String value) {
		if (value == null) return "";
		return value.replace("\"", "\"\"");
	}

	public ExportJob getJob(UUID jobId) {
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
