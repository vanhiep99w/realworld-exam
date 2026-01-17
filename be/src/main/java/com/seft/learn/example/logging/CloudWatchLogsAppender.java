package com.seft.learn.example.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class CloudWatchLogsAppender extends AppenderBase<ILoggingEvent> {

	private String endpoint = "http://localhost:4566";
	private String region = "ap-southeast-1";
	private String accessKey = "test";
	private String secretKey = "test";
	private String logGroupName = "/app/realworld-example/dev";
	private String logStreamName = "";

	@Nullable
	private CloudWatchLogsClient client;
	private final BlockingQueue<InputLogEvent> queue = new LinkedBlockingQueue<>(10000);
	@Nullable
	private ScheduledExecutorService scheduler;
	private volatile boolean initialized = false;

	private static final int BATCH_SIZE = 50;
	private static final int FLUSH_INTERVAL_MS = 5000;
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
			.withZone(ZoneId.systemDefault());

	private static final DateTimeFormatter STREAM_DATE_FORMATTER = DateTimeFormatter
			.ofPattern("yyyy-MM-dd")
			.withZone(ZoneId.systemDefault());

	@Override
	public void start() {
		if (logStreamName.isEmpty()) {
			logStreamName = getHostname() + "/" + STREAM_DATE_FORMATTER.format(Instant.now());
		}

		try {
			client = CloudWatchLogsClient.builder()
					.endpointOverride(URI.create(endpoint))
					.region(Region.of(region))
					.credentialsProvider(StaticCredentialsProvider.create(
							AwsBasicCredentials.create(accessKey, secretKey)))
					.build();

			createLogGroupIfNotExists();
			createLogStreamIfNotExists();

			scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "cloudwatch-logs-flusher");
				t.setDaemon(true);
				return t;
			});
			scheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

			initialized = true;
			super.start();
			addInfo("CloudWatch Logs appender started: " + logGroupName + "/" + logStreamName);
		} catch (Exception e) {
			addError("Failed to start CloudWatch Logs appender", e);
		}
	}

	@Override
	public void stop() {
		if (scheduler != null) {
			scheduler.shutdown();
			try {
				flush();
				scheduler.awaitTermination(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (client != null) {
			client.close();
		}
		super.stop();
	}

	@Override
	protected void append(ILoggingEvent event) {
		if (!initialized) return;

		String msg = formatEvent(event);
		InputLogEvent logEvent = InputLogEvent.builder()
				.timestamp(event.getTimeStamp())
				.message(msg)
				.build();

		if (!queue.offer(logEvent)) {
			addWarn("CloudWatch log queue full, dropping event");
		}
	}

	private String formatEvent(ILoggingEvent event) {
		StringBuilder sb = new StringBuilder();
		sb.append(FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp())));
		sb.append(" ").append(String.format("%-5s", event.getLevel()));
		sb.append(" [").append(event.getThreadName()).append("] ");
		sb.append(event.getLoggerName()).append(" - ");
		sb.append(event.getFormattedMessage());

		if (event.getThrowableProxy() != null) {
			var tp = event.getThrowableProxy();
			sb.append("\n").append(tp.getClassName()).append(": ").append(tp.getMessage());
			for (var ste : tp.getStackTraceElementProxyArray()) {
				sb.append("\n\tat ").append(ste.getSTEAsString());
			}
		}
		return sb.toString();
	}

	private void flush() {
		if (queue.isEmpty() || client == null) return;

		List<InputLogEvent> batch = new ArrayList<>(BATCH_SIZE);
		queue.drainTo(batch, BATCH_SIZE);
		if (batch.isEmpty()) return;

		batch.sort((a, b) -> Long.compare(a.timestamp(), b.timestamp()));

		try {
			client.putLogEvents(PutLogEventsRequest.builder()
					.logGroupName(logGroupName)
					.logStreamName(logStreamName)
					.logEvents(batch)
					.build());
		} catch (Exception e) {
			addError("Failed to push logs to CloudWatch", e);
		}
	}

	private void createLogGroupIfNotExists() {
		if (client == null) return;
		try {
			client.createLogGroup(CreateLogGroupRequest.builder().logGroupName(logGroupName).build());
		} catch (ResourceAlreadyExistsException ignored) {}
	}

	private void createLogStreamIfNotExists() {
		if (client == null) return;
		try {
			client.createLogStream(CreateLogStreamRequest.builder()
					.logGroupName(logGroupName)
					.logStreamName(logStreamName)
					.build());
		} catch (ResourceAlreadyExistsException ignored) {}
	}

	private String getHostname() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return "unknown";
		}
	}

	public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
	public void setRegion(String region) { this.region = region; }
	public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
	public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
	public void setLogGroupName(String logGroupName) { this.logGroupName = logGroupName; }
	public void setLogStreamName(String logStreamName) { this.logStreamName = logStreamName; }
}
