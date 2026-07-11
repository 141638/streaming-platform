package com.streaming.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag for chat PBAC ({@code ent}) enforcement.
 *
 * <p>PBAC ships <b>dark</b> (default {@code false}): {@link com.streaming.chat.security.ChatAuthorization}
 * short-circuits to allow when disabled, so the enforcement code lands in
 * production without gating any request until auth-service is confirmed to emit
 * chat {@code ent} templates compatible with the matcher. Flip
 * {@code chat.pbac.enabled=true} (env {@code CHAT_PBAC_ENABLED}) to activate.
 *
 * @param enabled whether {@code requireAccess} enforces entitlements (default {@code false})
 */
@ConfigurationProperties(prefix = "chat.pbac")
public record ChatPbacProperties(boolean enabled) {
}
