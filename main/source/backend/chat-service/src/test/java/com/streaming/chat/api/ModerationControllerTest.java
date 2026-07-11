package com.streaming.chat.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.streaming.chat.api.dto.BanRequest;
import com.streaming.chat.api.dto.BanResponse;
import com.streaming.chat.api.error.ChatExceptionHandler;
import com.streaming.chat.application.ModerationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Web-layer slice test for {@link ModerationController}. Verifies the
 * {@code durationSeconds} contract: a positive value is threaded through to the
 * service, and a non-positive value is rejected by {@code @Positive} validation
 * before the service is ever invoked.
 */
@WebFluxTest(controllers = ModerationController.class)
@Import({ChatExceptionHandler.class, ModerationControllerTest.TestSecurityConfig.class})
@DisplayName("ModerationController — ban duration contract")
class ModerationControllerTest {

    private static final String ROOM_KEY = "room-1";
    private static final String TARGET = "target-sub";

    @Autowired
    private WebTestClient client;

    @MockBean
    private ModerationService moderationService;

    private static BanResponse banResponse(OffsetDateTime expiresAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new BanResponse(
                UUID.randomUUID(), UUID.randomUUID(), TARGET, "mod-sub", "spam", now, expiresAt);
    }

    @Test
    @DisplayName("POST with positive durationSeconds threads it through and returns 201")
    void temporaryBanIsThreadedThrough() {
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3600);
        when(moderationService.ban(any(), eq(ROOM_KEY), eq(TARGET), eq("spam"), eq(3600L)))
                .thenReturn(Mono.just(banResponse(expiry)));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("mod-sub")))
                .post().uri("/v1/rooms/{roomKey}/bans", ROOM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BanRequest(TARGET, "spam", 3600L))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.bannedSubject").isEqualTo(TARGET)
                .jsonPath("$.expiresAt").exists();

        ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
        verify(moderationService).ban(any(), eq(ROOM_KEY), eq(TARGET), eq("spam"), durationCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(durationCaptor.getValue()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("POST with null durationSeconds issues a permanent ban (201)")
    void permanentBanWhenDurationNull() {
        when(moderationService.ban(any(), eq(ROOM_KEY), eq(TARGET), eq("spam"), eq((Long) null)))
                .thenReturn(Mono.just(banResponse(null)));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("mod-sub")))
                .post().uri("/v1/rooms/{roomKey}/bans", ROOM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BanRequest(TARGET, "spam", null))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("POST with non-positive durationSeconds is rejected with 400 before the service runs")
    void nonPositiveDurationIsRejected() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("mod-sub")))
                .post().uri("/v1/rooms/{roomKey}/bans", ROOM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new BanRequest(TARGET, "spam", 0L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST);

        verify(moderationService, org.mockito.Mockito.never()).ban(any(), any(), any(), any(), any());
    }

    @TestConfiguration
    @EnableWebFluxSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityWebFilterChain testChain(ServerHttpSecurity http) {
            return http
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(auth -> auth.anyExchange().permitAll())
                    .build();
        }
    }
}
