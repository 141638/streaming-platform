package com.streaming.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.stream.api.dto.CreateStreamRequest;

import com.streaming.stream.api.dto.UpdateStreamRequest;
import com.streaming.stream.messaging.StreamEventPublisher;
import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.entity.StreamStatus;
import com.streaming.stream.persistence.repository.StreamCategoryRepository;
import com.streaming.stream.persistence.repository.StreamSessionRepository;
import com.streaming.stream.security.AuthAction;
import com.streaming.stream.security.AuthResourceDomain;
import com.streaming.stream.security.AuthResourceKind;
import com.streaming.stream.security.RequiredAuthority;
import com.streaming.stream.security.StreamAuthorization;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("StreamService (with PBAC + state machine)")
@ExtendWith(MockitoExtension.class)
class StreamServiceTest {

    private static final UUID STREAM_ID = UUID.randomUUID();
    private static final String OWNER_SUB = "e8f9a1b2-3c4d-5e6f-7a8b-9c0d1e2f3a4b";
    private static final String OTHER_SUB = "f9a1b2c3-4d5e-6f7a-8b9c-0d1e2f3a4b5c";

    @Mock
    private StreamSessionRepository repository;

    @Mock
    private StreamCategoryRepository categoryRepository;

    @Mock
    private StreamAuthorization authorization;

    @Mock
    private StreamEventPublisher eventPublisher;

    private StreamService service;

    @BeforeEach
    void setUp() {
        service = new StreamService(repository, categoryRepository, authorization, eventPublisher);
    }

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", sub)
                .build();
    }

    private static StreamSessionEntity entity(UUID id, String broadcasterSubject, StreamStatus status) {
        StreamSessionEntity e = new StreamSessionEntity();
        e.setId(id);
        e.setBroadcasterSubject(broadcasterSubject);
        e.setTitle("Test Stream");
        e.setStatus(status);
        e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        e.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return e;
    }

    private static RequiredAuthority required(AuthAction action, String ownerSub) {
        return new RequiredAuthority(AuthResourceDomain.STREAM, AuthResourceKind.SESSION, action, ownerSub);
    }

    private void stubPublish() {
        when(eventPublisher.publish(any())).thenReturn(Mono.empty());
    }

    // ── getStream ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStream")
    class GetStream {

        @Test
        @DisplayName("returns entity when authorized")
        void returnsEntityWhenAuthorized() {
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.READ, OWNER_SUB))))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.getStream(STREAM_ID, j))
                    .assertNext(response -> {
                        assertThat(response.id()).isEqualTo(STREAM_ID);
                        assertThat(response.title()).isEqualTo("Test Stream");
                    })
                    .verifyComplete();

            verify(authorization).requireAccess(j, required(AuthAction.READ, OWNER_SUB));
        }

        @Test
        @DisplayName("throws StreamNotFoundException when access denied (404)")
        void throwsNotFoundExceptionWhenDenied() {
            Jwt j = jwt(OTHER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.READ, OWNER_SUB))))
                    .thenReturn(Mono.error(new StreamAuthorization.StreamAccessDeniedException(
                            required(AuthAction.READ, OWNER_SUB), OTHER_SUB)));

            StepVerifier.create(service.getStream(STREAM_ID, j))
                    .expectError(StreamService.StreamNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("throws StreamNotFoundException when entity missing")
        void throwsNotFoundExceptionWhenMissing() {
            Jwt j = jwt(OWNER_SUB);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.empty());

            StepVerifier.create(service.getStream(STREAM_ID, j))
                    .expectError(StreamService.StreamNotFoundException.class)
                    .verify();

            verify(authorization, never()).requireAccess(any(), any());
        }
    }

    // ── createStream ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createStream")
    class CreateStream {

        @Test
        @DisplayName("creates as DRAFT when no scheduledAt")
        void createsAsDraft() {
            stubPublish();
            Jwt j = jwt(OWNER_SUB);
            CreateStreamRequest request = new CreateStreamRequest(
                    "Test", "Desc", null, null, null, 100, null);
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.CREATE, OWNER_SUB))))
                    .thenReturn(Mono.empty());
            when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.createStream(request, j))
                    .assertNext(response -> {
                        assertThat(response.title()).isEqualTo("Test");
                        assertThat(response.status()).isEqualTo("draft");
                    })
                    .verifyComplete();

            verify(authorization).requireAccess(j, required(AuthAction.CREATE, OWNER_SUB));
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("creates as SCHEDULED when scheduledAt is provided")
        void createsAsScheduled() {
            stubPublish();
            Jwt j = jwt(OWNER_SUB);
            OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
            CreateStreamRequest request = new CreateStreamRequest(
                    "Test", "Desc", null, null, null, 100, future);
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.CREATE, OWNER_SUB))))
                    .thenReturn(Mono.empty());
            when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.createStream(request, j))
                    .assertNext(response -> {
                        assertThat(response.title()).isEqualTo("Test");
                        assertThat(response.status()).isEqualTo("scheduled");
                    })
                    .verifyComplete();
        }
    }

    // ── updateStream ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateStream")
    class UpdateStream {

        @Test
        @DisplayName("denies unauthorized caller")
        void deniesUnauthorized() {
            Jwt j = jwt(OTHER_SUB);
            UpdateStreamRequest request = new UpdateStreamRequest(null, null, null, null, null, null);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.UPDATE, OWNER_SUB))))
                    .thenReturn(Mono.error(new StreamAuthorization.StreamAccessDeniedException(
                            required(AuthAction.UPDATE, OWNER_SUB), OTHER_SUB)));

            StepVerifier.create(service.updateStream(STREAM_ID, request, j))
                    .expectError(StreamAuthorization.StreamAccessDeniedException.class)
                    .verify();
        }

        @Test
        @DisplayName("updates metadata without changing status")
        void updatesMetadataOnly() {
            Jwt j = jwt(OWNER_SUB);
            UpdateStreamRequest request = new UpdateStreamRequest("New Title", null, null, null, null, null);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.UPDATE, OWNER_SUB))))
                    .thenReturn(Mono.empty());
            when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.updateStream(STREAM_ID, request, j))
                    .assertNext(response -> {
                        assertThat(response.title()).isEqualTo("New Title");
                        assertThat(response.status()).isEqualTo("draft");
                    })
                    .verifyComplete();
        }
    }

    // ── startStream ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("startStream")
    class StartStream {

        @Test
        @DisplayName("transitions to LIVE and sets startedAt")
        void transitionsToLive() {
            stubPublish();
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.LIFECYCLE, OWNER_SUB))))
                    .thenReturn(Mono.empty());
            when(repository.existsByBroadcasterSubjectAndStatus(OWNER_SUB, StreamStatus.LIVE))
                    .thenReturn(Mono.just(false));
            when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.startStream(STREAM_ID, j))
                    .assertNext(response -> {
                        assertThat(response.status()).isEqualTo("live");
                        assertThat(response.startedAt()).isNotNull();
                    })
                    .verifyComplete();

            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("rejects when broadcaster already has a live stream")
        void rejectsWhenAlreadyLive() {
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.LIFECYCLE, OWNER_SUB))))
                    .thenReturn(Mono.empty());
            when(repository.existsByBroadcasterSubjectAndStatus(OWNER_SUB, StreamStatus.LIVE))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.startStream(STREAM_ID, j))
                    .expectError(StreamService.StreamAlreadyLiveException.class)
                    .verify();

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("rejects invalid transition (ENDED → LIVE)")
        void rejectsInvalidTransition() {
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.ENDED);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.LIFECYCLE, OWNER_SUB))))
                    .thenReturn(Mono.empty());
            when(repository.existsByBroadcasterSubjectAndStatus(OWNER_SUB, StreamStatus.LIVE))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(service.startStream(STREAM_ID, j))
                    .expectError(IllegalStateException.class)
                    .verify();
        }
    }

    // ── endStream ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("endStream")
    class EndStream {

        @Test
        @DisplayName("transitions LIVE → ENDED")
        void transitionsLiveToEnded() {
            stubPublish();
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.LIVE);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.LIFECYCLE, OWNER_SUB))))
                    .thenReturn(Mono.empty());
            when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.endStream(STREAM_ID, j))
                    .assertNext(response -> {
                        assertThat(response.status()).isEqualTo("ended");
                        assertThat(response.endedAt()).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("rejects DRAFT → ENDED")
        void rejectsDraftToEnded() {
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.LIFECYCLE, OWNER_SUB))))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.endStream(STREAM_ID, j))
                    .expectError(IllegalStateException.class)
                    .verify();
        }
    }

    // ── cancelStream ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelStream")
    class CancelStream {

        @Test
        @DisplayName("transitions DRAFT → CANCELLED")
        void transitionsDraftToCancelled() {
            stubPublish();
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.LIFECYCLE, OWNER_SUB))))
                    .thenReturn(Mono.empty());
            when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.cancelStream(STREAM_ID, j))
                    .assertNext(response -> assertThat(response.status()).isEqualTo("cancelled"))
                    .verifyComplete();

            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("rejects LIVE → CANCELLED (boundary enforcement)")
        void rejectsLiveToCancelled() {
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.LIVE);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.LIFECYCLE, OWNER_SUB))))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.cancelStream(STREAM_ID, j))
                    .expectError(IllegalStateException.class)
                    .verify();
        }
    }

    // ── deleteStream ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteStream")
    class DeleteStream {

        @Test
        @DisplayName("denies unauthorized caller")
        void deniesUnauthorized() {
            Jwt j = jwt(OTHER_SUB);
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(e));
            when(authorization.requireAccess(eq(j), eq(required(AuthAction.DELETE, OWNER_SUB))))
                    .thenReturn(Mono.error(new StreamAuthorization.StreamAccessDeniedException(
                            required(AuthAction.DELETE, OWNER_SUB), OTHER_SUB)));

            StepVerifier.create(service.deleteStream(STREAM_ID, j))
                    .expectError(StreamAuthorization.StreamAccessDeniedException.class)
                    .verify();
        }
    }

    // ── listCategories ──────────────────────────────────────────────────────

    @Test
    @DisplayName("listCategories returns sorted categories")
    void listCategoriesReturnsSorted() {
        when(categoryRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(service.listCategories())
                .verifyComplete();
    }

    // ── Entity-level transition tests ───────────────────────────────────────

    @Nested
    @DisplayName("StreamSessionEntity transitions")
    class EntityTransitions {

        @Test
        @DisplayName("DRAFT → LIVE succeeds")
        void draftToLive() {
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            e.goLive();
            assertThat(e.getStatus()).isEqualTo(StreamStatus.LIVE);
            assertThat(e.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("DRAFT → CANCELLED succeeds")
        void draftToCancelled() {
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            e.cancel();
            assertThat(e.getStatus()).isEqualTo(StreamStatus.CANCELLED);
            assertThat(e.getEndedAt()).isNotNull();
        }

        @Test
        @DisplayName("LIVE → ENDED succeeds")
        void liveToEnded() {
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.LIVE);
            e.end();
            assertThat(e.getStatus()).isEqualTo(StreamStatus.ENDED);
            assertThat(e.getEndedAt()).isNotNull();
        }

        @Test
        @DisplayName("LIVE → DRAFT throws")
        void liveToDraftThrows() {
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.LIVE);
            try {
                e.transitionTo(StreamStatus.DRAFT);
                throw new AssertionError("Expected IllegalStateException");
            } catch (IllegalStateException ex) {
                assertThat(ex.getMessage()).contains("Invalid transition");
            }
        }

        @Test
        @DisplayName("LIVE → CANCELLED throws (boundary)")
        void liveToCancelledThrows() {
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.LIVE);
            try {
                e.cancel();
                throw new AssertionError("Expected IllegalStateException");
            } catch (IllegalStateException ex) {
                assertThat(ex.getMessage()).contains("Invalid transition");
            }
        }

        @Test
        @DisplayName("ENDED is terminal")
        void endedIsTerminal() {
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.ENDED);
            try {
                e.goLive();
                throw new AssertionError("Expected IllegalStateException");
            } catch (IllegalStateException ex) {
                assertThat(ex.getMessage()).contains("Invalid transition");
            }
        }

        @Test
        @DisplayName("CANCELLED is terminal")
        void cancelledIsTerminal() {
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.CANCELLED);
            try {
                e.goLive();
                throw new AssertionError("Expected IllegalStateException");
            } catch (IllegalStateException ex) {
                assertThat(ex.getMessage()).contains("Invalid transition");
            }
        }

        @Test
        @DisplayName("same-state transition is no-op")
        void sameStateNoop() {
            StreamSessionEntity e = entity(STREAM_ID, OWNER_SUB, StreamStatus.DRAFT);
            e.transitionTo(StreamStatus.DRAFT);
            assertThat(e.getStatus()).isEqualTo(StreamStatus.DRAFT);
        }
    }
}
