package com.streaming.stream.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response for publish key operations.
 *
 * <p>{@code token} is the raw JWT — returned once at issuance
 * ({@code POST}) and masked as {@code ****} when viewing ({@code GET}).
 * {@code srsName} is the semi-private UUID used in RTMP/HLS URLs.
 */
public record PublishKeyResponse(
        UUID streamId,
        String srsName,
        String rtmpUrl,
        String playUrl,
        String token,
        OffsetDateTime expiresAt
) {}
