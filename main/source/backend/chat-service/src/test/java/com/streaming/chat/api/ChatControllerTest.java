package com.streaming.chat.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.streaming.chat.api.dto.SendMessageRequest;
import com.streaming.chat.api.error.ChatExceptionHandler;
import com.streaming.chat.application.ChatService;
import com.streaming.chat.application.ChatService.RoomArchivedException;
import com.streaming.chat.application.ChatService.RoomNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Web-layer slice test for {@link ChatController} error mapping. Verifies that the
 * Phase-0 {@link ChatExceptionHandler} advice turns chat domain exceptions into the
 * correct HTTP status and {@code ChatApiError} {@code code} — the machine-readable
 * discriminator clients branch on.
 *
 * <p>Only the two Phase-0 codes are asserted here (404 {@code CHAT_ROOM_NOT_FOUND},
 * 409 {@code CHAT_ROOM_ARCHIVED}). The 403 ban/authz codes are Track A's handlers.
 */
@WebFluxTest(controllers = ChatController.class)
@Import({ChatExceptionHandler.class, ChatControllerTest.TestSecurityConfig.class})
@DisplayName("ChatController error mapping")
class ChatControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private ChatService chatService;

    @Test
    @DisplayName("GET missing room → 404 with code CHAT_ROOM_NOT_FOUND")
    void missingRoomReturns404() {
        when(chatService.getRoom(any(), eq("ghost")))
                .thenReturn(Mono.error(new RoomNotFoundException("ghost")));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("user-1")))
                .get().uri("/v1/rooms/ghost")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CHAT_ROOM_NOT_FOUND");
    }

    @Test
    @DisplayName("POST to archived room → 409 with code CHAT_ROOM_ARCHIVED")
    void archivedRoomReturns409() {
        when(chatService.sendMessage(any(), eq("archived-room"), anyString(), any(), eq("hi")))
                .thenReturn(Mono.error(new RoomArchivedException("archived-room")));

        client.mutateWith(mockJwt().jwt(jwt -> jwt
                        .subject("user-1")
                        .claim("attr", Map.of("username", "bob"))))
                .post().uri("/v1/rooms/archived-room/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SendMessageRequest("hi"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code").isEqualTo("CHAT_ROOM_ARCHIVED");
    }

    /**
     * Permit-all reactive security so the slice test focuses on error mapping.
     * {@code @EnableWebFluxSecurity} still registers the {@code @AuthenticationPrincipal}
     * argument resolver the controller relies on; {@code mockJwt()} supplies the Jwt.
     */
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
