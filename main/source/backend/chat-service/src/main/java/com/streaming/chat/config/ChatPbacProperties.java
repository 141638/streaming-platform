package com.streaming.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag for chat PBAC ({@code ent}) enforcement.
 *
 * <p>PBAC enforcement is <b>on by default</b>. The {@code dev} Spring profile
 * allows disabling for local testing; in any other profile disabling is a hard
 * startup failure (see {@link com.streaming.chat.security.ChatAuthorization#enforceOrWarn}).
 * Set {@code CHAT_PBAC_ENABLED=false} (env) or {@code chat.pbac.enabled=false}
 * to disable during development.
 *
 * @param enabled whether {@code requireAccess} enforces entitlements (default {@code true})
 */
@ConfigurationProperties(prefix = "chat.pbac")
public record ChatPbacProperties(boolean enabled) {
}
