package com.streaming.chat.config;

import com.streaming.chat.application.SendGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Default wiring for the message-send pipeline guards.
 *
 * <p>Phase 0 provides a no-op {@link SendGuard} so the send pipeline compiles and
 * behaves exactly as before. Phase 3.4 (Track A) contributes a
 * {@code BanSendGuard} {@code @Component}; because this bean is
 * {@link ConditionalOnMissingBean}, the real guard automatically takes over with
 * no edit to this class.
 */
@Configuration
public class GuardConfig {

    @Bean
    @ConditionalOnMissingBean(SendGuard.class)
    public SendGuard noOpSendGuard() {
        return (room, authorSubject) -> Mono.empty();
    }
}
