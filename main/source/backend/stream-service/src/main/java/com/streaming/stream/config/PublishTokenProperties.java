package com.streaming.stream.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for publish token issuance and SRS media server URLs.
 *
 * <p>The HMAC secret for signing publish tokens is shared with
 * {@link JwtProperties#hmacSecret()} — both tokens use the same key.
 */
@ConfigurationProperties(prefix = "streaming.publish-token")
public record PublishTokenProperties(
        /** How long a publish token is valid for the DRAFT→LIVE authorization gate. */
        Duration ttl,
        /** RTMP ingest base URL, e.g. {@code rtmp://srs:1935}. */
        String srsRtmpHost,
        /** HLS playback base URL, e.g. {@code http://srs:8080}. */
        String srsHlsHost
) {}
