package com.streaming.stream.service;

import com.streaming.common.crypto.HashUtils;
import com.streaming.stream.api.dto.BroadcastPageResponse;
import com.streaming.stream.api.dto.CategoryCount;
import com.streaming.stream.api.dto.CategoryResponse;
import com.streaming.stream.api.dto.ChannelAboutResponse;
import com.streaming.stream.api.dto.ChannelHomeResponse;
import com.streaming.stream.api.dto.ChannelIdentityResponse;
import com.streaming.stream.api.dto.ChannelStats;
import com.streaming.stream.api.dto.CreateStreamRequest;
import com.streaming.stream.api.dto.LiveStreamPageResponse;
import com.streaming.stream.api.dto.PublishKeyResponse;
import com.streaming.stream.api.dto.SocialLink;
import com.streaming.stream.api.dto.StreamResponse;
import com.streaming.stream.api.dto.StreamSummaryResponse;
import com.streaming.stream.api.dto.UpdateProfileRequest;
import com.streaming.stream.api.dto.UpdateStreamRequest;
import com.streaming.stream.api.dto.WatchHistoryResponse;
import com.streaming.stream.api.dto.WatchResponse;
import com.streaming.stream.api.dto.StreamSseEvent;
import com.streaming.stream.config.PublishTokenProperties;
import com.streaming.stream.config.ViewCountProperties;
import com.streaming.stream.sse.SseConnectionRegistry;
import com.streaming.common.messaging.StreamEvent;
import com.streaming.stream.messaging.StreamEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import com.streaming.stream.persistence.entity.BroadcasterProfileEntity;
import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.entity.StreamStatus;
import com.streaming.stream.persistence.entity.WatchHistoryEntity;
import com.streaming.stream.persistence.query.BroadcastQueryBuilder;
import com.streaming.stream.persistence.repository.BroadcasterProfileRepository;
import com.streaming.stream.persistence.repository.StreamCategoryRepository;
import com.streaming.stream.persistence.repository.StreamSessionRepository;
import com.streaming.stream.persistence.repository.WatchHistoryRepository;
import com.streaming.stream.security.AuthAction;
import com.streaming.stream.security.AuthResourceDomain;
import java.time.Duration;
import com.streaming.stream.security.AuthResourceKind;
import com.streaming.stream.security.JwtAttr;
import com.streaming.stream.security.RequiredAuthority;
import com.streaming.stream.security.StreamAuthorization;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@Service
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);

    private final StreamSessionRepository repository;
    private final StreamCategoryRepository categoryRepository;
    private final BroadcasterProfileRepository profileRepository;
    private final WatchHistoryRepository watchHistoryRepository;
    private final StreamAuthorization authorization;
    private final StreamEventPublisher eventPublisher;
    private final OutboxWriter outboxWriter;
    private final PublishTokenService publishTokenService;
    private final PublishTokenProperties publishTokenProps;
    private final DatabaseClient databaseClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ViewCountProperties viewCountProperties;
    private final SseConnectionRegistry sseRegistry;

    public StreamService(StreamSessionRepository repository,
                         StreamCategoryRepository categoryRepository,
                         BroadcasterProfileRepository profileRepository,
                         WatchHistoryRepository watchHistoryRepository,
                         StreamAuthorization authorization,
                         StreamEventPublisher eventPublisher,
                         OutboxWriter outboxWriter,
                         PublishTokenService publishTokenService,
                         PublishTokenProperties publishTokenProps,
                         DatabaseClient databaseClient,
                         ReactiveRedisTemplate<String, String> redisTemplate,
                         ViewCountProperties viewCountProperties,
                         SseConnectionRegistry sseRegistry) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.profileRepository = profileRepository;
        this.watchHistoryRepository = watchHistoryRepository;
        this.authorization = authorization;
        this.eventPublisher = eventPublisher;
        this.outboxWriter = outboxWriter;
        this.publishTokenService = publishTokenService;
        this.publishTokenProps = publishTokenProps;
        this.databaseClient = databaseClient;
        this.redisTemplate = redisTemplate;
        this.viewCountProperties = viewCountProperties;
        this.sseRegistry = sseRegistry;
    }

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Create a new stream. If {@code scheduledAt} is provided, the stream is
     * created in {@code SCHEDULED} status with no publish key. Otherwise it
     * starts as {@code DRAFT} with a publish key (srsName + JWT token).
     */
    public Mono<StreamResponse> createStream(CreateStreamRequest request, Jwt jwt) {
        final String sub = jwt.getSubject();
        final String username = JwtAttr.username(jwt);
        final Boolean verified = JwtAttr.verifiedStreamer(jwt);
        final UUID id = UUID.randomUUID();
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return authorization.requireAccess(jwt, new RequiredAuthority(
                        AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                        AuthAction.CREATE, sub))
                .then(Mono.defer(() -> {
                    StreamSessionEntity entity = buildEntity(id, sub, username, verified,
                            request, now);
                    // Always generate srsName and streamKeyHash — even SCHEDULED
                    // streams need them (DB columns are NOT NULL). They'll be
                    // regenerated on SCHEDULED→DRAFT transition via goLive().
                    String srsName = newSrsName();
                    entity.setStreamKeyHash(HashUtils.sha256Hex(srsName));
                    entity.setSrsName(srsName);
                    if (request.scheduledAt() != null) {
                        entity.setStatus(StreamStatus.SCHEDULED);
                        entity.setScheduledAt(request.scheduledAt());
                    }
                    if (request.categoryId() != null) {
                        return categoryRepository.findById(request.categoryId())
                                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                        "Category not found: id=" + request.categoryId())))
                                .map(cat -> {
                                    entity.setCategoryId(cat.getId());
                                    entity.setCategory(cat.getName());
                                    return entity;
                                })
                                .flatMap(repository::save);
                    }
                    if (request.category() != null && !request.category().isBlank()) {
                        entity.setCategory(request.category());
                    }
                    return repository.save(entity);
                }))
                .flatMap(saved -> {
                    StreamEvent event = request.scheduledAt() != null
                            ? StreamEvent.scheduled(saved.getId(), sub,
                                    saved.getBroadcasterUsername())
                            : StreamEvent.created(saved.getId(), sub,
                                    saved.getBroadcasterUsername());
                    return outboxWriter.write(event)
                            .doOnSuccess(oe -> log.info(
                                    "Stream created: id={} status={} subject={}",
                                    saved.getId(), saved.getStatus().wireValue(), sub))
                            .thenReturn(saved);
                })
                .map(StreamResponse::from);
    }

    // ── Read ────────────────────────────────────────────────────────────────

    public Flux<StreamSummaryResponse> listMyStreams(String broadcasterSubject) {
        return repository.findAllByBroadcasterSubject(broadcasterSubject)
                .map(StreamSummaryResponse::from);
    }

    private static final int LIVE_STREAMS_DEFAULT_LIMIT = 24;

    /** Intermediate holder that pairs a summary with its cursor value. */
    private record LiveStreamRow(StreamSummaryResponse summary, String cursorValue) {}

    /**
     * Returns cursor-paginated live streams with optional keyword search.
     *
     * <p>Cursor is the ISO-8601 {@code started_at} of the last item from the
     * previous page. The response fetches one extra row to determine
     * {@code hasMore}; that extra row is stripped before returning.
     *
     * @param keyword optional search filter (matches title or broadcaster username)
     * @param cursor  ISO-8601 timestamp of the last item from the previous page,
     *                or {@code null} for the first page
     * @param limit   page size (clamped to 1–50, default 24)
     * @param sort    sort order: "views" for most-viewed, otherwise default started_at DESC
     */
    public Mono<LiveStreamPageResponse> getLiveStreams(
            String keyword, String cursor, int limit, String sort) {

        int effectiveLimit = Math.clamp(
                limit > 0 ? limit : LIVE_STREAMS_DEFAULT_LIMIT, 1, 50);
        int fetchSize = effectiveLimit + 1; // fetch one extra to determine hasMore

        StringBuilder sql = new StringBuilder("""
                SELECT * FROM stream.stream_session
                WHERE status = 'live'
                """);

        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title ILIKE :keyword OR broadcaster_username ILIKE :keyword)");
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" AND started_at < :cursor::timestamptz");
        }
        if ("views".equals(sort)) {
            sql.append(" ORDER BY views DESC LIMIT :limit");
        } else {
            sql.append(" ORDER BY started_at DESC LIMIT :limit");
        }

        var spec = databaseClient.sql(sql.toString())
                .bind("limit", fetchSize);

        if (keyword != null && !keyword.isBlank()) {
            spec = spec.bind("keyword", "%" + keyword + "%");
        }
        if (cursor != null && !cursor.isBlank()) {
            spec = spec.bind("cursor", cursor);
        }

        return spec.map((row, meta) -> {
                    UUID id = row.get("id", UUID.class);
                    String title = row.get("title", String.class);
                    String statusWire = row.get("status", String.class);
                    String cat = row.get("category", String.class);
                    UUID catId = row.get("category_id", UUID.class);
                    String[] tagArray = row.get("tags", String[].class);
                    List<String> tags = tagArray != null
                            ? List.copyOf(Arrays.asList(tagArray)) : List.of();
                    String thumbnailUrl = row.get("thumbnail_url", String.class);
                    Long views = row.get("views", Long.class);
                    OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
                    OffsetDateTime scheduledAt = row.get("scheduled_at", OffsetDateTime.class);
                    String broadcasterUsername = row.get("broadcaster_username", String.class);
                    OffsetDateTime startedAt = row.get("started_at", OffsetDateTime.class);

                    var summary = new StreamSummaryResponse(id, title, statusWire, cat,
                            catId, tags, thumbnailUrl, views, null,
                            createdAt, scheduledAt, broadcasterUsername);
                    return new LiveStreamRow(summary,
                            startedAt != null ? startedAt.toString() : null);
                })
                .all()
                .collectList()
                .map(rows -> {
                    boolean hasMore = rows.size() > effectiveLimit;
                    if (hasMore) {
                        rows = rows.subList(0, effectiveLimit);
                    }
                    List<StreamSummaryResponse> items = rows.stream()
                            .map(LiveStreamRow::summary)
                            .toList();
                    String nextCursor = hasMore && !rows.isEmpty()
                            ? rows.get(rows.size() - 1).cursorValue()
                            : null;
                    return LiveStreamPageResponse.of(items, nextCursor, hasMore);
                })
                .flatMap(page -> enrichWithViewerCounts(page.streams())
                        .map(enriched -> LiveStreamPageResponse.of(
                                enriched, page.nextCursor(), page.hasMore())));
    }

    /**
     * Returns watch page data for any authenticated viewer.
     * LIVE streams get a playUrl; non-LIVE streams get playUrl=null
     * so the UI can render an archive/ended state instead of a 409 error.
     */
    public Mono<WatchResponse> getWatchData(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .map(entity -> {
                    boolean isLive = entity.getStatus() == StreamStatus.LIVE;
                    String playUrl = null;
                    if (isLive && entity.getSrsName() != null) {
                        playUrl = String.format("%s/live/%s.m3u8",
                                publishTokenProps.srsHlsHost(), entity.getSrsName());
                    }
                    boolean isChatArchived = entity.getChatArchivedAt() != null;
                    return new WatchResponse(
                            playUrl,
                            id.toString(),
                            StreamSummaryResponse.from(entity),
                            isLive,
                            isChatArchived,
                            entity.getThumbnailUrl());
                });
    }

    public Mono<StreamResponse> getStream(UUID id, Jwt jwt, String viewerId) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.READ, entity.getBroadcasterSubject()))
                        .onErrorMap(StreamAuthorization.StreamAccessDeniedException.class,
                                e -> new StreamNotFoundException(id))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    if (!viewerId.equals(entity.getBroadcasterSubject())) {
                        return trackViewEvent(entity.getId(), viewerId)
                                .thenReturn(entity);
                    }
                    return Mono.just(entity);
                })
                .map(StreamResponse::from);
    }

    // ── Channel read (authenticated, cross-user safe) ─────────────────────────

    private static final int CHANNEL_HOME_SESSION_CAP = 15;

    /**
     * Return minimal identity for the channel page header.
     *
     * <p>Fetches only the newest 1 session to resolve the verified flag.
     * If no sessions exist yet, returns the path-variable username with
     * {@code verified = null}.
     */
    public Mono<ChannelIdentityResponse> getChannelIdentity(String username) {
        return repository.findFirstByBroadcasterUsernameOrderByCreatedAtDesc(username)
                .map(session -> {
                    String resolvedUsername = session.getBroadcasterUsername() != null
                            ? session.getBroadcasterUsername() : username;
                    return ChannelIdentityResponse.of(resolvedUsername,
                            session.getBroadcasterVerified(),
                            session.getBroadcasterSubject());
                })
                .defaultIfEmpty(ChannelIdentityResponse.of(username, null, null));
    }

    /**
     * Return the home-tab projection: 15 most recent sessions plus distinct
     * categories extracted from those sessions for the category strip.
     *
     * <p>Categories are derived from the capped rail rather than all sessions,
     * so this endpoint never performs a full table scan.
     */
    public Mono<ChannelHomeResponse> getChannelHome(String username) {
        return repository.findPublicSessionsByUsername(username, CHANNEL_HOME_SESSION_CAP)
                .collectList()
                .map(sessions -> {
                    List<StreamSummaryResponse> rail = sessions.stream()
                            .map(StreamSummaryResponse::from)
                            .collect(Collectors.toCollection(ArrayList::new));

                    List<String> recentCategories = sessions.stream()
                            .map(StreamSessionEntity::getCategory)
                            .filter(c -> c != null && !c.isBlank())
                            .distinct()
                            .collect(Collectors.toCollection(ArrayList::new));

                    return ChannelHomeResponse.of(rail, recentCategories);
                });
    }

    /**
     * Return the about-tab projection: bio, social links from the profile,
     * and stats computed from <em>all</em> sessions.
     *
     * <p>This is the most expensive channel endpoint — stats require a full
     * session scan — but it is only called when the user explicitly navigates
     * to the About tab.
     */
    public Mono<ChannelAboutResponse> getChannelAbout(String username) {
        Mono<List<StreamSessionEntity>> sessionsMono = repository
                .findAllByBroadcasterUsernameOrderByCreatedAtDesc(username)
                .collectList();
        Mono<BroadcasterProfileEntity> profileMono = profileRepository
                .findByUsername(username)
                .defaultIfEmpty(new BroadcasterProfileEntity());

        return Mono.zip(sessionsMono, profileMono)
                .map(tuple -> {
                    List<StreamSessionEntity> allSessions = tuple.getT1();
                    BroadcasterProfileEntity profile = tuple.getT2();

                    List<SocialLink> links = profile.getSocialLinks() != null
                            ? profile.getSocialLinks() : List.of();

                    ChannelStats stats = computeStats(allSessions);

                    return ChannelAboutResponse.of(profile.getBio(), links, stats);
                });
    }

    // ── Stats computation ──────────────────────────────────────────────────

    /**
     * Compute derived channel statistics from the full (uncapped) session list.
     * Zero-allocation when the list is empty.
     */
    private static ChannelStats computeStats(List<StreamSessionEntity> allSessions) {
        if (allSessions.isEmpty()) {
            return new ChannelStats(0, 0, null, null, List.of());
        }

        int totalStreams = allSessions.size();

        long totalHours = 0;
        OffsetDateTime oldest = null;
        for (var s : allSessions) {
            if (s.getStartedAt() != null && s.getEndedAt() != null) {
                long seconds = java.time.Duration.between(
                        s.getStartedAt(), s.getEndedAt()).toSeconds();
                if (seconds > 0) {
                    totalHours += seconds;
                }
            }
            if (s.getCreatedAt() != null) {
                if (oldest == null || s.getCreatedAt().isBefore(oldest)) {
                    oldest = s.getCreatedAt();
                }
            }
        }
        long totalHoursStreamed = totalHours / 3600;

        // Category frequency map
        Map<String, Long> catCounts = allSessions.stream()
                .map(StreamSessionEntity::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

        String topCategory = catCounts.entrySet()
                .stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);

        List<CategoryCount> breakdown = catCounts.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new CategoryCount(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        return new ChannelStats(totalStreams, totalHoursStreamed, topCategory,
                oldest, breakdown);
    }

    /**
     * Upsert the channel bio for the authenticated owner.
     *
     * <p>Only the channel owner (JWT username must match the URL username)
     * may update the bio. Creates the profile row on first write.
     */
    public Mono<Void> updateProfile(String username, UpdateProfileRequest request, Jwt jwt) {
        String jwtUsername = JwtAttr.username(jwt);
        if (jwtUsername == null || !jwtUsername.equals(username)) {
            return Mono.error(new ProfileOwnershipException(username));
        }

        return profileRepository.findByUsername(username)
                .defaultIfEmpty(BroadcasterProfileEntity.create(username, null))
                .flatMap(entity -> {
                    entity.setBio(request.bio());
                    entity.setSocialLinks(request.socialLinks() != null
                            ? request.socialLinks() : List.of());
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    return profileRepository.save(entity);
                })
                .doOnSuccess(saved -> log.info("Profile updated: username={}", username))
                .then();
    }

    // ── Update (metadata only) ──────────────────────────────────────────────

    public Mono<StreamResponse> updateStream(UUID id, UpdateStreamRequest request, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.UPDATE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> applyMetadataUpdates(entity, request))
                .flatMap(repository::save)
                .doOnSuccess(saved -> log.info("Stream updated: id={}", saved.getId()))
                .map(StreamResponse::from)
                .onErrorMap(OptimisticLockingFailureException.class,
                        ex -> new StreamConflictException(
                                "Stream was modified by another operation. Reload and try again."));
    }

    // ── Lifecycle transitions ───────────────────────────────────────────────

    /**
     * Prepare a DRAFT stream for broadcasting: issue a publish key for OBS
     * and keep the stream in DRAFT. The actual DRAFT→LIVE transition happens
     * when SRS fires the {@code on_publish} webhook (see {@link #handlePublish}).
     *
     * <p>Enforces the one-live-stream-per-broadcaster rule as an early check;
     * the webhook is the authoritative gate.
     */
    public Mono<PublishKeyResponse> startStream(UUID id, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.LIFECYCLE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    if (entity.getStatus() != StreamStatus.DRAFT) {
                        return Mono.error(new IllegalStateException(
                                "Only DRAFT streams can be started. Current: "
                                + entity.getStatus().wireValue()));
                    }
                    if (entity.getSrsName() == null) {
                        return Mono.error(new IllegalStateException(
                                "Stream has no publish name — recreate the stream."));
                    }
                    return repository.existsByBroadcasterSubjectAndStatus(
                                    entity.getBroadcasterSubject(), StreamStatus.LIVE)
                            .flatMap(hasLive -> {
                                if (Boolean.TRUE.equals(hasLive)) {
                                    return Mono.error(new StreamAlreadyLiveException(
                                            entity.getBroadcasterSubject()));
                                }
                                // Issue a fresh short-lived token for OBS
                                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                                String token = publishTokenService.issueToken(
                                        id, entity.getSrsName(), entity.getBroadcasterSubject());
                                entity.setUpdatedAt(now);
                                return repository.save(entity)
                                        .map(saved -> buildPublishKeyResponse(
                                                saved, entity.getSrsName(), token, now));
                            });
                })
                .doOnSuccess(resp -> log.info(
                        "Stream prepared for broadcast: id={}", id))
                .onErrorMap(OptimisticLockingFailureException.class,
                        ex -> new StreamConflictException(
                                "Stream was modified by another operation. Reload and try again."));
    }

    /** Transition a stream to ENDED. */
    public Mono<StreamResponse> endStream(UUID id, Jwt jwt) {
        return lifecycleTransition(id, jwt, StreamSessionEntity::end,
                entity -> StreamEvent.ended(entity.getId(), entity.getBroadcasterSubject(),
                        entity.getBroadcasterUsername(),
                        Boolean.TRUE.equals(entity.getAutoArchiveChat()),
                        entity.getChatArchiveDelayMinutes() != null
                                ? entity.getChatArchiveDelayMinutes() : 0),
                "ended");
    }

    /** Transition a stream to CANCELLED. Only allowed from DRAFT or SCHEDULED. */
    public Mono<StreamResponse> cancelStream(UUID id, Jwt jwt) {
        return lifecycleTransition(id, jwt, StreamSessionEntity::cancel,
                entity -> StreamEvent.cancelled(entity.getId(), entity.getBroadcasterSubject(),
                        entity.getBroadcasterUsername()),
                "cancelled");
    }

    // ── Delete (soft-delete via cancel) ─────────────────────────────────────

    public Mono<Void> deleteStream(UUID id, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.DELETE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    entity.cancel();
                    return repository.save(entity);
                })
                .flatMap(saved ->
                        outboxWriter.write(StreamEvent.cancelled(saved.getId(),
                                saved.getBroadcasterSubject(),
                                saved.getBroadcasterUsername()))
                                .doOnSuccess(oe -> log.info(
                                        "Stream cancelled via delete: id={}",
                                        saved.getId()))
                                .thenReturn(saved))
                .then();
    }

    // ── Publish key ─────────────────────────────────────────────────────────

    /**
     * Issue a fresh publish key. DRAFT: full rotation (new srsName + JWT).
     * LIVE: JWT-only rotation (same srsName, new JWT — protects HLS playback).
     */
    public Mono<PublishKeyResponse> issuePublishKey(UUID streamId, Jwt jwt) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.ISSUE_KEY, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    if (entity.getStatus() != StreamStatus.DRAFT
                        && entity.getStatus() != StreamStatus.LIVE) {
                        return Mono.error(new IllegalStateException(
                                "Publish key can only be issued for DRAFT or LIVE streams"));
                    }

                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    boolean isLive = entity.getStatus() == StreamStatus.LIVE;

                    // LIVE: keep srsName stable so HLS playback isn't interrupted
                    String srsName = isLive ? entity.getSrsName() : newSrsName();
                    String token = publishTokenService.issueToken(
                            streamId, srsName, entity.getBroadcasterSubject());

                    entity.setSrsName(srsName);
                    entity.setStreamKeyHash(HashUtils.sha256Hex(srsName));
                    entity.setUpdatedAt(now);

                    return repository.save(entity)
                            .map(saved -> buildPublishKeyResponse(
                                    saved, srsName, token, now));
                })
                .doOnSuccess(resp -> log.info(
                        "Publish key issued for stream: id={}", streamId));
    }

    /** View an existing publish key (token masked). */
    public Mono<PublishKeyResponse> getPublishKey(UUID streamId, Jwt jwt) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.READ, entity.getBroadcasterSubject()))
                        .onErrorMap(StreamAuthorization.StreamAccessDeniedException.class,
                                e -> new StreamNotFoundException(streamId))
                        .thenReturn(entity))
                .map(entity -> {
                    if (entity.getSrsName() == null) {
                        throw new NoPublishKeyException(streamId);
                    }
                    return buildPublishKeyResponse(entity, entity.getSrsName(),
                            "****", null);
                });
    }

    // ── Go-live from SCHEDULED ──────────────────────────────────────────────

    /**
     * Activate a scheduled stream: SCHEDULED → DRAFT with a fresh publish key.
     * This is the only way to move out of SCHEDULED status.
     */
    public Mono<PublishKeyResponse> goLiveFromSchedule(UUID streamId, Jwt jwt) {
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.LIFECYCLE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    if (entity.getStatus() != StreamStatus.SCHEDULED) {
                        return Mono.error(new IllegalStateException(
                                "Only SCHEDULED streams can go live. Current: "
                                + entity.getStatus().wireValue()));
                    }

                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                    String srsName = newSrsName();
                    String token = publishTokenService.issueToken(
                            streamId, srsName, entity.getBroadcasterSubject());

                    entity.setSrsName(srsName);
                    entity.setStreamKeyHash(HashUtils.sha256Hex(srsName));
                    entity.setStatus(StreamStatus.DRAFT);
                    entity.setScheduledAt(null);
                    entity.setUpdatedAt(now);

                    return repository.save(entity)
                            .map(saved -> buildPublishKeyResponse(
                                    saved, srsName, token, now));
                })
                .doOnSuccess(resp -> log.info(
                        "Stream activated from SCHEDULED: id={}", streamId));
    }

    // ── Webhook handlers (internal — no PBAC, auth via publish token) ───────

    /**
     * Handle an SRS {@code on_publish} webhook. If the stream is DRAFT,
     * transitions it to LIVE. If already LIVE, allows the reconnection
     * (Sol3: expiry is not checked for LIVE reconnects).
     */
    public Mono<Void> handlePublish(String srsName, String rawToken) {
        String hash = HashUtils.sha256Hex(srsName);
        return repository.findByStreamKeyHash(hash)
                .switchIfEmpty(Mono.error(new InvalidPublishTokenException(
                        "No stream found for srsName")))
                .flatMap(entity -> {
                    StreamStatus status = entity.getStatus();
                    if (status == StreamStatus.ENDED
                        || status == StreamStatus.CANCELLED) {
                        return Mono.error(new InvalidPublishTokenException(
                                "Stream is " + status.wireValue()));
                    }

                    return publishTokenService.validateForPublish(
                                    rawToken, srsName, status)
                            .then(Mono.defer(() -> {
                                if (status == StreamStatus.DRAFT) {
                                    return repository
                                            .existsByBroadcasterSubjectAndStatus(
                                                    entity.getBroadcasterSubject(),
                                                    StreamStatus.LIVE)
                                            .flatMap(hasLive -> {
                                                if (Boolean.TRUE.equals(hasLive)) {
                                                    return Mono.error(
                                                            new StreamAlreadyLiveException(
                                                                    entity.getBroadcasterSubject()));
                                                }
                                                entity.goLive();
                                                entity.setThumbnailUrl(
                                                        publishTokenProps.srsHlsHost()
                                                                + "/thumbnails/"
                                                                + srsName
                                                                + ".jpg");
                                                return repository.save(entity)
                                                        .flatMap(saved ->
                                                                outboxWriter.write(
                                                                        StreamEvent.started(
                                                                                saved.getId(),
                                                                                saved.getBroadcasterSubject(),
                                                                                saved.getBroadcasterUsername()))
                                                                        .doOnSuccess(oe -> {
                                                                            log.info(
                                                                                    "Stream started via webhook: id={} thumbnailUrl={}",
                                                                                    saved.getId(),
                                                                                    saved.getThumbnailUrl());
                                                                            sseRegistry.push(
                                                                                    saved.getBroadcasterSubject(),
                                                                                    new StreamSseEvent(
                                                                                            "stream:started",
                                                                                            saved.getId(),
                                                                                            "LIVE",
                                                                                            null));
                                                                        })
                                                                        .thenReturn(saved));
                                            });
                                }
                                // LIVE → reconnect, no state change
                                log.info("Stream reconnect via webhook: id={}",
                                        entity.getId());
                                return Mono.<StreamSessionEntity>just(entity);
                            }));
                })
                .then();
    }

    /** Handle an SRS {@code on_unpublish} webhook. Ends the stream if LIVE. */
    public Mono<Void> handleUnpublish(String srsName) {
        String hash = HashUtils.sha256Hex(srsName);
        return repository.findByStreamKeyHash(hash)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("on_unpublish for unknown stream (idempotent no-op)");
                    return Mono.empty();
                }))
                .flatMap(entity -> {
                    if (entity.getStatus() != StreamStatus.LIVE) {
                        log.info("on_unpublish for non-LIVE stream: id={} status={}",
                                entity.getId(), entity.getStatus().wireValue());
                        return Mono.<StreamSessionEntity>just(entity);
                    }
                    entity.end();
                    // If auto-archive chat with zero delay, mark archived immediately
                    boolean autoArchive = Boolean.TRUE.equals(entity.getAutoArchiveChat());
                    int delay = entity.getChatArchiveDelayMinutes() != null
                            ? entity.getChatArchiveDelayMinutes() : 0;
                    if (autoArchive && delay == 0) {
                        entity.setChatArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    }
                    return repository.save(entity)
                            .flatMap(saved ->
                                    outboxWriter.write(
                                            StreamEvent.ended(saved.getId(),
                                                    saved.getBroadcasterSubject(),
                                                    saved.getBroadcasterUsername(),
                                                    autoArchive, delay))
                                            .doOnSuccess(oe -> {
                                                log.info(
                                                        "Stream ended via webhook: id={} autoArchiveChat={} delay={}",
                                                        saved.getId(), autoArchive, delay);
                                                StreamSseEvent endedEvent = new StreamSseEvent(
                                                        "stream:ended",
                                                        saved.getId(),
                                                        "ENDED",
                                                        null);
                                                // Push to viewers watching the stream
                                                sseRegistry.pushToStreamViewers(
                                                        saved.getId(), endedEvent);
                                                // Also push to the broadcaster (they connect
                                                // without a streamId so aren't in streamViewers)
                                                sseRegistry.push(
                                                        saved.getBroadcasterSubject(),
                                                        endedEvent);
                                            })
                                            .thenReturn(saved));
                })
                .onErrorResume(e -> {
                    log.warn("on_unpublish lookup failed (idempotent no-op): {}",
                            e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    // ── Categories ──────────────────────────────────────────────────────────

    public Flux<CategoryResponse> listCategories() {
        return categoryRepository.findAll()
                .sort((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                .map(CategoryResponse::from);
    }

    // ── Archive ──────────────────────────────────────────────────────────────

    /**
     * Archive an ended stream. Owner-only. Sets {@code archived_url} on the
     * entity. In production this copies the SRS DVR MP4 to a persistent volume;
     * for now it sets the URL pointing at the SRS DVR path.
     */
    public Mono<StreamResponse> archiveStream(UUID id, Jwt jwt) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.LIFECYCLE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    if (entity.getStatus() != StreamStatus.ENDED) {
                        return Mono.error(new IllegalStateException(
                                "Only ENDED streams can be archived. Current: "
                                + entity.getStatus().wireValue()));
                    }
                    if (entity.getSrsName() == null) {
                        return Mono.error(new IllegalStateException(
                                "Cannot archive stream without srsName: id=" + id));
                    }
                    // Archive URL points to SRS DVR persistent path
                    String dvrUrl = String.format("%s/dvr/%s/archive.mp4",
                            publishTokenProps.srsHlsHost(), entity.getSrsName());
                    entity.setArchivedUrl(dvrUrl);
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    return repository.save(entity);
                })
                .doOnSuccess(saved -> log.info("Stream archived: id={}", saved.getId()))
                .map(StreamResponse::from)
                .onErrorMap(OptimisticLockingFailureException.class,
                        ex -> new StreamConflictException(
                                "Stream was modified by another operation. Reload and try again."));
    }

    // ── View tracking ────────────────────────────────────────────────────────

    private static final String VIEW_KEY_PREFIX = "stream:view:";

    /**
     * Track a view event in Redis with per-user-per-stream deduplication.
     *
     * <p>Uses a Redis Hash keyed by {@code stream:view:{streamId}:{viewerId}}.
     * On first view within the TTL window, sets {@code user_id} and
     * {@code first_seen_at}. On every view (including return visits), updates
     * {@code last_seen_at} and refreshes the TTL.
     *
     * <p>The caller is responsible for skipping self-views
     * (broadcaster viewing their own stream).
     *
     * @param streamId the stream being viewed
     * @param viewerId JWT subject for authenticated users, or {@code ip:…}
     *                 for anonymous viewers
     */
    private Mono<Void> trackViewEvent(UUID streamId, String viewerId) {
        String key = VIEW_KEY_PREFIX + streamId + ":" + viewerId;
        String now = String.valueOf(System.currentTimeMillis());
        return redisTemplate.opsForHash()
                .put(key, "user_id", viewerId)
                .then(redisTemplate.opsForHash()
                        .putIfAbsent(key, "first_seen_at", now))
                .then(redisTemplate.opsForHash()
                        .put(key, "last_seen_at", now))
                .then(redisTemplate.expire(key, viewCountProperties.viewTtl()))
                .doOnError(ex -> log.warn(
                        "Failed to track view event for stream={} viewer={}: {}",
                        streamId, viewerId, ex.getMessage()))
                .onErrorComplete()
                .then();
    }

    // ── Broadcasts (server-enforced status) ─────────────────────────────────

    /**
     * Row mapper: {@code stream_session} row → {@link StreamSummaryResponse}.
     */
    private static final BiFunction<Row, RowMetadata, StreamSummaryResponse> SUMMARY_MAPPER =
            (row, meta) -> {
                UUID id = row.get("id", UUID.class);
                String title = row.get("title", String.class);
                String statusWire = row.get("status", String.class);
                String category = row.get("category", String.class);
                UUID categoryId = row.get("category_id", UUID.class);
                String[] tagArray = row.get("tags", String[].class);
                List<String> tags = tagArray != null
                        ? List.copyOf(Arrays.asList(tagArray)) : List.of();
                String thumbnailUrl = row.get("thumbnail_url", String.class);
                Long views = row.get("views", Long.class);
                OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
                OffsetDateTime scheduledAt = row.get("scheduled_at", OffsetDateTime.class);
                String broadcasterUsername = row.get("broadcaster_username", String.class);

                return new StreamSummaryResponse(id, title, statusWire, category,
                        categoryId, tags, thumbnailUrl, views, null,
                        createdAt, scheduledAt, broadcasterUsername);
            };

    /**
     * Return the 10 most recent ENDED streams for a channel.
     * Server-enforced: status=ENDED, ordered by created_at DESC, limit 10.
     */
    public Flux<StreamSummaryResponse> getRecentBroadcasts(String username) {
        var query = BroadcastQueryBuilder.forRail(username);
        return databaseClient.sql(query.sql())
                .bindValues(query.bindings())
                .map(SUMMARY_MAPPER)
                .all();
    }

    /**
     * Return a paginated, filterable list of broadcasts for a channel.
     * Server-enforced: status IN (ENDED, LIVE, SCHEDULED) — never from client.
     * Keyword search uses database-level ILIKE; sorting/pagination execute in SQL.
     */
    public Mono<BroadcastPageResponse> getBroadcasts(
            String username,
            String keyword,
            String sort,
            String order,
            int page,
            int size
    ) {
        List<StreamStatus> statuses = List.of(
                StreamStatus.ENDED, StreamStatus.LIVE, StreamStatus.SCHEDULED);

        var countQ = BroadcastQueryBuilder.count(username, statuses, keyword);
        var dataQ = BroadcastQueryBuilder.forList(username, statuses,
                keyword, sort, order, page, size);

        Mono<Long> totalMono = databaseClient.sql(countQ.sql())
                .bindValues(countQ.bindings())
                .map(row -> row.get("total", Long.class))
                .one();

        Flux<StreamSummaryResponse> dataFlux = databaseClient.sql(dataQ.sql())
                .bindValues(dataQ.bindings())
                .map(SUMMARY_MAPPER)
                .all();

        return Mono.zip(totalMono, dataFlux.collectList())
                .map(tuple -> BroadcastPageResponse.of(
                        tuple.getT2(), tuple.getT1(), page, size));
    }

    /**
     * Return recently ended streams across all channels for the browse page
     * multi-rail layout. Server-enforced: status=ENDED, ordered by ended_at DESC.
     *
     * @param hours lookback window in hours (clamped 1–168, i.e. up to 7 days)
     * @param limit max results (clamped 1–50)
     */
    public Flux<StreamSummaryResponse> getRecentlyEndedStreams(int hours, int limit) {
        int effectiveHours = Math.clamp(hours, 1, 168);
        int effectiveLimit = Math.clamp(limit, 1, 50);
        String sql = """
                SELECT * FROM stream.stream_session
                WHERE status = 'ENDED'
                  AND ended_at > NOW() - (:hours || ' hours')::INTERVAL
                ORDER BY ended_at DESC
                LIMIT :limit
                """;
        return databaseClient.sql(sql)
                .bind("hours", effectiveHours)
                .bind("limit", effectiveLimit)
                .map(SUMMARY_MAPPER)
                .all();
    }

    // ── Viewer presence ────────────────────────────────────────────────────

    private static final String PRESENCE_KEY_PREFIX = "stream:presence:";
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(30);

    /**
     * Records a viewer heartbeat for a stream.
     * Self-view (broadcaster watching their own stream) is silently ignored.
     */
    public Mono<Void> sendHeartbeat(UUID streamId, Jwt jwt) {
        String viewerSubject = jwt.getSubject();
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> {
                    if (viewerSubject.equals(entity.getBroadcasterSubject())) {
                        return Mono.empty(); // self-view excluded
                    }
                    String key = PRESENCE_KEY_PREFIX + streamId + ":" + viewerSubject;
                    return redisTemplate.opsForValue()
                            .set(key, "1", PRESENCE_TTL)
                            .then();
                });
    }

    /**
     * Returns the count of active viewers for a stream.
     */
    public Mono<Long> getViewerCount(UUID streamId) {
        String pattern = PRESENCE_KEY_PREFIX + streamId + ":*";
        var options = org.springframework.data.redis.core.ScanOptions
                .scanOptions().match(pattern).build();
        return redisTemplate.scan(options).count();
    }

    /**
     * Batch-fetches viewer counts from Redis and returns a new list with
     * viewerCount set on each LIVE summary. Non-LIVE summaries pass through
     * unchanged.
     *
     * <p>A single Redis SCAN is performed across all presence keys; results
     * are grouped by stream ID and applied to matching summaries.
     */
    private Mono<List<StreamSummaryResponse>> enrichWithViewerCounts(
            List<StreamSummaryResponse> items) {
        if (items == null || items.isEmpty()) {
            return Mono.just(List.of());
        }

        // Pre-allocate counters for LIVE streams only
        java.util.Map<UUID, java.util.concurrent.atomic.AtomicLong> counters =
                new java.util.concurrent.ConcurrentHashMap<>();
        for (StreamSummaryResponse item : items) {
            if ("LIVE".equals(item.status())) {
                counters.put(item.id(), new java.util.concurrent.atomic.AtomicLong(0));
            }
        }

        if (counters.isEmpty()) {
            return Mono.just(items);
        }

        var options = org.springframework.data.redis.core.ScanOptions
                .scanOptions().match(PRESENCE_KEY_PREFIX + "*").count(1000).build();

        return redisTemplate.scan(options)
                .doOnNext(key -> {
                    UUID streamId = ViewerCountPushService.extractStreamId(key);
                    if (streamId != null) {
                        java.util.concurrent.atomic.AtomicLong counter =
                                counters.get(streamId);
                        if (counter != null) {
                            counter.incrementAndGet();
                        }
                    }
                })
                .then()
                .thenReturn(items.stream()
                        .map(item -> {
                            java.util.concurrent.atomic.AtomicLong counter =
                                    counters.get(item.id());
                            return counter != null
                                    ? item.withViewerCount(counter.get())
                                    : item;
                        })
                        .toList());
    }

    // ── Watch history ──────────────────────────────────────────────────────

    /**
     * Record (or update) a watch history entry for the authenticated user.
     * Upsert pattern: if an entry exists for user+stream, update watchedAt;
     * otherwise create a new entry.
     */
    public Mono<Void> recordWatchHistory(UUID streamId, Jwt jwt) {
        String userSubject = jwt.getSubject();
        return repository.findById(streamId)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(streamId)))
                .flatMap(entity -> watchHistoryRepository
                        .findByUserSubjectAndStreamId(userSubject, streamId)
                        .flatMap(existing -> {
                            existing.setWatchedAt(OffsetDateTime.now(ZoneOffset.UTC));
                            return watchHistoryRepository.save(existing);
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            WatchHistoryEntity entry = new WatchHistoryEntity();
                            entry.setId(UUID.randomUUID());
                            entry.setNew(true);
                            entry.setUserSubject(userSubject);
                            entry.setStreamId(streamId);
                            entry.setWatchedAt(OffsetDateTime.now(ZoneOffset.UTC));
                            entry.setWatchDurationSeconds(0L);
                            return watchHistoryRepository.save(entry);
                        }))
                        .doOnSuccess(saved -> log.debug(
                                "Watch history recorded: user={} stream={}",
                                userSubject, streamId))
                        .then());
    }

    /**
     * Return the authenticated user's watch history, most recent first.
     * Deleted streams return "[Deleted]" placeholders so the user's list
     * does not break.
     */
    public Flux<WatchHistoryResponse> getWatchHistory(Jwt jwt, int limit) {
        String userSubject = jwt.getSubject();
        int effectiveLimit = Math.clamp(limit, 1, 100);
        return watchHistoryRepository.findAllByUserSubjectOrderByWatchedAtDesc(userSubject)
                .take(effectiveLimit)
                .flatMap(entry ->
                        repository.findById(entry.getStreamId())
                                .map(entity -> new WatchHistoryResponse(
                                        entry.getId(),
                                        entry.getStreamId(),
                                        entity.getTitle(),
                                        entity.getStatus().wireValue(),
                                        entity.getCategory(),
                                        entity.getThumbnailUrl(),
                                        entity.getBroadcasterUsername(),
                                        entity.getViews(),
                                        entry.getWatchedAt(),
                                        entry.getWatchDurationSeconds()))
                                .defaultIfEmpty(new WatchHistoryResponse(
                                        entry.getId(),
                                        entry.getStreamId(),
                                        "[Deleted]",
                                        "DELETED",
                                        null,
                                        null,
                                        null,
                                        null,
                                        entry.getWatchedAt(),
                                        entry.getWatchDurationSeconds()))
                );
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private StreamSessionEntity buildEntity(UUID id, String sub,
                                            String username, Boolean verified,
                                            CreateStreamRequest request,
                                            OffsetDateTime now) {
        StreamSessionEntity entity = new StreamSessionEntity();
        entity.setId(id);
        entity.setNew(true);
        entity.setBroadcasterSubject(sub);
        entity.setBroadcasterUsername(username);
        entity.setBroadcasterVerified(verified);
        entity.setTitle(request.title());
        entity.setDescription(request.description());
        entity.setMaxViewers(request.maxViewers());
        entity.setStatus(StreamStatus.DRAFT);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        if (request.tags() != null && !request.tags().isEmpty()) {
            entity.setTags(request.tags().toArray(String[]::new));
        }

        if (request.autoArchiveChat() != null) {
            entity.setAutoArchiveChat(request.autoArchiveChat());
        }
        if (request.chatArchiveDelayMinutes() != null) {
            entity.setChatArchiveDelayMinutes(request.chatArchiveDelayMinutes());
        }

        return entity;
    }

    private Mono<StreamSessionEntity> applyMetadataUpdates(StreamSessionEntity entity,
                                                           UpdateStreamRequest request) {
        if (request.title() != null) {
            entity.setTitle(request.title());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.maxViewers() != null) {
            entity.setMaxViewers(request.maxViewers());
        }

        if (request.autoArchiveChat() != null) {
            entity.setAutoArchiveChat(request.autoArchiveChat());
        }
        if (request.chatArchiveDelayMinutes() != null) {
            int delay = request.chatArchiveDelayMinutes();
            if (delay < 0 || delay > 10080) {
                return Mono.error(new IllegalArgumentException(
                        "chatArchiveDelayMinutes must be 0–10080 (7 days), got: " + delay));
            }
            entity.setChatArchiveDelayMinutes(delay);
        }

        if (request.categoryId() != null) {
            return categoryRepository.findById(request.categoryId())
                    .switchIfEmpty(Mono.error(new IllegalArgumentException(
                            "Category not found: id=" + request.categoryId())))
                    .map(category -> {
                        entity.setCategoryId(category.getId());
                        entity.setCategory(category.getName());
                        return entity;
                    });
        } else if (request.category() != null) {
            entity.setCategory(request.category());
        }

        if (request.tags() != null) {
            entity.setTags(request.tags().toArray(String[]::new));
        }

        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return Mono.just(entity);
    }

    /**
     * Generic lifecycle transition helper for simple status changes
     * that don't need extra validation beyond the PBAC check.
     */
    private Mono<StreamResponse> lifecycleTransition(
            UUID id, Jwt jwt,
            java.util.function.Consumer<StreamSessionEntity> transition,
            java.util.function.Function<StreamSessionEntity, StreamEvent> eventFactory,
            String actionLabel) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
                .flatMap(entity -> authorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.STREAM, AuthResourceKind.SESSION,
                                AuthAction.LIFECYCLE, entity.getBroadcasterSubject()))
                        .thenReturn(entity))
                .flatMap(entity -> {
                    transition.accept(entity);
                    return repository.save(entity);
                })
                .flatMap(saved ->
                        outboxWriter.write(eventFactory.apply(saved))
                                .doOnSuccess(oe -> {
                                    log.info("Stream {}: id={}", actionLabel,
                                            saved.getId());
                                    // Push SSE for end transitions so viewers and
                                    // the broadcaster see the change in real time.
                                    // The on_unpublish webhook path also pushes,
                                    // but the explicit endStream API path was
                                    // missing this — SSE only went through Kafka.
                                    if ("ended".equals(actionLabel)) {
                                        StreamSseEvent endedEvent = new StreamSseEvent(
                                                "stream:ended",
                                                saved.getId(),
                                                "ENDED",
                                                null);
                                        sseRegistry.pushToStreamViewers(
                                                saved.getId(), endedEvent);
                                        sseRegistry.push(
                                                saved.getBroadcasterSubject(),
                                                endedEvent);
                                    }
                                })
                                .thenReturn(saved))
                .map(StreamResponse::from)
                .onErrorMap(OptimisticLockingFailureException.class,
                        ex -> new StreamConflictException(
                                "Stream was modified by another operation. Reload and try again."));
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /** Generate a new SRS stream name (UUID without dashes). */
    private static String newSrsName() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Build a PublishKeyResponse from entity state. */
    private PublishKeyResponse buildPublishKeyResponse(
            StreamSessionEntity entity, String srsName,
            String token, OffsetDateTime now) {
        String rtmpUrl = String.format("%s/live/%s?token=%s",
                publishTokenProps.srsRtmpHost(), srsName, token);
        String playUrl = String.format("%s/live/%s.m3u8",
                publishTokenProps.srsHlsHost(), srsName);
        OffsetDateTime expiresAt = now != null
                ? now.plus(publishTokenProps.ttl())
                : null;
        return new PublishKeyResponse(
                entity.getId(), srsName, rtmpUrl, playUrl, token, expiresAt);
    }

    // ── Exceptions ──────────────────────────────────────────────────────────

    public static class StreamNotFoundException extends RuntimeException {
        public StreamNotFoundException(UUID id) {
            super("Stream not found: id=" + id);
        }
    }

    public static class StreamNotLiveException extends RuntimeException {
        public StreamNotLiveException(UUID id) {
            super("Stream is not live: id=" + id);
        }
    }

    public static class StreamAlreadyLiveException extends RuntimeException {
        public StreamAlreadyLiveException(String broadcasterSubject) {
            super("Broadcaster already has a live stream: " + broadcasterSubject);
        }
    }

    public static class StreamConflictException extends RuntimeException {
        public StreamConflictException(String message) {
            super(message);
        }
    }

    public static class NoPublishKeyException extends RuntimeException {
        public NoPublishKeyException(UUID streamId) {
            super("No publish key has been issued for stream: id=" + streamId);
        }
    }

    /** Thrown when webhook publish token validation fails. */
    public static class InvalidPublishTokenException extends RuntimeException {
        public InvalidPublishTokenException(String message) {
            super(message);
        }
    }

    /** Thrown when a non-owner tries to update a channel profile. */
    public static class ProfileOwnershipException extends RuntimeException {
        public ProfileOwnershipException(String username) {
            super("You do not own the channel: " + username);
        }
    }
}
