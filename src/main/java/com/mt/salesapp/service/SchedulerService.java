package com.mt.salesapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * SchedulerService
 *
 * Spring Boot component located inside 'com.mt.salesapp.service'.
 * Triggers an HTTP GET request to 'https://madhan-traders-api.onrender.com/index.html'
 * immediately when the application starts up, and every 2 minutes thereafter.
 */
@Component
@EnableScheduling
public class SchedulerService {

    private static final String TARGET_URL = "https://madhan-traders-api.onrender.com/index.html";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerService.class);

    private final HttpClient httpClient;

    public SchedulerService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        LOGGER.info("=========================================================================");
        LOGGER.info(">>> SchedulerService Bean Loaded! URL ping starting immediately... <<<");
        LOGGER.info("=========================================================================");
    }

    /**
     * Executes automatically on startup (initialDelay = 0)
     * and runs every 2 minutes (fixedRate = 2).
     */
    @Scheduled(initialDelay = 0, fixedRate = 2, timeUnit = TimeUnit.MINUTES)
    public void executeUrlPing() {
        LOGGER.info(">>> [SCHEDULER] Ping execution triggered for: {}", TARGET_URL);
        long startTime = System.currentTimeMillis();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TARGET_URL))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "Spring-Boot-SchedulerService/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedTime = System.currentTimeMillis() - startTime;

            LOGGER.info(">>> [SCHEDULER] Response received in {} ms | HTTP Status Code: {}", elapsedTime, response.statusCode());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOGGER.info(">>> [SCHEDULER] SUCCESS: Endpoint responded successfully.");
            } else {
                LOGGER.warn(">>> [SCHEDULER] WARNING: Received status code: {}", response.statusCode());
            }

            String body = response.body();
            if (body != null && !body.isBlank()) {
                String snippet = body.trim().replaceAll("\\s+", " ");
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "... [truncated]";
                }
                LOGGER.info(">>> [SCHEDULER] Response Snippet: {}", snippet);
            } else {
                LOGGER.info(">>> [SCHEDULER] Response body is empty.");
            }

        } catch (IOException e) {
            LOGGER.error(">>> [SCHEDULER] Network/IO error while pinging {}: {}", TARGET_URL, e.getMessage());
        } catch (InterruptedException e) {
            LOGGER.warn(">>> [SCHEDULER] Ping request interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error(">>> [SCHEDULER] Unexpected error during scheduled execution: {}", e.getMessage(), e);
        }
    }
}