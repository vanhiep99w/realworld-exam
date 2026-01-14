package com.seft.learn.example.runner;

import com.seft.learn.example.service.S3PresignedUrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRunner implements CommandLineRunner {

	private final S3PresignedUrlService s3Service;

	@Override
	public void run(String... args) {
		log.info("Initializing S3 bucket...");
		s3Service.createBucketIfNotExists();
		log.info("S3 bucket initialization complete");
	}
}
