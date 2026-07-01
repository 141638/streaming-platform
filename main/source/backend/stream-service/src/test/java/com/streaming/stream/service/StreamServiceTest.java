package com.streaming.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.stream.api.dto.CreateStreamRequest;
import com.streaming.stream.api.dto.UpdateStreamRequest;
import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.entity.StreamStatus;
import com.streaming.stream.persistence.repository.StreamSessionRepository;
import com.streaming.stream.security.AuthAction;
import com.streaming.stream.security.AuthResourceDomain;
import com.streaming.stream.security.AuthResourceKind;
import com.streaming.stream.security.RequiredAuthority;
import com.streaming.stream.security.StreamAuthorization;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("StreamService (with PBAC)")
@ExtendWith(MockitoExtension.class)
class StreamServiceTest {

    private static final UUID STREAM_ID = UUID.randomUUID();
    private static final String OWNER_SUB = "e8f9a1b2-3c4d-5e6f-7a8b-9c0d1e2f3a4b";
    private static final String OTHER_SUB = "f9a1b2c3-4d5e-6f7a-8b9c-0d1e2f3a4b5c";

    @Mock
    private StreamSessionRepository repository;

    @Mock
    private StreamAuthorization authorization;

    private StreamService service;

    @BeforeEach
    void setUp() {
        service = new StreamService(repository, authorization);
    }

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", sub)
                .build();
    }

    private static StreamSessionEntity entity(UUID id, String broadcasterSubject) {
        StreamSessionEntity e = new StreamSessionEntity();
        e.setId(id);
        e.setBroadcasterSubject(broadcasterSubject);
        e.setTitle("Test Stream");
        e.setStatus(StreamStatus.DRAFT);
        e.setCreatedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        return e;
    }

    private static RequiredAuthority required(AuthAction action, String ownerSub) {
        return new RequiredAuthority(AuthResourceDomain.STREAM, AuthResourceKind.SESSION, action, ownerSub);
    }

    // ── getStream ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStream")
    class GetStream {

        @Test
        @DisplayName("returns entity when authorized")
        void returnsEntityWhenAuthorized() {
            Jwt j = jwt(OWNER_SUB);
            StreamSessionEntity entity = entity(STREAM_ID, OWNER_SUB);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(entity));
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
            StreamSessionEntity entity = entity(STREAM_ID, OWNER_SUB);
            when(repository.findById(STREAM_ID)).thenReturn(Mono.just(entity));
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

    // ── createStream ─────────────────────────────────────────────────────

    @Test
    @DisplayName("createStream checks authorization with CREATE action")
    void createStreamChecksAuthorization() {
        Jwt j = jwt(OWNER_SUB);
        CreateStreamRequest request = new CreateStreamRequest(
                "Test", "Desc", "gaming", 100);
        when(authorization.requireAccess(eq(j), eq(required(AuthAction.CREATE, OWNER_SUB))))
                .thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.createStream(request, j))
                .assertNext(response -> assertThat(response.title()).isEqualTo("Test"))
                .verifyComplete();

        verify(authorization).requireAccess(j, required(AuthAction.CREATE, OWNER_SUB));
    }

    // ── updateStream ─────────────────────────────────────────────────────

    @Test
    @DisplayName("updateStream denies unauthorized caller")
    void updateStreamDeniesUnauthorized() {
        Jwt j = jwt(OTHER_SUB);
        UpdateStreamRequest request = new UpdateStreamRequest(null, null, null, null, null);
        StreamSessionEntity entity = entity(STREAM_ID, OWNER_SUB);
        when(repository.findById(STREAM_ID)).thenReturn(Mono.just(entity));
        when(authorization.requireAccess(eq(j), eq(required(AuthAction.UPDATE, OWNER_SUB))))
                .thenReturn(Mono.error(new StreamAuthorization.StreamAccessDeniedException(
                        required(AuthAction.UPDATE, OWNER_SUB), OTHER_SUB)));

        StepVerifier.create(service.updateStream(STREAM_ID, request, j))
                .expectError(StreamAuthorization.StreamAccessDeniedException.class)
                .verify();
    }

    // ── deleteStream ─────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteStream denies unauthorized caller")
    void deleteStreamDeniesUnauthorized() {
        Jwt j = jwt(OTHER_SUB);
        StreamSessionEntity entity = entity(STREAM_ID, OWNER_SUB);
        when(repository.findById(STREAM_ID)).thenReturn(Mono.just(entity));
        when(authorization.requireAccess(eq(j), eq(required(AuthAction.DELETE, OWNER_SUB))))
                .thenReturn(Mono.error(new StreamAuthorization.StreamAccessDeniedException(
                        required(AuthAction.DELETE, OWNER_SUB), OTHER_SUB)));

        StepVerifier.create(service.deleteStream(STREAM_ID, j))
                .expectError(StreamAuthorization.StreamAccessDeniedException.class)
                .verify();
    }
}
