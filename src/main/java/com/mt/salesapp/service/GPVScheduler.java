package com.mt.salesapp.service;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class GPVScheduler {

    private static final List<String> RECENT_LOGS = new CopyOnWriteArrayList<>();
    private static final int MAX_RECENT_LOGS = 120;
    private static final PipelineStageTracker PIPELINE_TRACKER = new PipelineStageTracker();
    private static final List<OutputStream> SSE_SUBSCRIBERS = new CopyOnWriteArrayList<>();
    private static final DateTimeFormatter ZONE_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final Logger LOG = createLogger();

    private VideoPipelineScheduler activeScheduler;
    private SchedulerApiServer activeApiServer;

    @PostConstruct
    public void initSpringService() {
        try {
            startEngine(false);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to initialize GPVScheduler service", e);
        }
    }

    @PreDestroy
    public void destroySpringService() {
        if (activeScheduler != null) {
            activeScheduler.stop();
        }
        if (activeApiServer != null) {
            activeApiServer.stop();
        }
    }

    public static void main(String[] args) throws Exception {
        GPVScheduler app = new GPVScheduler();
        app.startEngine(true);
    }

    public synchronized void startEngine(boolean blockMainThread) throws Exception {
        if (activeScheduler != null) {
            return;
        }

        Path propertiesPath = Paths.get("src", "main", "resources", "application.properties");
        SchedulerConfig initialConfig = SchedulerConfig.loadFromEnvironmentAndProperties(propertiesPath);
        AtomicReference<SchedulerConfig> configRef = new AtomicReference<>(initialConfig);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        UsageQuotaManager quotaManager = new UsageQuotaManager(configRef);
        ResourcePromptRepository promptRepository = new ResourcePromptRepository(configRef);
        promptRepository.seedSamplePromptsIfEmpty();

        TrendingMetadataService metadataService = new TrendingMetadataService(configRef, httpClient);
        GeminiVeo3Client veo3Client = new GeminiVeo3Client(configRef, httpClient);
        SocialMediaPublisherOrchestrator socialOrchestrator = new SocialMediaPublisherOrchestrator(configRef, httpClient);

        activeScheduler = new VideoPipelineScheduler(
                configRef,
                quotaManager,
                promptRepository,
                metadataService,
                veo3Client,
                socialOrchestrator
        );

        activeApiServer = new SchedulerApiServer(
                configRef,
                quotaManager,
                promptRepository,
                metadataService,
                activeScheduler
        );
        activeApiServer.start();
        activeScheduler.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown signal received. Stopping GPVScheduler gracefully...");
            activeScheduler.stop();
            activeApiServer.stop();
        }));

        if (blockMainThread) {
            Thread.currentThread().join();
        }
    }

    private static String formatInstantInZone(Instant instant, ZoneId zone) {
        if (instant == null) {
            return "none";
        }
        ZoneId effectiveZone = zone != null ? zone : ZoneId.of("Asia/Kolkata");
        return ZonedDateTime.ofInstant(instant, effectiveZone).format(ZONE_DATE_TIME_FORMATTER);
    }

    private static Logger createLogger() {
        Logger logger = Logger.getLogger("GPVScheduler");
        logger.setUseParentHandlers(false);
        if (logger.getHandlers().length == 0) {
            ConsoleHandler handler = new ConsoleHandler();
            handler.setLevel(Level.ALL);
            handler.setFormatter(new Formatter() {
                private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                        .withZone(ZoneId.systemDefault());

                @Override
                public String format(LogRecord record) {
                    String ts = fmt.format(Instant.ofEpochMilli(record.getMillis()));
                    String line = "[" + ts + "] [" + record.getLevel().getName() + "] " + record.getMessage();
                    RECENT_LOGS.add(line);
                    while (RECENT_LOGS.size() > MAX_RECENT_LOGS) {
                        RECENT_LOGS.remove(0);
                    }
                    broadcastSseEvent("log", Map.of("line", line));
                    StringBuilder sb = new StringBuilder(line).append(System.lineSeparator());
                    if (record.getThrown() != null) {
                        sb.append("  Exception: ").append(record.getThrown()).append(System.lineSeparator());
                    }
                    return sb.toString();
                }
            });
            logger.addHandler(handler);
        }
        logger.setLevel(Level.INFO);
        return logger;
    }

    private static void broadcastSseEvent(String eventName, Object payload) {
        if (SSE_SUBSCRIBERS.isEmpty()) {
            return;
        }
        String frame = "event: " + eventName + "\n"
                + "data: " + MiniJson.stringify(payload) + "\n\n";
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : SSE_SUBSCRIBERS) {
            try {
                synchronized (os) {
                    os.write(bytes);
                    os.flush();
                }
            } catch (IOException e) {
                SSE_SUBSCRIBERS.remove(os);
            }
        }
    }

    public static final class PipelineStageTracker {
        private volatile String stage = "STANDBY";
        private volatile int progressPercent = 0;
        private volatile String activePromptFile = "";
        private volatile String activeDetail = "Awaiting eligible user time window or manual trigger.";
        private final List recentBroadcasts = new CopyOnWriteArrayList<>();

        public void update(String stage, int progressPercent, String activePromptFile, String activeDetail) {
            this.stage = stage;
            this.progressPercent = Math.max(0, Math.min(100, progressPercent));
            this.activePromptFile = activePromptFile == null ? "" : activePromptFile;
            this.activeDetail = activeDetail == null ? "" : activeDetail;
            broadcastSseEvent("pipeline", snapshot());
        }

        public void recordBroadcast(String promptFile, String videoFileName, ViralVideoMetadata meta, List<PlatformPublishResult> results) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("timestamp", Instant.now().toString());
            record.put("promptFile", promptFile);
            record.put("videoFileName", videoFileName);
            record.put("title", meta.title());
            record.put("tags", meta.tags());
            record.put("hashtags", meta.hashtags());
            List platformList = new ArrayList<>();
            for (PlatformPublishResult r : results) {
                platformList.add(Map.of(
                        "platform", r.platform(),
                        "success", r.success(),
                        "details", r.details()
                ));
            }
            record.put("platforms", platformList);
            recentBroadcasts.add(0, record);
            while (recentBroadcasts.size() > 20) {
                recentBroadcasts.remove(recentBroadcasts.size() - 1);
            }
            broadcastSseEvent("broadcast", record);
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stage", stage);
            map.put("progressPercent", progressPercent);
            map.put("activePromptFile", activePromptFile);
            map.put("activeDetail", activeDetail);
            return map;
        }

        public List getRecentBroadcasts() {
            return new ArrayList<>(recentBroadcasts);
        }
    }

    public static final class SchedulerConfig {
        public final Path propertiesFilePath;
        public final boolean videoCreationEnabled;
        public final int apiServerPort;
        public final int schedulerPollIntervalSeconds;

        public final ZoneId userTimeZone;
        public final LocalTime allowedStartTime;
        public final LocalTime allowedEndTime;
        public final Set<DayOfWeek> allowedDaysOfWeek;
        public final int maxVideosPerDay;
        public final int maxVideosPerHour;
        public final int minCooldownMinutesBetweenVideos;
        public final double maxDailyBudgetUsd;
        public final double estimatedCostPer4kVideoUsd;

        public final Path promptsDirectory;
        public final Path outputVideosDirectory;
        public final Path usageStateFile;

        public final String geminiApiKey;
        public final String geminiTextModel;
        public final String veoModel;
        public final String videoResolution;
        public final String videoAspectRatio;
        public final int videoDurationSeconds;
        public final int veoPollIntervalSeconds;
        public final int veoMaxWaitMinutes;

        public final boolean youtubeEnabled;
        public final String youtubeAccessToken;
        public final String youtubePrivacyStatus;
        public final String youtubeCategoryId;

        public final boolean instagramEnabled;
        public final String instagramBusinessAccountId;
        public final String instagramAccessToken;

        public final boolean twitterEnabled;
        public final String twitterBearerOrOAuth2UserToken;

        public final boolean facebookEnabled;
        public final String facebookPageId;
        public final String facebookPageAccessToken;

        public SchedulerConfig(
                Path propertiesFilePath,
                boolean videoCreationEnabled,
                int apiServerPort,
                int schedulerPollIntervalSeconds,
                ZoneId userTimeZone,
                LocalTime allowedStartTime,
                LocalTime allowedEndTime,
                Set<DayOfWeek> allowedDaysOfWeek,
                int maxVideosPerDay,
                int maxVideosPerHour,
                int minCooldownMinutesBetweenVideos,
                double maxDailyBudgetUsd,
                double estimatedCostPer4kVideoUsd,
                Path promptsDirectory,
                Path outputVideosDirectory,
                Path usageStateFile,
                String geminiApiKey,
                String geminiTextModel,
                String veoModel,
                String videoResolution,
                String videoAspectRatio,
                int videoDurationSeconds,
                int veoPollIntervalSeconds,
                int veoMaxWaitMinutes,
                boolean youtubeEnabled,
                String youtubeAccessToken,
                String youtubePrivacyStatus,
                String youtubeCategoryId,
                boolean instagramEnabled,
                String instagramBusinessAccountId,
                String instagramAccessToken,
                boolean twitterEnabled,
                String twitterBearerOrOAuth2UserToken,
                boolean facebookEnabled,
                String facebookPageId,
                String facebookPageAccessToken
        ) {
            this.propertiesFilePath = propertiesFilePath;
            this.videoCreationEnabled = videoCreationEnabled;
            this.apiServerPort = apiServerPort;
            this.schedulerPollIntervalSeconds = schedulerPollIntervalSeconds;
            this.userTimeZone = userTimeZone;
            this.allowedStartTime = allowedStartTime;
            this.allowedEndTime = allowedEndTime;
            this.allowedDaysOfWeek = allowedDaysOfWeek;
            this.maxVideosPerDay = maxVideosPerDay;
            this.maxVideosPerHour = maxVideosPerHour;
            this.minCooldownMinutesBetweenVideos = minCooldownMinutesBetweenVideos;
            this.maxDailyBudgetUsd = maxDailyBudgetUsd;
            this.estimatedCostPer4kVideoUsd = estimatedCostPer4kVideoUsd;
            this.promptsDirectory = promptsDirectory;
            this.outputVideosDirectory = outputVideosDirectory;
            this.usageStateFile = usageStateFile;
            this.geminiApiKey = geminiApiKey;
            this.geminiTextModel = geminiTextModel;
            this.veoModel = veoModel;
            this.videoResolution = videoResolution;
            this.videoAspectRatio = videoAspectRatio;
            this.videoDurationSeconds = videoDurationSeconds;
            this.veoPollIntervalSeconds = veoPollIntervalSeconds;
            this.veoMaxWaitMinutes = veoMaxWaitMinutes;
            this.youtubeEnabled = youtubeEnabled;
            this.youtubeAccessToken = youtubeAccessToken;
            this.youtubePrivacyStatus = youtubePrivacyStatus;
            this.youtubeCategoryId = youtubeCategoryId;
            this.instagramEnabled = instagramEnabled;
            this.instagramBusinessAccountId = instagramBusinessAccountId;
            this.instagramAccessToken = instagramAccessToken;
            this.twitterEnabled = twitterEnabled;
            this.twitterBearerOrOAuth2UserToken = twitterBearerOrOAuth2UserToken;
            this.facebookEnabled = facebookEnabled;
            this.facebookPageId = facebookPageId;
            this.facebookPageAccessToken = facebookPageAccessToken;
        }

        public static SchedulerConfig loadFromEnvironmentAndProperties(Path propertiesPath) {
            Properties props = new Properties();
            if (Files.exists(propertiesPath)) {
                try (InputStream is = Files.newInputStream(propertiesPath)) {
                    props.load(is);
                } catch (IOException e) {
                    LOG.warning("Could not read properties file " + propertiesPath + ": " + e.getMessage());
                }
            }

            boolean enabled = Boolean.parseBoolean(getSetting(props, "GEMINI_VIDEO_ENABLED", "true"));
            int apiPort = Integer.parseInt(getSetting(props, "SCHEDULER_API_PORT", "8081"));
            int pollSec = Integer.parseInt(getSetting(props, "SCHEDULER_POLL_INTERVAL_SECONDS", "30"));

            ZoneId zone = ZoneId.of(getSetting(props, "USER_TIMEZONE", "Asia/Kolkata"));
            LocalTime start = LocalTime.parse(getSetting(props, "USER_ALLOWED_START_TIME", "00:00"));
            LocalTime end = LocalTime.parse(getSetting(props, "USER_ALLOWED_END_TIME", "23:59"));

            String daysRaw = getSetting(props, "USER_ALLOWED_DAYS",
                    "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
            Set<DayOfWeek> days = Arrays.stream(daysRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> DayOfWeek.valueOf(s.toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            int maxDay = Integer.parseInt(getSetting(props, "USER_MAX_VIDEOS_PER_DAY", "5"));
            int maxHour = Integer.parseInt(getSetting(props, "USER_MAX_VIDEOS_PER_HOUR", "2"));
            int cooldownMin = Integer.parseInt(getSetting(props, "USER_COOLDOWN_MINUTES", "15"));
            double maxBudget = Double.parseDouble(getSetting(props, "USER_MAX_DAILY_BUDGET_USD", "25.00"));
            double costPerVideo = Double.parseDouble(getSetting(props, "ESTIMATED_COST_PER_4K_VIDEO_USD", "3.20"));

            Path promptsDir = Paths.get(getSetting(props, "PROMPTS_DIR", "src/main/resources/prompts"));
            Path outputDir = Paths.get(getSetting(props, "OUTPUT_VIDEOS_DIR", "generated_videos"));
            Path stateFile = Paths.get(getSetting(props, "USAGE_STATE_FILE", "scheduler_usage_state.json"));

            String geminiKey = getSetting(props, "GEMINI_API_KEY", "");
            String textModel = getSetting(props, "GEMINI_TEXT_MODEL", "gemini-2.5-flash");
            String veoModel = getSetting(props, "GEMINI_VEO_MODEL", "gemini-omni-1.2-flash");
            String resolution = getSetting(props, "VEO_RESOLUTION", "4k");
            String aspectRatio = getSetting(props, "VEO_ASPECT_RATIO", "16:9");
            int durationSec = Integer.parseInt(getSetting(props, "VEO_DURATION_SECONDS", "8"));
            int veoPollSec = Integer.parseInt(getSetting(props, "VEO_POLL_INTERVAL_SECONDS", "10"));
            int veoMaxWaitMin = Integer.parseInt(getSetting(props, "VEO_MAX_WAIT_MINUTES", "12"));

            boolean ytEnabled = Boolean.parseBoolean(getSetting(props, "YOUTUBE_UPLOAD_ENABLED", "true"));
            String ytToken = getSetting(props, "YOUTUBE_ACCESS_TOKEN", "");
            String ytPrivacy = getSetting(props, "YOUTUBE_PRIVACY_STATUS", "public");
            String ytCategory = getSetting(props, "YOUTUBE_CATEGORY_ID", "24");

            boolean igEnabled = Boolean.parseBoolean(getSetting(props, "INSTAGRAM_UPLOAD_ENABLED", "true"));
            String igAccountId = getSetting(props, "INSTAGRAM_BUSINESS_ACCOUNT_ID", "");
            String igToken = getSetting(props, "INSTAGRAM_ACCESS_TOKEN", "");

            boolean twEnabled = Boolean.parseBoolean(getSetting(props, "TWITTER_UPLOAD_ENABLED", "true"));
            String twToken = getSetting(props, "TWITTER_USER_ACCESS_TOKEN", "");

            boolean fbEnabled = Boolean.parseBoolean(getSetting(props, "FACEBOOK_UPLOAD_ENABLED", "true"));
            String fbPageId = getSetting(props, "FACEBOOK_PAGE_ID", "");
            String fbToken = getSetting(props, "FACEBOOK_PAGE_ACCESS_TOKEN", "");

            return new SchedulerConfig(
                    propertiesPath,
                    enabled, apiPort, pollSec,
                    zone, start, end, days,
                    maxDay, maxHour, cooldownMin, maxBudget, costPerVideo,
                    promptsDir, outputDir, stateFile,
                    geminiKey, textModel, veoModel, resolution, aspectRatio, durationSec, veoPollSec, veoMaxWaitMin,
                    ytEnabled, ytToken, ytPrivacy, ytCategory,
                    igEnabled, igAccountId, igToken,
                    twEnabled, twToken,
                    fbEnabled, fbPageId, fbToken
            );
        }

        public SchedulerConfig mergeFromJson(Map<String, Object> json) {
            boolean enabled = MiniJson.asBoolean(json.get("videoCreationEnabled"), this.videoCreationEnabled);
            int pollSec = MiniJson.asInt(json.get("schedulerPollIntervalSeconds"), this.schedulerPollIntervalSeconds);

            String tzStr = MiniJson.asString(json.get("userTimeZone"), this.userTimeZone.getId());
            ZoneId zone = ZoneId.of(tzStr);

            String startStr = MiniJson.asString(json.get("allowedStartTime"), this.allowedStartTime.toString());
            String endStr = MiniJson.asString(json.get("allowedEndTime"), this.allowedEndTime.toString());
            LocalTime start = LocalTime.parse(startStr);
            LocalTime end = LocalTime.parse(endStr);

            Set<DayOfWeek> days = this.allowedDaysOfWeek;
            if (json.containsKey("allowedDaysOfWeek")) {
                List<String> dayList = MiniJson.asStringList(json.get("allowedDaysOfWeek"));
                if (!dayList.isEmpty()) {
                    days = dayList.stream()
                            .map(s -> DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT)))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                }
            }

            int maxDay = MiniJson.asInt(json.get("maxVideosPerDay"), this.maxVideosPerDay);
            int maxHour = MiniJson.asInt(json.get("maxVideosPerHour"), this.maxVideosPerHour);
            int cooldownMin = MiniJson.asInt(json.get("minCooldownMinutesBetweenVideos"), this.minCooldownMinutesBetweenVideos);
            double maxBudget = MiniJson.asDouble(json.get("maxDailyBudgetUsd"), this.maxDailyBudgetUsd);
            double costPerVideo = MiniJson.asDouble(json.get("estimatedCostPer4kVideoUsd"), this.estimatedCostPer4kVideoUsd);

            String newGeminiKey = MiniJson.asString(json.get("geminiApiKey"), "");
            String effectiveGeminiKey = newGeminiKey.isBlank() ? this.geminiApiKey : newGeminiKey;

            String veoModelVal = MiniJson.asString(json.get("veoModel"), this.veoModel);
            String resVal = MiniJson.asString(json.get("videoResolution"), this.videoResolution);
            String aspectVal = MiniJson.asString(json.get("videoAspectRatio"), this.videoAspectRatio);
            int durVal = MiniJson.asInt(json.get("videoDurationSeconds"), this.videoDurationSeconds);

            boolean ytEn = MiniJson.asBoolean(json.get("youtubeEnabled"), this.youtubeEnabled);
            String newYtTok = MiniJson.asString(json.get("youtubeAccessToken"), "");
            String effYtTok = newYtTok.isBlank() ? this.youtubeAccessToken : newYtTok;
            String ytPriv = MiniJson.asString(json.get("youtubePrivacyStatus"), this.youtubePrivacyStatus);
            String ytCat = MiniJson.asString(json.get("youtubeCategoryId"), this.youtubeCategoryId);

            boolean igEn = MiniJson.asBoolean(json.get("instagramEnabled"), this.instagramEnabled);
            String igId = MiniJson.asString(json.get("instagramBusinessAccountId"), this.instagramBusinessAccountId);
            String newIgTok = MiniJson.asString(json.get("instagramAccessToken"), "");
            String effIgTok = newIgTok.isBlank() ? this.instagramAccessToken : newIgTok;

            boolean twEn = MiniJson.asBoolean(json.get("twitterEnabled"), this.twitterEnabled);
            String newTwTok = MiniJson.asString(json.get("twitterBearerOrOAuth2UserToken"), "");
            String effTwTok = newTwTok.isBlank() ? this.twitterBearerOrOAuth2UserToken : newTwTok;

            boolean fbEn = MiniJson.asBoolean(json.get("facebookEnabled"), this.facebookEnabled);
            String fbId = MiniJson.asString(json.get("facebookPageId"), this.facebookPageId);
            String newFbTok = MiniJson.asString(json.get("facebookPageAccessToken"), "");
            String effFbTok = newFbTok.isBlank() ? this.facebookPageAccessToken : newFbTok;

            return new SchedulerConfig(
                    this.propertiesFilePath,
                    enabled, this.apiServerPort, pollSec,
                    zone, start, end, days,
                    maxDay, maxHour, cooldownMin, maxBudget, costPerVideo,
                    this.promptsDirectory, this.outputVideosDirectory, this.usageStateFile,
                    effectiveGeminiKey, this.geminiTextModel, veoModelVal, resVal, aspectVal, durVal,
                    this.veoPollIntervalSeconds, this.veoMaxWaitMinutes,
                    ytEn, effYtTok, ytPriv, ytCat,
                    igEn, igId, effIgTok,
                    twEn, effTwTok,
                    fbEn, fbId, effFbTok
            );
        }

        public void saveToPropertiesFile() throws IOException {
            if (propertiesFilePath.getParent() != null) {
                Files.createDirectories(propertiesFilePath.getParent());
            }
            Properties existing = new Properties();
            if (Files.exists(propertiesFilePath)) {
                try (InputStream is = Files.newInputStream(propertiesFilePath)) {
                    existing.load(is);
                }
            }

            existing.setProperty("GEMINI_VIDEO_ENABLED", String.valueOf(videoCreationEnabled));
            existing.setProperty("SCHEDULER_API_PORT", String.valueOf(apiServerPort));
            existing.setProperty("SCHEDULER_POLL_INTERVAL_SECONDS", String.valueOf(schedulerPollIntervalSeconds));
            existing.setProperty("USER_TIMEZONE", userTimeZone.getId());
            existing.setProperty("USER_ALLOWED_START_TIME", allowedStartTime.toString());
            existing.setProperty("USER_ALLOWED_END_TIME", allowedEndTime.toString());
            existing.setProperty("USER_ALLOWED_DAYS", allowedDaysOfWeek.stream().map(Enum::name).collect(Collectors.joining(",")));
            existing.setProperty("USER_MAX_VIDEOS_PER_DAY", String.valueOf(maxVideosPerDay));
            existing.setProperty("USER_MAX_VIDEOS_PER_HOUR", String.valueOf(maxVideosPerHour));
            existing.setProperty("USER_COOLDOWN_MINUTES", String.valueOf(minCooldownMinutesBetweenVideos));
            existing.setProperty("USER_MAX_DAILY_BUDGET_USD", String.valueOf(maxDailyBudgetUsd));
            existing.setProperty("ESTIMATED_COST_PER_4K_VIDEO_USD", String.valueOf(estimatedCostPer4kVideoUsd));
            existing.setProperty("PROMPTS_DIR", promptsDirectory.toString().replace("\\", "/"));
            existing.setProperty("OUTPUT_VIDEOS_DIR", outputVideosDirectory.toString().replace("\\", "/"));
            existing.setProperty("GEMINI_API_KEY", geminiApiKey);
            existing.setProperty("GEMINI_VEO_MODEL", veoModel);
            existing.setProperty("VEO_RESOLUTION", videoResolution);
            existing.setProperty("VEO_ASPECT_RATIO", videoAspectRatio);
            existing.setProperty("VEO_DURATION_SECONDS", String.valueOf(videoDurationSeconds));
            existing.setProperty("YOUTUBE_UPLOAD_ENABLED", String.valueOf(youtubeEnabled));
            existing.setProperty("YOUTUBE_ACCESS_TOKEN", youtubeAccessToken);
            existing.setProperty("YOUTUBE_PRIVACY_STATUS", youtubePrivacyStatus);
            existing.setProperty("YOUTUBE_CATEGORY_ID", youtubeCategoryId);
            existing.setProperty("INSTAGRAM_UPLOAD_ENABLED", String.valueOf(instagramEnabled));
            existing.setProperty("INSTAGRAM_BUSINESS_ACCOUNT_ID", instagramBusinessAccountId);
            existing.setProperty("INSTAGRAM_ACCESS_TOKEN", instagramAccessToken);
            existing.setProperty("TWITTER_UPLOAD_ENABLED", String.valueOf(twitterEnabled));
            existing.setProperty("TWITTER_USER_ACCESS_TOKEN", twitterBearerOrOAuth2UserToken);
            existing.setProperty("FACEBOOK_UPLOAD_ENABLED", String.valueOf(facebookEnabled));
            existing.setProperty("FACEBOOK_PAGE_ID", facebookPageId);
            existing.setProperty("FACEBOOK_PAGE_ACCESS_TOKEN", facebookPageAccessToken);

            try (OutputStream os = Files.newOutputStream(propertiesFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                existing.store(os, "Updated via VEO-3 Neural Mission Control");
            }
        }

        public Map<String, Object> toSafeMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("videoCreationEnabled", videoCreationEnabled);
            map.put("apiServerPort", apiServerPort);
            map.put("schedulerPollIntervalSeconds", schedulerPollIntervalSeconds);
            map.put("userTimeZone", userTimeZone.getId());
            map.put("allowedStartTime", allowedStartTime.toString());
            map.put("allowedEndTime", allowedEndTime.toString());
            map.put("allowedDaysOfWeek", allowedDaysOfWeek.stream().map(Enum::name).toList());
            map.put("maxVideosPerDay", maxVideosPerDay);
            map.put("maxVideosPerHour", maxVideosPerHour);
            map.put("minCooldownMinutesBetweenVideos", minCooldownMinutesBetweenVideos);
            map.put("maxDailyBudgetUsd", maxDailyBudgetUsd);
            map.put("estimatedCostPer4kVideoUsd", estimatedCostPer4kVideoUsd);
            map.put("promptsDirectory", promptsDirectory.toString());
            map.put("outputVideosDirectory", outputVideosDirectory.toString());
            map.put("geminiApiKeyMasked", maskSecret(geminiApiKey));
            map.put("geminiTextModel", geminiTextModel);
            map.put("veoModel", veoModel);
            map.put("videoResolution", videoResolution);
            map.put("videoAspectRatio", videoAspectRatio);
            map.put("videoDurationSeconds", videoDurationSeconds);
            map.put("youtubeEnabled", youtubeEnabled);
            map.put("youtubeAccessTokenMasked", maskSecret(youtubeAccessToken));
            map.put("youtubePrivacyStatus", youtubePrivacyStatus);
            map.put("youtubeCategoryId", youtubeCategoryId);
            map.put("instagramEnabled", instagramEnabled);
            map.put("instagramBusinessAccountId", instagramBusinessAccountId);
            map.put("instagramAccessTokenMasked", maskSecret(instagramAccessToken));
            map.put("twitterEnabled", twitterEnabled);
            map.put("twitterTokenMasked", maskSecret(twitterBearerOrOAuth2UserToken));
            map.put("facebookEnabled", facebookEnabled);
            map.put("facebookPageId", facebookPageId);
            map.put("facebookTokenMasked", maskSecret(facebookPageAccessToken));
            return map;
        }

        private static String maskSecret(String secret) {
            if (secret == null || secret.isBlank()) {
                return "";
            }
            if (secret.length() <= 6) {
                return "******";
            }
            return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 2);
        }

        private static String getSetting(Properties props, String key, String defaultValue) {
            String envVal = System.getenv(key);
            if (envVal != null && !envVal.isBlank()) {
                return envVal.trim();
            }
            String sysVal = System.getProperty(key);
            if (sysVal != null && !sysVal.isBlank()) {
                return sysVal.trim();
            }
            String propVal = props.getProperty(key);
            if (propVal != null && !propVal.isBlank()) {
                return propVal.trim();
            }
            return defaultValue;
        }
    }

    public record PromptTask(String fileName, Path filePath, String promptText) {}

    public static final class ResourcePromptRepository {
        private final AtomicReference<SchedulerConfig> configRef;

        public ResourcePromptRepository(AtomicReference<SchedulerConfig> configRef) {
            this.configRef = configRef;
        }

        public void seedSamplePromptsIfEmpty() throws IOException {
            Path dir = configRef.get().promptsDirectory;
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                boolean hasPrompts = files.anyMatch(this::isSupportedPromptFile);
                if (!hasPrompts) {
                    Files.writeString(dir.resolve("01_cyberpunk_tokyo_rain.txt"),
                            "Ultra-detailed 4K cinematic drone shot gliding through futuristic Neo-Tokyo at night during a neon-lit rainstorm, reflections shimmering on wet asphalt, volumetric fog, anamorphic lens flare, hyper-realistic 60fps.",
                            StandardCharsets.UTF_8);
                    Files.writeString(dir.resolve("02_aurora_iceland_glacier.txt"),
                            "Breathtaking 4K aerial time-lapse of emerald and violet Aurora Borealis dancing over a crystal-clear glacial lagoon in Iceland, floating icebergs reflecting the glowing sky, crisp starfield, National Geographic cinematography.",
                            StandardCharsets.UTF_8);
                    Files.writeString(dir.resolve("03_macro_bioluminescent_ocean.txt"),
                            "Cinematic 4K underwater macro shot of bioluminescent jellyfish pulsing with electric blue and gold light in the deep midnight ocean, slow graceful motion, dramatic chiaroscuro lighting, photorealistic.",
                            StandardCharsets.UTF_8);
                    LOG.info("Seeded 3 sample 4K prompt files in " + dir.toAbsolutePath());
                }
            }
        }

        public synchronized Optional<PromptTask> fetchNextUnprocessedPrompt(UsageQuotaManager quotaManager) throws IOException {
            Path dir = configRef.get().promptsDirectory;
            Files.createDirectories(dir);

            List<Path> sortedPromptFiles;
            try (Stream<Path> stream = Files.list(dir)) {
                sortedPromptFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isSupportedPromptFile)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .toList();
            }

            for (Path file : sortedPromptFiles) {
                String fileName = file.getFileName().toString();
                if (quotaManager.isPromptAlreadyCompleted(fileName)) {
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    return Optional.of(new PromptTask(fileName, file, content));
                }
            }
            return Optional.empty();
        }

        public synchronized List listAllPrompts(UsageQuotaManager quotaManager) throws IOException {
            Path dir = configRef.get().promptsDirectory;
            Files.createDirectories(dir);

            List<Path> sortedPromptFiles;
            try (Stream<Path> stream = Files.list(dir)) {
                sortedPromptFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isSupportedPromptFile)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .toList();
            }

            List list = new ArrayList<>();
            boolean foundNext = false;
            for (Path file : sortedPromptFiles) {
                String name = file.getFileName().toString();
                String text = Files.readString(file, StandardCharsets.UTF_8).trim();
                boolean completed = quotaManager.isPromptAlreadyCompleted(name);
                boolean isNext = !completed && !foundNext;
                if (isNext) {
                    foundNext = true;
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileName", name);
                item.put("promptText", text);
                item.put("status", completed ? "COMPLETED" : "QUEUED");
                item.put("isNext", isNext);
                item.put("sizeBytes", Files.size(file));
                list.add(item);
            }
            return list;
        }

        public synchronized Path saveOrUpdatePrompt(String rawFileName, String promptText) throws IOException {
            Path dir = configRef.get().promptsDirectory;
            Files.createDirectories(dir);
            String sanitized = sanitizePromptFileName(rawFileName);
            Path target = dir.resolve(sanitized).normalize();
            if (!target.startsWith(dir.normalize())) {
                throw new SecurityException("Invalid prompt filename path.");
            }
            Files.writeString(target, promptText.trim() + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return target;
        }

        public synchronized boolean deletePromptFile(String rawFileName) throws IOException {
            Path dir = configRef.get().promptsDirectory;
            String sanitized = sanitizePromptFileName(rawFileName);
            Path target = dir.resolve(sanitized).normalize();
            if (!target.startsWith(dir.normalize())) {
                throw new SecurityException("Invalid prompt filename path.");
            }
            return Files.deleteIfExists(target);
        }

        public synchronized void reorderPrompts(List<String> orderedFileNames, UsageQuotaManager quotaManager) throws IOException {
            Path dir = configRef.get().promptsDirectory;
            Files.createDirectories(dir);
            if (orderedFileNames == null || orderedFileNames.isEmpty()) {
                return;
            }

            List<Path> tempFiles = new ArrayList<>();
            List<String> targetNames = new ArrayList<>();
            List<Boolean> wasCompletedList = new ArrayList<>();

            int index = 1;
            for (String rawName : orderedFileNames) {
                String sanitized = sanitizePromptFileName(rawName);
                Path source = dir.resolve(sanitized).normalize();
                if (!source.startsWith(dir.normalize()) || !Files.exists(source)) {
                    continue;
                }
                boolean wasCompleted = quotaManager.isPromptAlreadyCompleted(sanitized);
                String strippedBase = sanitized.replaceFirst("^\\d+_", "");
                String newName = String.format("%02d_%s", index++, strippedBase);
                Path temp = dir.resolve(".tmp_reorder_" + UUID.randomUUID() + "_" + newName);
                Files.move(source, temp);
                tempFiles.add(temp);
                targetNames.add(newName);
                wasCompletedList.add(wasCompleted);
                if (wasCompleted) {
                    quotaManager.requeuePrompt(sanitized);
                }
            }

            for (int i = 0; i < tempFiles.size(); i++) {
                Path finalTarget = dir.resolve(targetNames.get(i));
                Files.move(tempFiles.get(i), finalTarget);
                if (wasCompletedList.get(i)) {
                    quotaManager.recordCompletedMarkerOnly(targetNames.get(i));
                }
            }
            LOG.info("Reordered " + targetNames.size() + " prompt files in resources/prompts queue.");
        }

        private String sanitizePromptFileName(String raw) {
            String base = Paths.get(raw == null ? "" : raw.trim()).getFileName().toString();
            base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (base.isEmpty() || base.equals(".") || base.equals("..")) {
                base = "prompt_" + System.currentTimeMillis() + ".txt";
            }
            if (!base.toLowerCase(Locale.ROOT).endsWith(".txt") && !base.toLowerCase(Locale.ROOT).endsWith(".prompt")) {
                base = base + ".txt";
            }
            return base;
        }

        private boolean isSupportedPromptFile(Path path) {
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return !lower.startsWith(".") && (lower.endsWith(".txt") || lower.endsWith(".prompt"));
        }
    }

    public record EligibilityResult(boolean allowed, String reason) {}

    public static final class UsageQuotaManager {
        private final AtomicReference<SchedulerConfig> configRef;
        private LocalDate activeUserDate;
        private int videosGeneratedToday;
        private double estimatedSpendTodayUsd;
        private Instant lastVideoGeneratedAt;
        private Instant rateLimitedUntil;
        private final List<Instant> recentHourlyTimestamps = new ArrayList<>();
        private final Set<String> completedPromptFileNames = new LinkedHashSet<>();

        public UsageQuotaManager(AtomicReference<SchedulerConfig> configRef) {
            this.configRef = configRef;
            this.activeUserDate = LocalDate.now(configRef.get().userTimeZone);
            loadStateFromDisk();
        }

        public synchronized Instant recordRateLimitBackoff(long retryAfterSeconds) {
            ZoneId zone = configRef.get().userTimeZone;
            long safeSeconds = Math.max(1L, retryAfterSeconds);
            this.rateLimitedUntil = Instant.now().plusSeconds(safeSeconds);
            saveStateToDisk();
            LOG.warning("Gemini rate/quota limit hit (HTTP 429). Pausing automatic retries for "
                    + safeSeconds + "s until " + formatInstantInZone(this.rateLimitedUntil, zone)
                    + " (" + this.rateLimitedUntil + ").");
            return this.rateLimitedUntil;
        }

        public synchronized void clearRateLimitBackoff() {
            this.rateLimitedUntil = null;
            saveStateToDisk();
        }

        public synchronized Instant getRateLimitedUntil() {
            getRateLimitRemainingSeconds();
            return rateLimitedUntil;
        }

        public synchronized long getRateLimitRemainingSeconds() {
            if (rateLimitedUntil == null) {
                return 0L;
            }
            long remaining = Duration.between(Instant.now(), rateLimitedUntil).getSeconds();
            if (remaining <= 0L) {
                rateLimitedUntil = null;
                saveStateToDisk();
                return 0L;
            }
            return remaining;
        }

        public synchronized EligibilityResult evaluateEligibility() {
            SchedulerConfig config = configRef.get();
            if (!config.videoCreationEnabled) {
                return new EligibilityResult(false, "Gemini video creation is disabled (GEMINI_VIDEO_ENABLED=false).");
            }

            long backoffRemaining = getRateLimitRemainingSeconds();
            if (backoffRemaining > 0L) {
                return new EligibilityResult(false, String.format(
                        "API rate/quota backoff active: %d second(s) remaining (resets at %s).",
                        backoffRemaining, formatInstantInZone(rateLimitedUntil, config.userTimeZone)
                ));
            }

            ZonedDateTime userNow = ZonedDateTime.now(config.userTimeZone);
            rolloverIfNewUserDay(userNow.toLocalDate());

            DayOfWeek today = userNow.getDayOfWeek();
            if (!config.allowedDaysOfWeek.contains(today)) {
                return new EligibilityResult(false, "Today (" + today + ") is outside USER_ALLOWED_DAYS.");
            }

            LocalTime currentTime = userNow.toLocalTime();
            if (!isWithinAllowedTimeWindow(currentTime, config.allowedStartTime, config.allowedEndTime)) {
                return new EligibilityResult(false, String.format(
                        "Current user time %s (%s) is outside allowed window [%s - %s].",
                        currentTime.withNano(0), config.userTimeZone, config.allowedStartTime, config.allowedEndTime
                ));
            }

            if (videosGeneratedToday >= config.maxVideosPerDay) {
                return new EligibilityResult(false, String.format(
                        "Daily usage limit reached (%d / %d videos today).",
                        videosGeneratedToday, config.maxVideosPerDay
                ));
            }

            if (estimatedSpendTodayUsd + config.estimatedCostPer4kVideoUsd > config.maxDailyBudgetUsd) {
                return new EligibilityResult(false, String.format(
                        "Daily budget limit reached ($%.2f / $%.2f USD).",
                        estimatedSpendTodayUsd, config.maxDailyBudgetUsd
                ));
            }

            Instant nowInstant = Instant.now();
            pruneOldHourlyTimestamps(nowInstant);
            if (recentHourlyTimestamps.size() >= config.maxVideosPerHour) {
                return new EligibilityResult(false, String.format(
                        "Hourly usage limit reached (%d / %d videos in rolling 60m).",
                        recentHourlyTimestamps.size(), config.maxVideosPerHour
                ));
            }

            if (lastVideoGeneratedAt != null && config.minCooldownMinutesBetweenVideos > 0) {
                long minutesElapsed = Duration.between(lastVideoGeneratedAt, nowInstant).toMinutes();
                if (minutesElapsed < config.minCooldownMinutesBetweenVideos) {
                    long remaining = config.minCooldownMinutesBetweenVideos - minutesElapsed;
                    return new EligibilityResult(false, String.format(
                            "Cooldown active: %d minute(s) remaining before next video.", remaining
                    ));
                }
            }

            return new EligibilityResult(true, String.format(
                    "Eligible! User time %s | Daily quota: %d/%d | Hourly quota: %d/%d",
                    currentTime.withNano(0), videosGeneratedToday, config.maxVideosPerDay,
                    recentHourlyTimestamps.size(), config.maxVideosPerHour
            ));
        }

        private boolean isWithinAllowedTimeWindow(LocalTime now, LocalTime start, LocalTime end) {
            if (start.equals(end)) {
                return true;
            }
            if (start.isBefore(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            }
            return !now.isBefore(start) || !now.isAfter(end);
        }

        public synchronized boolean isPromptAlreadyCompleted(String promptFileName) {
            return completedPromptFileNames.contains(promptFileName);
        }

        public synchronized void recordSuccessfulVideoRun(String promptFileName) {
            ZonedDateTime userNow = ZonedDateTime.now(configRef.get().userTimeZone);
            rolloverIfNewUserDay(userNow.toLocalDate());

            Instant now = Instant.now();
            videosGeneratedToday++;
            estimatedSpendTodayUsd += configRef.get().estimatedCostPer4kVideoUsd;
            lastVideoGeneratedAt = now;
            recentHourlyTimestamps.add(now);
            completedPromptFileNames.add(promptFileName);
            saveStateToDisk();
        }

        public synchronized void requeuePrompt(String promptFileName) {
            if ("*".equals(promptFileName)) {
                completedPromptFileNames.clear();
            } else {
                completedPromptFileNames.remove(promptFileName);
            }
            saveStateToDisk();
        }

        public synchronized void recordCompletedMarkerOnly(String promptFileName) {
            completedPromptFileNames.add(promptFileName);
            saveStateToDisk();
        }

        public synchronized Map<String, Object> getQuotaSnapshot() {
            SchedulerConfig config = configRef.get();
            ZonedDateTime userNow = ZonedDateTime.now(config.userTimeZone);
            rolloverIfNewUserDay(userNow.toLocalDate());
            pruneOldHourlyTimestamps(Instant.now());
            long backoffRemaining = getRateLimitRemainingSeconds();
            boolean activeBackoff = backoffRemaining > 0L && rateLimitedUntil != null;

            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("activeUserDate", activeUserDate.toString());
            snap.put("userTimeZone", config.userTimeZone.getId());
            snap.put("videosGeneratedToday", videosGeneratedToday);
            snap.put("maxVideosPerDay", config.maxVideosPerDay);
            snap.put("videosInLastHour", recentHourlyTimestamps.size());
            snap.put("maxVideosPerHour", config.maxVideosPerHour);
            snap.put("estimatedSpendTodayUsd", Math.round(estimatedSpendTodayUsd * 100.0) / 100.0);
            snap.put("maxDailyBudgetUsd", config.maxDailyBudgetUsd);
            snap.put("minCooldownMinutes", config.minCooldownMinutesBetweenVideos);
            snap.put("lastVideoGeneratedAt", lastVideoGeneratedAt != null ? lastVideoGeneratedAt.toString() : null);
            snap.put("rateLimitRemainingSeconds", backoffRemaining);
            snap.put("rateLimitedUntil", activeBackoff ? rateLimitedUntil.toString() : null);
            snap.put("rateLimitedUntilUserZone", activeBackoff ? formatInstantInZone(rateLimitedUntil, config.userTimeZone) : null);
            return snap;
        }

        private void rolloverIfNewUserDay(LocalDate currentUserDate) {
            if (!currentUserDate.equals(activeUserDate)) {
                LOG.info("New user day detected (" + currentUserDate + "). Resetting daily video & budget counters.");
                activeUserDate = currentUserDate;
                videosGeneratedToday = 0;
                estimatedSpendTodayUsd = 0.0;
                saveStateToDisk();
            }
        }

        private void pruneOldHourlyTimestamps(Instant now) {
            Instant oneHourAgo = now.minus(Duration.ofHours(1));
            recentHourlyTimestamps.removeIf(ts -> ts.isBefore(oneHourAgo));
        }

        private void loadStateFromDisk() {
            Path statePath = configRef.get().usageStateFile;
            if (!Files.exists(statePath)) {
                return;
            }
            try {
                String json = Files.readString(statePath, StandardCharsets.UTF_8);
                Map<String, Object> map = MiniJson.parseObject(json);
                String savedDate = MiniJson.asString(map.get("activeUserDate"), "");
                if (!savedDate.isEmpty()) {
                    this.activeUserDate = LocalDate.parse(savedDate);
                }
                this.videosGeneratedToday = MiniJson.asInt(map.get("videosGeneratedToday"), 0);
                this.estimatedSpendTodayUsd = MiniJson.asDouble(map.get("estimatedSpendTodayUsd"), 0.0);
                String lastTs = MiniJson.asString(map.get("lastVideoGeneratedAt"), "");
                if (!lastTs.isEmpty()) {
                    this.lastVideoGeneratedAt = Instant.parse(lastTs);
                }
                String backoffTs = MiniJson.asString(map.get("rateLimitedUntil"), "");
                if (!backoffTs.isEmpty()) {
                    Instant parsedBackoff = Instant.parse(backoffTs);
                    if (parsedBackoff.isAfter(Instant.now())) {
                        this.rateLimitedUntil = parsedBackoff;
                    }
                }
                for (String item : MiniJson.asStringList(map.get("completedPromptFileNames"))) {
                    completedPromptFileNames.add(item);
                }
            } catch (Exception e) {
                LOG.warning("Could not restore usage state file: " + e.getMessage());
            }
        }

        private void saveStateToDisk() {
            Path statePath = configRef.get().usageStateFile;
            try {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("activeUserDate", activeUserDate.toString());
                state.put("videosGeneratedToday", videosGeneratedToday);
                state.put("estimatedSpendTodayUsd", estimatedSpendTodayUsd);
                state.put("lastVideoGeneratedAt", lastVideoGeneratedAt != null ? lastVideoGeneratedAt.toString() : "");
                state.put("rateLimitedUntil", rateLimitedUntil != null ? rateLimitedUntil.toString() : "");
                state.put("completedPromptFileNames", new ArrayList<>(completedPromptFileNames));
                Files.writeString(statePath, MiniJson.stringify(state), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                LOG.warning("Failed to persist usage state: " + e.getMessage());
            }
        }
    }

    public record ViralVideoMetadata(
            String title,
            String description,
            List<String> tags,
            List<String> hashtags
    ) {}

    public static final class TrendingMetadataService {
        private final AtomicReference<SchedulerConfig> configRef;
        private final HttpClient httpClient;

        public TrendingMetadataService(AtomicReference<SchedulerConfig> configRef, HttpClient httpClient) {
            this.configRef = configRef;
            this.httpClient = httpClient;
        }

        public ViralVideoMetadata generateWorldwideTrendingMetadata(PromptTask task) {
            SchedulerConfig config = configRef.get();
            if (config.geminiApiKey.isBlank()) {
                return buildFallbackMetadata(task);
            }

            try {
                String url = String.format(
                        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
                        config.geminiTextModel
                );

                String instruction = """
                        You are a global viral social media strategist for YouTube, Instagram Reels, X (Twitter), and Facebook.
                        Given this 4K AI video prompt, generate high-CTR worldwide trending metadata.
                        Return ONLY a JSON object with these exact keys:
- "title": catchy viral title under 70 characters
- "description": engaging 2-sentence global description
- "tags": array of 15 high-volume worldwide searchable tags (no # prefix)
- "hashtags": array of 10 global trending hashtags (each starting with #)

                        Video Prompt:
                        """ + task.promptText();

                Map<String, Object> requestBody = Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", instruction)))),
                        "generationConfig", Map.of(
                                "responseMimeType", "application/json",
                                "temperature", 0.7
                        )
                );

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", config.geminiApiKey)
                        .timeout(Duration.ofSeconds(45))
                        .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(requestBody)))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    Map<String, Object> root = MiniJson.parseObject(resp.body());
                    List<Object> candidates = MiniJson.asList(root.get("candidates"));
                    if (!candidates.isEmpty()) {
                        Map<String, Object> cand0 = MiniJson.asMap(candidates.get(0));
                        Map<String, Object> content = MiniJson.asMap(cand0.get("content"));
                        List<Object> parts = MiniJson.asList(content.get("parts"));
                        if (!parts.isEmpty()) {
                            String jsonText = MiniJson.asString(MiniJson.asMap(parts.get(0)).get("text"), "{}");
                            Map<String, Object> metaJson = MiniJson.parseObject(jsonText);

                            String title = MiniJson.asString(metaJson.get("title"), "");
                            String desc = MiniJson.asString(metaJson.get("description"), "");
                            List<String> tags = MiniJson.asStringList(metaJson.get("tags"));
                            List<String> hashtags = MiniJson.asStringList(metaJson.get("hashtags"));

                            if (!title.isBlank() && !tags.isEmpty()) {
                                return new ViralVideoMetadata(title, desc, tags, hashtags);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warning("Gemini trending metadata call failed, using deterministic viral tags: " + e.getMessage());
            }

            return buildFallbackMetadata(task);
        }

        private ViralVideoMetadata buildFallbackMetadata(PromptTask task) {
            String cleanSnippet = task.promptText().length() > 54
                    ? task.promptText().substring(0, 54).trim() + "..."
                    : task.promptText();
            String title = "4K Ultra HD: " + cleanSnippet;
            String description = task.promptText() + "\n\nCreated in 4K Ultra HD with Gemini video AI.";
            List<String> tags = List.of(
                    "4K Video", "Gemini AI", "AI Cinematography", "Trending Worldwide",
                    "Viral Shorts", "Ultra HD 60fps", "Future Tech", "Visual Art",
                    "Aesthetic 4K", "Global Trends", "AI Filmmaking", "NextGen Video"
            );
            List<String> hashtags = List.of(
                    "#Trending", "#Viral", "#4K", "#GeminiAI",
                    "#AIVideo", "#UltraHD", "#Cinematography", "#Worldwide", "#FYP"
            );
            return new ViralVideoMetadata(title, description, tags, hashtags);
        }

        public Map<String, Object> enhanceIdeaTo4kPrompt(String rawIdea) {
            SchedulerConfig config = configRef.get();
            String cleanIdea = rawIdea == null ? "" : rawIdea.trim();
            if (cleanIdea.isEmpty()) {
                cleanIdea = "Futuristic bioluminescent floating city above ocean clouds at sunset";
            }

            if (!config.geminiApiKey.isBlank()) {
                try {
                    String url = String.format(
                            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
                            config.geminiTextModel
                    );
                    String instruction = """
                            You are an Academy Award-winning cinematography director specializing in 4K AI video generation.
                            Expand the user's short idea into a rich, hyper-detailed 4K prompt (50-85 words) specifying camera movement, lens (e.g. 35mm anamorphic), volumetric lighting, atmosphere, and 60fps photorealism (no on-screen text).
                            Return ONLY a JSON object with keys:
- "fileName": snake_case filename ending in .txt (e.g. "neon_cyber_harbor_4k.txt")
- "enhancedPrompt": the 4K prompt
- "predictedTrendingHashtags": array of 6 worldwide trending hashtags

                            User Idea:
                            """ + cleanIdea;

                    Map<String, Object> reqBody = Map.of(
                            "contents", List.of(Map.of("parts", List.of(Map.of("text", instruction)))),
                            "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.75)
                    );

                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .header("x-goog-api-key", config.geminiApiKey)
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(reqBody)))
                            .build();

                    HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() >= 200 && res.statusCode() < 300) {
                        Map<String, Object> root = MiniJson.parseObject(res.body());
                        List<Object> candidates = MiniJson.asList(root.get("candidates"));
                        if (!candidates.isEmpty()) {
                            Map<String, Object> content = MiniJson.asMap(MiniJson.asMap(candidates.get(0)).get("content"));
                            List<Object> parts = MiniJson.asList(content.get("parts"));
                            if (!parts.isEmpty()) {
                                String jsonText = MiniJson.asString(MiniJson.asMap(parts.get(0)).get("text"), "{}");
                                Map<String, Object> parsed = MiniJson.parseObject(jsonText);
                                if (parsed.containsKey("enhancedPrompt")) {
                                    return parsed;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warning("AI Prompt Enhancer API call failed, using deterministic director template: " + e.getMessage());
                }
            }

            String slug = cleanIdea.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            if (slug.length() > 36) {
                slug = slug.substring(0, 36);
            }
            if (slug.isEmpty()) {
                slug = "cinematic_scene";
            }

            String enhanced = "Ultra-detailed 4K IMAX 70mm cinematic tracking shot of " + cleanIdea
                    + ", captured on a 35mm anamorphic lens with volumetric golden-hour god rays, atmospheric depth, "
                    + "intricate surface textures, smooth Steadicam motion, natural color grading, photorealistic 60fps.";

            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("fileName", slug + "_4k.txt");
            fallback.put("enhancedPrompt", enhanced);
            fallback.put("predictedTrendingHashtags", List.of("#4K", "#GeminiAI", "#Cinematic", "#Trending", "#Viral"));
            return fallback;
        }
    }

    public static final class GeminiVeo3Client {
        private static final Pattern RETRY_DELAY_PATTERN =
                Pattern.compile("\"retryDelay\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+)?)s\"");

        private final AtomicReference<SchedulerConfig> configRef;
        private final HttpClient httpClient;

        public GeminiVeo3Client(AtomicReference<SchedulerConfig> configRef, HttpClient httpClient) {
            this.configRef = configRef;
            this.httpClient = httpClient;
        }

        public static final class RateLimitException extends IOException {
            private final long retryAfterSeconds;

            public RateLimitException(String message, long retryAfterSeconds) {
                super(message);
                this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
            }

            public long getRetryAfterSeconds() {
                return retryAfterSeconds;
            }
        }

        private static long extractRetryDelaySeconds(String responseBody) {
            if (responseBody == null || responseBody.isBlank()) {
                return 60L;
            }
            Matcher matcher = RETRY_DELAY_PATTERN.matcher(responseBody);
            if (matcher.find()) {
                try {
                    double seconds = Double.parseDouble(matcher.group(1));
                    return Math.max(1L, (long) Math.ceil(seconds));
                } catch (NumberFormatException ignored) {
                    return 60L;
                }
            }
            return 60L;
        }

        public Path generate4kVideo(PromptTask task) throws Exception {
            SchedulerConfig config = configRef.get();
            Files.createDirectories(config.outputVideosDirectory);

            String baseName = task.fileName().replaceAll("\\.[^.]+$", "");
            Path outputFile = config.outputVideosDirectory.resolve(baseName + "_4k_" + System.currentTimeMillis() + ".mp4");

            if (config.geminiApiKey.isBlank()) {
                LOG.warning("GEMINI_API_KEY is not set. Creating simulated 4K MP4 placeholder at: " + outputFile);
                writeDryRunPlaceholderMp4(outputFile, task);
                return outputFile;
            }

            LOG.info("Submitting 4K video generation job via :generateContent (" + config.veoModel + ", resolution=" + config.videoResolution + ")...");
            if (tryGenerateContentVideo(task, config, outputFile)) {
                LOG.info("4K Video successfully saved via :generateContent to: " + outputFile.toAbsolutePath()
                        + " (" + Files.size(outputFile) + " bytes)");
                return outputFile;
            }

            LOG.info("Falling back to :predictLongRunning for model " + config.veoModel + "...");
            generateViaLongRunningPrediction(task, config, outputFile);
            LOG.info("4K Video successfully saved to: " + outputFile.toAbsolutePath()
                    + " (" + Files.size(outputFile) + " bytes)");
            return outputFile;
        }

        private boolean tryGenerateContentVideo(PromptTask task, SchedulerConfig config, Path outputFile) throws Exception {
            String generateUrl = String.format(
                    "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
                    config.veoModel
            );

            String promptWithSpecs = String.format(
                    "Generate a video (%s, %s aspect ratio, %ds duration): %s",
                    config.videoResolution, config.videoAspectRatio, config.videoDurationSeconds, task.promptText()
            );

            Map<String, Object> payload = Map.of(
                    "contents", List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", promptWithSpecs))
                    ))
            );

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(generateUrl))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", config.geminiApiKey)
                    .timeout(Duration.ofMinutes(4))
                    .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(payload)))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                long retrySec = extractRetryDelaySeconds(resp.body());
                throw new RateLimitException("Gemini :generateContent returned HTTP 429: " + resp.body(), retrySec);
            }
            if (resp.statusCode() == 404) {
                LOG.warning("Model " + config.veoModel + " not found on :generateContent (HTTP 404). Trying :predictLongRunning.");
                return false;
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("Gemini :generateContent returned HTTP " + resp.statusCode() + ": " + resp.body());
            }

            Map<String, Object> root = MiniJson.parseObject(resp.body());
            List<Object> candidates = MiniJson.asList(root.get("candidates"));
            for (Object candObj : candidates) {
                Map<String, Object> content = MiniJson.asMap(MiniJson.asMap(candObj).get("content"));
                for (Object partObj : MiniJson.asList(content.get("parts"))) {
                    Map<String, Object> part = MiniJson.asMap(partObj);

                    Map<String, Object> inlineData = MiniJson.asMap(part.get("inlineData"));
                    String mimeType = MiniJson.asString(inlineData.get("mimeType"), "");
                    String b64Data = MiniJson.asString(inlineData.get("data"), "");
                    if (!b64Data.isBlank() && (mimeType.startsWith("video/") || mimeType.isBlank())) {
                        Files.write(outputFile, Base64.getDecoder().decode(b64Data));
                        return true;
                    }

                    Map<String, Object> fileData = MiniJson.asMap(part.get("fileData"));
                    String fileUri = MiniJson.asString(fileData.get("fileUri"), "");
                    if (!fileUri.isBlank()) {
                        downloadVideo(fileUri, config.geminiApiKey, outputFile);
                        return true;
                    }
                }
            }

            throw new IOException("Gemini :generateContent responded with HTTP " + resp.statusCode()
                    + " but contained no video inlineData or fileData: " + resp.body());
        }

        private void generateViaLongRunningPrediction(PromptTask task, SchedulerConfig config, Path outputFile) throws Exception {
            String predictUrl = String.format(
                    "https://generativelanguage.googleapis.com/v1beta/models/%s:predictLongRunning",
                    config.veoModel
            );

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("aspectRatio", config.videoAspectRatio);
            parameters.put("resolution", config.videoResolution);
            parameters.put("durationSeconds", config.videoDurationSeconds);
            parameters.put("personGeneration", "allow_adult");

            Map<String, Object> payload = Map.of(
                    "instances", List.of(Map.of("prompt", task.promptText())),
                    "parameters", parameters
            );

            HttpRequest startReq = HttpRequest.newBuilder()
                    .uri(URI.create(predictUrl))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", config.geminiApiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(payload)))
                    .build();

            HttpResponse<String> startResp = httpClient.send(startReq, HttpResponse.BodyHandlers.ofString());
            if (startResp.statusCode() == 429) {
                long retrySec = extractRetryDelaySeconds(startResp.body());
                throw new RateLimitException("Gemini Veo API returned HTTP 429: " + startResp.body(), retrySec);
            }
            if (startResp.statusCode() < 200 || startResp.statusCode() >= 300) {
                throw new IOException("Gemini Veo API returned HTTP " + startResp.statusCode() + ": " + startResp.body());
            }

            Map<String, Object> opJson = MiniJson.parseObject(startResp.body());
            String operationName = MiniJson.asString(opJson.get("name"), "");
            if (operationName.isBlank()) {
                throw new IOException("Veo API did not return an operation name: " + startResp.body());
            }

            LOG.info("Long-running operation started: " + operationName);

            String pollUrl = "https://generativelanguage.googleapis.com/v1beta/" + operationName;
            Instant deadline = Instant.now().plus(Duration.ofMinutes(config.veoMaxWaitMinutes));
            Map<String, Object> completedOp = null;
            int pollAttempt = 0;

            while (Instant.now().isBefore(deadline)) {
                Thread.sleep(config.veoPollIntervalSeconds * 1000L);
                pollAttempt++;
                int simulatedPct = Math.min(72, 35 + (pollAttempt * 4));
                PIPELINE_TRACKER.update("VEO3_4K_RENDER", simulatedPct, task.fileName(),
                        "Synthesizing 4K frames (poll #" + pollAttempt + ")...");

                HttpRequest pollReq = HttpRequest.newBuilder()
                        .uri(URI.create(pollUrl))
                        .header("x-goog-api-key", config.geminiApiKey)
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();

                HttpResponse<String> pollResp = httpClient.send(pollReq, HttpResponse.BodyHandlers.ofString());
                if (pollResp.statusCode() == 429) {
                    long retrySec = extractRetryDelaySeconds(pollResp.body());
                    throw new RateLimitException("Failed polling operation (HTTP 429): " + pollResp.body(), retrySec);
                }
                if (pollResp.statusCode() < 200 || pollResp.statusCode() >= 300) {
                    throw new IOException("Failed polling operation (" + pollResp.statusCode() + "): " + pollResp.body());
                }

                Map<String, Object> statusJson = MiniJson.parseObject(pollResp.body());
                if (statusJson.containsKey("error")) {
                    String errBody = MiniJson.stringify(statusJson.get("error"));
                    if (errBody.contains("\"code\":429") || errBody.contains("RESOURCE_EXHAUSTED")) {
                        throw new RateLimitException("Video generation rate/quota error: " + errBody, extractRetryDelaySeconds(errBody));
                    }
                    throw new IOException("Video generation error: " + errBody);
                }

                if (MiniJson.asBoolean(statusJson.get("done"), false)) {
                    completedOp = statusJson;
                    break;
                }
                LOG.info("4K render still in progress...");
            }

            if (completedOp == null) {
                throw new IOException("Timed out waiting for 4K video after " + config.veoMaxWaitMinutes + " minutes.");
            }

            Map<String, Object> responseObj = MiniJson.asMap(completedOp.get("response"));
            String videoUri = extractVideoUriOrBase64(responseObj, outputFile);
            if (videoUri != null && !videoUri.isBlank()) {
                downloadVideo(videoUri, config.geminiApiKey, outputFile);
            }
        }

        private String extractVideoUriOrBase64(Map<String, Object> responseObj, Path outputFile) throws IOException {
            Map<String, Object> genVidResp = MiniJson.asMap(responseObj.get("generateVideoResponse"));
            List<Object> samples = MiniJson.asList(genVidResp.get("generatedSamples"));
            if (!samples.isEmpty()) {
                Map<String, Object> videoObj = MiniJson.asMap(MiniJson.asMap(samples.get(0)).get("video"));
                String uri = MiniJson.asString(videoObj.get("uri"), "");
                if (!uri.isBlank()) {
                    return uri;
                }
            }

            List<Object> genVideos = MiniJson.asList(responseObj.get("generatedVideos"));
            if (!genVideos.isEmpty()) {
                Map<String, Object> videoObj = MiniJson.asMap(MiniJson.asMap(genVideos.get(0)).get("video"));
                String uri = MiniJson.asString(videoObj.get("uri"), "");
                if (!uri.isBlank()) {
                    return uri;
                }
            }

            List<Object> predictions = MiniJson.asList(responseObj.get("predictions"));
            if (!predictions.isEmpty()) {
                Map<String, Object> pred0 = MiniJson.asMap(predictions.get(0));
                String b64 = MiniJson.asString(pred0.get("bytesBase64Encoded"), "");
                if (!b64.isBlank()) {
                    byte[] videoBytes = Base64.getDecoder().decode(b64);
                    Files.write(outputFile, videoBytes);
                    return null;
                }
            }

            throw new IOException("Completed operation did not return a video URI or base64 payload.");
        }

        private void downloadVideo(String videoUri, String apiKey, Path outputPath) throws Exception {
            HttpRequest downloadReq = HttpRequest.newBuilder()
                    .uri(URI.create(videoUri))
                    .header("x-goog-api-key", apiKey)
                    .timeout(Duration.ofMinutes(3))
                    .GET()
                    .build();

            HttpResponse<Path> fileResp = httpClient.send(downloadReq, HttpResponse.BodyHandlers.ofFile(outputPath));
            if (fileResp.statusCode() < 200 || fileResp.statusCode() >= 300) {
                throw new IOException("Failed downloading 4K MP4 from URI: HTTP " + fileResp.statusCode());
            }
        }

        private void writeDryRunPlaceholderMp4(Path outputPath, PromptTask task) throws IOException {
            byte[] ftypHeader = new byte[]{
                    0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
                    0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00,
                    0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32
            };
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(ftypHeader);
            out.write(("SIMULATED_4K_MP4:" + task.fileName()).getBytes(StandardCharsets.UTF_8));
            Files.write(outputPath, out.toByteArray());
        }
    }

    public record PlatformPublishResult(String platform, boolean success, String details) {}

    public static final class SocialMediaPublisherOrchestrator {
        private final AtomicReference<SchedulerConfig> configRef;
        private final HttpClient httpClient;

        public SocialMediaPublisherOrchestrator(AtomicReference<SchedulerConfig> configRef, HttpClient httpClient) {
            this.configRef = configRef;
            this.httpClient = httpClient;
        }

        public List<PlatformPublishResult> publishToAllPlatforms(Path videoFile, ViralVideoMetadata metadata) {
            SchedulerConfig config = configRef.get();
            LOG.info("Starting worldwide multi-platform distribution for: \"" + metadata.title() + "\"");
            LOG.info("Trending Tags: " + String.join(", ", metadata.tags()));

            List<CompletableFuture<PlatformPublishResult>> tasks = new ArrayList<>();

            if (config.youtubeEnabled) {
                tasks.add(CompletableFuture.supplyAsync(() -> publishToYouTube(videoFile, metadata, config)));
            }
            if (config.instagramEnabled) {
                tasks.add(CompletableFuture.supplyAsync(() -> publishToInstagramReels(videoFile, metadata, config)));
            }
            if (config.twitterEnabled) {
                tasks.add(CompletableFuture.supplyAsync(() -> publishToTwitterX(videoFile, metadata, config)));
            }
            if (config.facebookEnabled) {
                tasks.add(CompletableFuture.supplyAsync(() -> publishToFacebookPage(videoFile, metadata, config)));
            }

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

            List<PlatformPublishResult> results = new ArrayList<>();
            for (CompletableFuture<PlatformPublishResult> future : tasks) {
                PlatformPublishResult res = future.join();
                results.add(res);
                if (res.success()) {
                    LOG.info(String.format("  [SUCCESS] %-10s -> %s", res.platform(), res.details()));
                } else {
                    LOG.warning(String.format("  [FAILED]  %-10s -> %s", res.platform(), res.details()));
                }
            }
            return results;
        }

        private PlatformPublishResult publishToYouTube(Path videoFile, ViralVideoMetadata meta, SchedulerConfig cfg) {
            if (cfg.youtubeAccessToken.isBlank()) {
                return new PlatformPublishResult("YouTube", true,
                        "Dry-run validated (set YOUTUBE_ACCESS_TOKEN for live upload). Tags: " + meta.tags().size());
            }

            try {
                String boundary = "veo3_yt_boundary_" + UUID.randomUUID();
                Map<String, Object> ytMetadata = Map.of(
                        "snippet", Map.of(
                                "title", meta.title(),
                                "description", meta.description() + "\n\n" + String.join(" ", meta.hashtags()),
                                "tags", meta.tags(),
                                "categoryId", cfg.youtubeCategoryId
                        ),
                        "status", Map.of(
                                "privacyStatus", cfg.youtubePrivacyStatus,
                                "selfDeclaredMadeForKids", false
                        )
                );

                byte[] videoBytes = Files.readAllBytes(videoFile);
                ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
                bodyStream.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                        + MiniJson.stringify(ytMetadata) + "\r\n").getBytes(StandardCharsets.UTF_8));
                bodyStream.write(("--" + boundary + "\r\nContent-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                bodyStream.write(videoBytes);
                bodyStream.write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=multipart&part=snippet,status"))
                        .header("Authorization", "Bearer " + cfg.youtubeAccessToken)
                        .header("Content-Type", "multipart/related; boundary=" + boundary)
                        .timeout(Duration.ofMinutes(5))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyStream.toByteArray()))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    Map<String, Object> json = MiniJson.parseObject(resp.body());
                    String videoId = MiniJson.asString(json.get("id"), "unknown");
                    return new PlatformPublishResult("YouTube", true, "https://www.youtube.com/watch?v=" + videoId);
                }
                return new PlatformPublishResult("YouTube", false, "HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (Exception e) {
                return new PlatformPublishResult("YouTube", false, e.getMessage());
            }
        }

        private PlatformPublishResult publishToInstagramReels(Path videoFile, ViralVideoMetadata meta, SchedulerConfig cfg) {
            if (cfg.instagramAccessToken.isBlank() || cfg.instagramBusinessAccountId.isBlank()) {
                return new PlatformPublishResult("Instagram", true,
                        "Dry-run validated (set INSTAGRAM_BUSINESS_ACCOUNT_ID & INSTAGRAM_ACCESS_TOKEN).");
            }

            try {
                String caption = URLEncoder.encode(
                        meta.title() + "\n\n" + meta.description() + "\n\n" + String.join(" ", meta.hashtags()),
                        StandardCharsets.UTF_8
                );
                String createContainerUrl = String.format(
                        "https://graph.facebook.com/v21.0/%s/media?media_type=REELS&upload_type=resumable&caption=%s&access_token=%s",
                        cfg.instagramBusinessAccountId, caption, URLEncoder.encode(cfg.instagramAccessToken, StandardCharsets.UTF_8)
                );

                HttpRequest createReq = HttpRequest.newBuilder()
                        .uri(URI.create(createContainerUrl))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
                if (createResp.statusCode() < 200 || createResp.statusCode() >= 300) {
                    return new PlatformPublishResult("Instagram", false, "Container error: " + createResp.body());
                }

                Map<String, Object> containerJson = MiniJson.parseObject(createResp.body());
                String creationId = MiniJson.asString(containerJson.get("id"), "");
                String uploadUri = MiniJson.asString(containerJson.get("uri"), "https://rupload.facebook.com/ig-api-upload/v21.0/" + creationId);

                byte[] videoBytes = Files.readAllBytes(videoFile);
                HttpRequest uploadReq = HttpRequest.newBuilder()
                        .uri(URI.create(uploadUri))
                        .header("Authorization", "OAuth " + cfg.instagramAccessToken)
                        .header("offset", "0")
                        .header("file_size", String.valueOf(videoBytes.length))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(videoBytes))
                        .build();
                httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());

                String publishUrl = String.format(
                        "https://graph.facebook.com/v21.0/%s/media_publish?creation_id=%s&access_token=%s",
                        cfg.instagramBusinessAccountId, creationId, URLEncoder.encode(cfg.instagramAccessToken, StandardCharsets.UTF_8)
                );
                HttpResponse<String> pubResp = httpClient.send(
                        HttpRequest.newBuilder().uri(URI.create(publishUrl)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                if (pubResp.statusCode() >= 200 && pubResp.statusCode() < 300) {
                    return new PlatformPublishResult("Instagram", true, "Reel Media ID: " + creationId);
                }
                return new PlatformPublishResult("Instagram", false, "Publish error: " + pubResp.body());
            } catch (Exception e) {
                return new PlatformPublishResult("Instagram", false, e.getMessage());
            }
        }

        private PlatformPublishResult publishToTwitterX(Path videoFile, ViralVideoMetadata meta, SchedulerConfig cfg) {
            if (cfg.twitterBearerOrOAuth2UserToken.isBlank()) {
                return new PlatformPublishResult("X/Twitter", true,
                        "Dry-run validated (set TWITTER_USER_ACCESS_TOKEN for live X video post).");
            }

            try {
                byte[] videoBytes = Files.readAllBytes(videoFile);

                String initUrl = "https://upload.twitter.com/1.1/media/upload.json?command=INIT&total_bytes="
                        + videoBytes.length + "&media_type=video/mp4&media_category=tweet_video";
                HttpResponse<String> initResp = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(initUrl))
                                .header("Authorization", "Bearer " + cfg.twitterBearerOrOAuth2UserToken)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (initResp.statusCode() < 200 || initResp.statusCode() >= 300) {
                    return new PlatformPublishResult("X/Twitter", false, "INIT failed: " + initResp.body());
                }

                String mediaId = MiniJson.asString(MiniJson.parseObject(initResp.body()).get("media_id_string"), "");

                String boundary = "veo3_x_boundary_" + UUID.randomUUID();
                ByteArrayOutputStream appendBody = new ByteArrayOutputStream();
                appendBody.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"media\"; filename=\"video.mp4\"\r\nContent-Type: video/mp4\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                appendBody.write(videoBytes);
                appendBody.write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));

                String appendUrl = "https://upload.twitter.com/1.1/media/upload.json?command=APPEND&media_id="
                        + mediaId + "&segment_index=0";
                httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(appendUrl))
                                .header("Authorization", "Bearer " + cfg.twitterBearerOrOAuth2UserToken)
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(appendBody.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                String finUrl = "https://upload.twitter.com/1.1/media/upload.json?command=FINALIZE&media_id=" + mediaId;
                httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(finUrl))
                                .header("Authorization", "Bearer " + cfg.twitterBearerOrOAuth2UserToken)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                String tweetText = meta.title() + " " + String.join(" ", meta.hashtags());
                if (tweetText.length() > 275) {
                    tweetText = tweetText.substring(0, 275);
                }
                Map<String, Object> tweetPayload = Map.of(
                        "text", tweetText,
                        "media", Map.of("media_ids", List.of(mediaId))
                );

                HttpResponse<String> tweetResp = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://api.twitter.com/2/tweets"))
                                .header("Authorization", "Bearer " + cfg.twitterBearerOrOAuth2UserToken)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(tweetPayload)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                if (tweetResp.statusCode() >= 200 && tweetResp.statusCode() < 300) {
                    return new PlatformPublishResult("X/Twitter", true, "Tweet published with media_id=" + mediaId);
                }
                return new PlatformPublishResult("X/Twitter", false, "Tweet error: " + tweetResp.body());
            } catch (Exception e) {
                return new PlatformPublishResult("X/Twitter", false, e.getMessage());
            }
        }

        private PlatformPublishResult publishToFacebookPage(Path videoFile, ViralVideoMetadata meta, SchedulerConfig cfg) {
            if (cfg.facebookPageAccessToken.isBlank() || cfg.facebookPageId.isBlank()) {
                return new PlatformPublishResult("Facebook", true,
                        "Dry-run validated (set FACEBOOK_PAGE_ID & FACEBOOK_PAGE_ACCESS_TOKEN).");
            }

            try {
                String boundary = "veo3_fb_boundary_" + UUID.randomUUID();
                byte[] videoBytes = Files.readAllBytes(videoFile);
                String fullDesc = meta.description() + "\n\n" + String.join(" ", meta.hashtags());

                ByteArrayOutputStream body = new ByteArrayOutputStream();
                writeFormField(body, boundary, "access_token", cfg.facebookPageAccessToken);
                writeFormField(body, boundary, "title", meta.title());
                writeFormField(body, boundary, "description", fullDesc);
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"source\"; filename=\""
                        + videoFile.getFileName() + "\"\r\nContent-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(videoBytes);
                body.write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));

                String fbUrl = String.format("https://graph-video.facebook.com/v21.0/%s/videos", cfg.facebookPageId);
                HttpResponse<String> resp = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(fbUrl))
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .timeout(Duration.ofMinutes(4))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    String fbVideoId = MiniJson.asString(MiniJson.parseObject(resp.body()).get("id"), "unknown");
                    return new PlatformPublishResult("Facebook", true, "Facebook Video ID: " + fbVideoId);
                }
                return new PlatformPublishResult("Facebook", false, "HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (Exception e) {
                return new PlatformPublishResult("Facebook", false, e.getMessage());
            }
        }

        private void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                    + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class VideoPipelineScheduler {
        private final AtomicReference<SchedulerConfig> configRef;
        private final UsageQuotaManager quotaManager;
        private final ResourcePromptRepository promptRepository;
        private final TrendingMetadataService metadataService;
        private final GeminiVeo3Client veo3Client;
        private final SocialMediaPublisherOrchestrator socialOrchestrator;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        private final AtomicBoolean jobInProgress = new AtomicBoolean(false);

        public VideoPipelineScheduler(
                AtomicReference<SchedulerConfig> configRef,
                UsageQuotaManager quotaManager,
                ResourcePromptRepository promptRepository,
                TrendingMetadataService metadataService,
                GeminiVeo3Client veo3Client,
                SocialMediaPublisherOrchestrator socialOrchestrator
        ) {
            this.configRef = configRef;
            this.quotaManager = quotaManager;
            this.promptRepository = promptRepository;
            this.metadataService = metadataService;
            this.veo3Client = veo3Client;
            this.socialOrchestrator = socialOrchestrator;
        }

        public void start() {
            SchedulerConfig cfg = configRef.get();
            LOG.info("==================================================================");
            LOG.info("Starting GPVScheduler (Video Pipeline)");
            LOG.info("  Enabled: " + cfg.videoCreationEnabled
                    + " | User Timezone: " + cfg.userTimeZone
                    + " | Window: " + cfg.allowedStartTime + " - " + cfg.allowedEndTime);
            LOG.info("  Limits : Max " + cfg.maxVideosPerDay + "/day, Max " + cfg.maxVideosPerHour
                    + "/hour, Cooldown " + cfg.minCooldownMinutesBetweenVideos + "m, Budget $" + cfg.maxDailyBudgetUsd);
            LOG.info("  Prompts: " + cfg.promptsDirectory.toAbsolutePath());
            LOG.info("==================================================================");

            executor.scheduleWithFixedDelay(
                    this::runScheduledCheck,
                    0,
                    cfg.schedulerPollIntervalSeconds,
                    TimeUnit.SECONDS
            );
        }

        public void stop() {
            executor.shutdownNow();
        }

        public void triggerImmediateCheck() {
            quotaManager.clearRateLimitBackoff();
            executor.submit(this::runScheduledCheck);
        }

        public boolean isJobInProgress() {
            return jobInProgress.get();
        }

        private void runScheduledCheck() {
            if (!jobInProgress.compareAndSet(false, true)) {
                return;
            }
            SchedulerConfig config = configRef.get();
            try {
                PIPELINE_TRACKER.update("WINDOW_CHECK", 5, "", "Evaluating user time window & daily/hourly quotas...");
                EligibilityResult eligibility = quotaManager.evaluateEligibility();
                if (!eligibility.allowed()) {
                    LOG.info("Scheduler Standby: " + eligibility.reason());
                    PIPELINE_TRACKER.update("STANDBY", 0, "", eligibility.reason());
                    return;
                }

                Optional<PromptTask> nextTaskOpt = promptRepository.fetchNextUnprocessedPrompt(quotaManager);
                if (nextTaskOpt.isEmpty()) {
                    LOG.info("Scheduler Idle: All prompt files in " + config.promptsDirectory + " have been processed.");
                    PIPELINE_TRACKER.update("STANDBY", 0, "", "Queue empty — all prompts in resources/prompts completed.");
                    return;
                }

                PromptTask task = nextTaskOpt.get();
                LOG.info("------------------------------------------------------------------");
                LOG.info("TRIGGERED: " + eligibility.reason());
                LOG.info("Processing Prompt File [1-by-1]: " + task.fileName());
                LOG.info("Prompt Content: \"" + task.promptText() + "\"");

                PIPELINE_TRACKER.update("TRENDING_AI", 20, task.fileName(),
                        "Generating worldwide trending tags & viral metadata via Gemini...");
                ViralVideoMetadata metadata = metadataService.generateWorldwideTrendingMetadata(task);

                PIPELINE_TRACKER.update("VEO3_4K_RENDER", 35, task.fileName(),
                        "Submitting 4K render job (" + config.veoModel + ")...");
                Path generated4kVideo = veo3Client.generate4kVideo(task);

                PIPELINE_TRACKER.update("GLOBAL_PUBLISH", 82, task.fileName(),
                        "Broadcasting 4K MP4 & trending tags to YouTube, Instagram, X & Facebook...");
                List<PlatformPublishResult> publishResults = socialOrchestrator.publishToAllPlatforms(generated4kVideo, metadata);

                quotaManager.recordSuccessfulVideoRun(task.fileName());
                PIPELINE_TRACKER.recordBroadcast(task.fileName(), generated4kVideo.getFileName().toString(), metadata, publishResults);
                PIPELINE_TRACKER.update("COMPLETED", 100, task.fileName(),
                        "Completed 4K render & global social broadcast for " + task.fileName());
                LOG.info("Completed full pipeline for prompt: " + task.fileName());
                LOG.info("------------------------------------------------------------------");

            } catch (GeminiVeo3Client.RateLimitException rle) {
                Instant resetInstant = quotaManager.recordRateLimitBackoff(rle.getRetryAfterSeconds());
                String resetInZone = formatInstantInZone(resetInstant, config.userTimeZone);
                PIPELINE_TRACKER.update("STANDBY", 0, "",
                        "Rate/quota limit (429): next eligible run at " + resetInZone
                                + " (" + rle.getRetryAfterSeconds() + "s).");
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Error during scheduled video pipeline execution", e);
                PIPELINE_TRACKER.update("STANDBY", 0, "", "Pipeline error: " + e.getMessage());
            } finally {
                jobInProgress.set(false);
            }
        }
    }

    public static final class SchedulerApiServer {
        private final AtomicReference<SchedulerConfig> configRef;
        private final UsageQuotaManager quotaManager;
        private final ResourcePromptRepository promptRepository;
        private final TrendingMetadataService metadataService;
        private final VideoPipelineScheduler scheduler;
        private HttpServer server;

        public SchedulerApiServer(
                AtomicReference<SchedulerConfig> configRef,
                UsageQuotaManager quotaManager,
                ResourcePromptRepository promptRepository,
                TrendingMetadataService metadataService,
                VideoPipelineScheduler scheduler
        ) {
            this.configRef = configRef;
            this.quotaManager = quotaManager;
            this.promptRepository = promptRepository;
            this.metadataService = metadataService;
            this.scheduler = scheduler;
        }

        public void start() throws IOException {
            int desiredPort = configRef.get().apiServerPort;
            int boundPort = desiredPort;
            IOException lastEx = null;

            for (int offset = 0; offset < 10; offset++) {
                try {
                    boundPort = desiredPort + offset;
                    server = HttpServer.create(new InetSocketAddress(boundPort), 0);
                    break;
                } catch (IOException ex) {
                    lastEx = ex;
                }
            }
            if (server == null) {
                throw lastEx != null ? lastEx : new IOException("Could not bind SchedulerApiServer");
            }

            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", this::handleRootUi);
            server.createContext("/api/events", this::handleSseStream);
            server.createContext("/api/status", this::handleStatus);
            server.createContext("/api/config", this::handleConfig);
            server.createContext("/api/prompts", this::handlePromptsList);
            server.createContext("/api/prompts/upload", this::handlePromptUpload);
            server.createContext("/api/prompts/enhance", this::handlePromptEnhance);
            server.createContext("/api/prompts/reorder", this::handlePromptReorder);
            server.createContext("/api/prompts/requeue", this::handlePromptRequeue);
            server.createContext("/api/prompts/delete", this::handlePromptDelete);
            server.createContext("/api/videos/stream/", this::handleVideoStream);
            server.createContext("/api/scheduler/trigger", this::handleTriggerRun);

            server.start();
            LOG.info("GPVScheduler REST API, SSE Stream & Mission Control listening on http://localhost:" + boundPort);
        }

        public void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private void handleRootUi(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, Map.of("error", "Not Found"));
                return;
            }
            Path dashboardHtml = Paths.get("scheduler-dashboard.html");
            if (Files.exists(dashboardHtml)) {
                byte[] htmlBytes = Files.readAllBytes(dashboardHtml);
                addCorsHeaders(exchange.getResponseHeaders());
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, htmlBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(htmlBytes);
                }
                return;
            }
            sendJson(exchange, 200, Map.of(
                    "service", "GPVScheduler REST API",
                    "status", "RUNNING",
                    "hint", "Open scheduler-dashboard.html in your browser or place it in the working directory."
            ));
        }

        private void handleSseStream(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            addCorsHeaders(headers);
            headers.set("Content-Type", "text/event-stream; charset=UTF-8");
            headers.set("Cache-Control", "no-cache");
            headers.set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            OutputStream os = exchange.getResponseBody();
            String hello = "event: connected\ndata: " + MiniJson.stringify(PIPELINE_TRACKER.snapshot()) + "\n\n";
            os.write(hello.getBytes(StandardCharsets.UTF_8));
            os.flush();
            SSE_SUBSCRIBERS.add(os);
        }

        private void handleStatus(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            SchedulerConfig cfg = configRef.get();
            EligibilityResult eligibility = quotaManager.evaluateEligibility();
            List prompts = promptRepository.listAllPrompts(quotaManager);
            List<?> rawPrompts = promptRepository.listAllPrompts(quotaManager);
            long queuedCount = rawPrompts.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> (Map<?, ?>) item)
                    .filter(m -> "QUEUED".equals(m.get("status")))
                    .count();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serverTime", Instant.now().toString());
            payload.put("userLocalTime", ZonedDateTime.now(cfg.userTimeZone).format(ZONE_DATE_TIME_FORMATTER));
            payload.put("userHour24", ZonedDateTime.now(cfg.userTimeZone).getHour());
            payload.put("userMinute", ZonedDateTime.now(cfg.userTimeZone).getMinute());
            payload.put("videoCreationEnabled", cfg.videoCreationEnabled);
            payload.put("jobInProgress", scheduler.isJobInProgress());
            payload.put("pipelineStage", PIPELINE_TRACKER.snapshot());
            payload.put("recentBroadcasts", PIPELINE_TRACKER.getRecentBroadcasts());
            payload.put("eligibility", Map.of("allowed", eligibility.allowed(), "reason", eligibility.reason()));
            payload.put("quota", quotaManager.getQuotaSnapshot());
            payload.put("promptsSummary", Map.of(
                    "total", prompts.size(),
                    "queued", queuedCount,
                    "completed", prompts.size() - queuedCount
            ));
            payload.put("generatedVideos", listGeneratedVideos(cfg.outputVideosDirectory));
            payload.put("recentLogs", new ArrayList<>(RECENT_LOGS));

            sendJson(exchange, 200, payload);
        }

        private void handleConfig(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if ("GET".equals(method)) {
                sendJson(exchange, 200, configRef.get().toSafeMap());
                return;
            }
            if ("POST".equals(method)) {
                try {
                    String body = readRequestBody(exchange);
                    Map<String, Object> incoming = MiniJson.parseObject(body);
                    SchedulerConfig updated = configRef.get().mergeFromJson(incoming);
                    updated.saveToPropertiesFile();
                    configRef.set(updated);
                    LOG.info("Hot-reloaded scheduler configuration via REST API.");
                    sendJson(exchange, 200, Map.of(
                            "saved", true,
                            "config", updated.toSafeMap()
                    ));
                } catch (Exception e) {
                    sendJson(exchange, 400, Map.of("error", "Invalid configuration payload: " + e.getMessage()));
                }
                return;
            }
            sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
        }

        private void handlePromptsList(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            List prompts = promptRepository.listAllPrompts(quotaManager);
            sendJson(exchange, 200, Map.of(
                    "promptsDirectory", configRef.get().promptsDirectory.toString(),
                    "prompts", prompts
            ));
        }

        private void handlePromptUpload(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "POST required"));
                return;
            }
            try {
                String body = readRequestBody(exchange);
                Map<String, Object> payload = MiniJson.parseObject(body);
                List<String> savedFiles = new ArrayList<>();

                if (payload.containsKey("prompts")) {
                    for (Object itemObj : MiniJson.asList(payload.get("prompts"))) {
                        Map<String, Object> item = MiniJson.asMap(itemObj);
                        String fName = MiniJson.asString(item.get("fileName"), "");
                        String pText = MiniJson.asString(item.get("promptText"), "");
                        if (!pText.isBlank()) {
                            Path saved = promptRepository.saveOrUpdatePrompt(fName, pText);
                            savedFiles.add(saved.getFileName().toString());
                        }
                    }
                } else {
                    String fName = MiniJson.asString(payload.get("fileName"), "");
                    String pText = MiniJson.asString(payload.get("promptText"), "");
                    if (pText.isBlank()) {
                        sendJson(exchange, 400, Map.of("error", "promptText cannot be empty"));
                        return;
                    }
                    Path saved = promptRepository.saveOrUpdatePrompt(fName, pText);
                    savedFiles.add(saved.getFileName().toString());
                }

                LOG.info("Uploaded " + savedFiles.size() + " prompt file(s) to resources/prompts: " + savedFiles);
                sendJson(exchange, 200, Map.of(
                        "uploaded", savedFiles,
                        "prompts", promptRepository.listAllPrompts(quotaManager)
                ));
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of("error", e.getMessage()));
            }
        }

        private void handlePromptEnhance(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "POST required"));
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, Object> payload = MiniJson.parseObject(body);
            String idea = MiniJson.asString(payload.get("idea"), "");
            Map<String, Object> enhanced = metadataService.enhanceIdeaTo4kPrompt(idea);
            sendJson(exchange, 200, enhanced);
        }

        private void handlePromptReorder(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "POST required"));
                return;
            }
            try {
                String body = readRequestBody(exchange);
                Map<String, Object> payload = MiniJson.parseObject(body);
                List<String> orderedFiles = MiniJson.asStringList(payload.get("orderedFiles"));
                promptRepository.reorderPrompts(orderedFiles, quotaManager);
                sendJson(exchange, 200, Map.of(
                        "reordered", true,
                        "prompts", promptRepository.listAllPrompts(quotaManager)
                ));
            } catch (Exception e) {
                sendJson(exchange, 400, Map.of("error", e.getMessage()));
            }
        }

        private void handlePromptRequeue(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "POST required"));
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, Object> payload = MiniJson.parseObject(body);
            String fileName = MiniJson.asString(payload.get("fileName"), "*");
            quotaManager.requeuePrompt(fileName);
            LOG.info("Re-queued prompt(s): " + fileName);
            sendJson(exchange, 200, Map.of(
                    "requeued", fileName,
                    "prompts", promptRepository.listAllPrompts(quotaManager)
            ));
        }

        private void handlePromptDelete(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "POST required"));
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, Object> payload = MiniJson.parseObject(body);
            String fileName = MiniJson.asString(payload.get("fileName"), "");
            boolean deleted = promptRepository.deletePromptFile(fileName);
            quotaManager.requeuePrompt(fileName);
            LOG.info("Deleted prompt file: " + fileName + " (existed=" + deleted + ")");
            sendJson(exchange, 200, Map.of(
                    "deleted", deleted,
                    "prompts", promptRepository.listAllPrompts(quotaManager)
            ));
        }

        private void handleVideoStream(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            String rawPath = exchange.getRequestURI().getPath();
            String fileName = rawPath.substring("/api/videos/stream/".length());
            fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
            String safeName = Paths.get(fileName).getFileName().toString();

            Path outDir = configRef.get().outputVideosDirectory.normalize();
            Path videoPath = outDir.resolve(safeName).normalize();
            if (!videoPath.startsWith(outDir) || !Files.exists(videoPath) || !Files.isRegularFile(videoPath)) {
                sendJson(exchange, 404, Map.of("error", "Video file not found"));
                return;
            }

            addCorsHeaders(exchange.getResponseHeaders());
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, Files.size(videoPath));
            try (InputStream is = Files.newInputStream(videoPath);
                 OutputStream os = exchange.getResponseBody()) {
                is.transferTo(os);
            }
        }

        private void handleTriggerRun(HttpExchange exchange) throws IOException {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "POST required"));
                return;
            }
            scheduler.triggerImmediateCheck();
            sendJson(exchange, 200, Map.of(
                    "triggered", true,
                    "message", "Immediate scheduler cycle dispatched."
            ));
        }

        private List listGeneratedVideos(Path dir) {
            try {
                Files.createDirectories(dir);
                try (Stream<Path> files = Files.list(dir)) {
                    return files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4"))
                            .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                            .limit(20)
                            .map(p -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("fileName", p.getFileName().toString());
                                try {
                                    m.put("sizeBytes", Files.size(p));
                                    m.put("lastModified", Files.getLastModifiedTime(p).toInstant().toString());
                                } catch (IOException ignored) {
                                    m.put("sizeBytes", 0);
                                }
                                return m;
                            })
                            .toList();
                }
            } catch (IOException e) {
                return Collections.emptyList();
            }
        }

        private boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCorsHeaders(exchange.getResponseHeaders());
                exchange.sendResponseHeaders(204, -1);
                return true;
            }
            return false;
        }

        private void addCorsHeaders(Headers headers) {
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        }

        private String readRequestBody(HttpExchange exchange) throws IOException {
            try (InputStream is = exchange.getRequestBody()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
            byte[] bytes = MiniJson.stringify(payload).getBytes(StandardCharsets.UTF_8);
            addCorsHeaders(exchange.getResponseHeaders());
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    public static final class MiniJson {
        private final String src;
        private int pos;

        private MiniJson(String src) {
            this.src = src == null ? "{}" : src;
            this.pos = 0;
        }

        public static Map<String, Object> parseObject(String json) {
            MiniJson parser = new MiniJson(json);
            parser.skipWhitespace();
            if (parser.pos >= parser.src.length() || parser.src.charAt(parser.pos) != '{') {
                return new LinkedHashMap<>();
            }
            return parser.readObject();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (pos < src.length()) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ':') {
                    pos++;
                }
                skipWhitespace();
                Object val = readValue();
                map.put(key, val);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == '}') {
                    pos++;
                    break;
                } else {
                    break;
                }
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (pos < src.length()) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == ']') {
                    pos++;
                    break;
                } else {
                    break;
                }
            }
            return list;
        }

        private Object readValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                return null;
            }
            char c = src.charAt(pos);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't' && src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (c == 'f' && src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (c == 'n' && src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return readNumber();
        }

        private String readString() {
            if (pos >= src.length() || src.charAt(pos) != '"') {
                return "";
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 <= src.length()) {
                                String hex = src.substring(pos, pos + 4);
                                sb.append((char) Integer.parseInt(hex, 16));
                                pos += 4;
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number readNumber() {
            int start = pos;
            boolean isDouble = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') {
                    isDouble = true;
                }
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = src.substring(start, pos);
            try {
                return isDouble ? Double.parseDouble(token) : Long.parseLong(token);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        public static String stringify(Object obj) {
            if (obj == null) {
                return "null";
            }
            if (obj instanceof String s) {
                return "\"" + escape(s) + "\"";
            }
            if (obj instanceof Number || obj instanceof Boolean) {
                return obj.toString();
            }
            if (obj instanceof Map<?, ?> map) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(",");
                    }
                    first = false;
                    sb.append("\"").append(escape(String.valueOf(entry.getKey()))).append("\":");
                    sb.append(stringify(entry.getValue()));
                }
                return sb.append("}").toString();
            }
            if (obj instanceof Iterable<?> iterable) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) {
                        sb.append(",");
                    }
                    first = false;
                    sb.append(stringify(item));
                }
                return sb.append("]").toString();
            }
            return "\"" + escape(obj.toString()) + "\"";
        }

        private static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        public static Map<String, Object> asMap(Object obj) {
            if (obj instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return new LinkedHashMap<>();
        }

        @SuppressWarnings("unchecked")
        public static List<Object> asList(Object obj) {
            if (obj instanceof List<?> l) {
                return (List<Object>) l;
            }
            return new ArrayList<>();
        }

        public static String asString(Object obj, String def) {
            return obj == null ? def : String.valueOf(obj);
        }

        public static List<String> asStringList(Object obj) {
            List<String> out = new ArrayList<>();
            if (obj instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        out.add(String.valueOf(item));
                    }
                }
            }
            return out;
        }

        public static int asInt(Object obj, int def) {
            if (obj instanceof Number n) {
                return n.intValue();
            }
            if (obj instanceof String s && !s.isBlank()) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }

        public static double asDouble(Object obj, double def) {
            if (obj instanceof Number n) {
                return n.doubleValue();
            }
            if (obj instanceof String s && !s.isBlank()) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }

        public static boolean asBoolean(Object obj, boolean def) {
            if (obj instanceof Boolean b) {
                return b;
            }
            if (obj instanceof String s && !s.isBlank()) {
                return Boolean.parseBoolean(s.trim());
            }
            return def;
        }
    }
}