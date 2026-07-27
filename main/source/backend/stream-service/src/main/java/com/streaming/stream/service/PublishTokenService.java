package com.streaming.stream.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.streaming.pbac.config.JwtProperties;
import com.streaming.stream.config.PublishTokenProperties;
import com.streaming.stream.persistence.entity.StreamStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Issues and validates publish tokens (short-lived JWTs embedded in RTMP URLs).
 *
 * <h3>Sol3 validation (ADR-0004 Revised)</h3>
 * <ul>
 *   <li><b>DRAFT</b>: full validation — signature, srsName match, expiry</li>
 *   <li><b>LIVE</b>: relaxed validation — signature and srsName match only;
 *       expiry is <em>skipped</em> so OBS can reconnect to an active stream
 *       with the original token indefinitely</li>
 * </ul>
 *
 * <p>The TTL gates initial authorization (DRAFT→LIVE). Once LIVE, the DB
 * status check is the authoritative gate; the expired JWT still proves the
 * caller is the same entity that was originally authorized.
 */
@Service
public class PublishTokenService {

    private static final Logger log = LoggerFactory.getLogger(PublishTokenService.class);

    private final PublishTokenProperties props;
    private final JwtProperties jwtProps;

    public PublishTokenService(PublishTokenProperties props, JwtProperties jwtProps) {
        this.props = props;
        this.jwtProps = jwtProps;
    }

    /**
     * Issue a publish token for the given stream and streamer.
     *
     * @param streamId the public stream ID
     * @param srsName  the semi-private SRS stream name (UUID)
     * @param sub      the streamer's subject claim
     * @return serialised HS256 JWT
     */
    public String issueToken(UUID streamId, String srsName, String sub) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.ttl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .claim("streamId", streamId.toString())
                .claim("srsName", srsName)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .build();

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(signingKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign publish token", e);
        }
    }

    /**
     * Validate a publish token against the expected SRS stream name and
     * stream status, applying Sol3 contextual expiry rules.
     *
     * @param token        raw JWT string from the RTMP {@code ?token=} param
     * @param expectedSrsName the SRS stream name extracted from the RTMP path
     * @param streamStatus current status of the stream (DRAFT or LIVE)
     * @return validated claims on success, error signal on failure
     */
    public Mono<PublishTokenClaims> validateForPublish(
            String token, String expectedSrsName, StreamStatus streamStatus) {

        return Mono.fromCallable(() -> {
            SignedJWT jwt = SignedJWT.parse(token);

            // 1. Verify signature
            JWSVerifier verifier = new MACVerifier(signingKey());
            if (!jwt.verify(verifier)) {
                throw new InvalidPublishTokenException("Publish token signature invalid");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            // 2. Verify srsName matches
            String tokenSrsName = claims.getStringClaim("srsName");
            if (tokenSrsName == null || !tokenSrsName.equals(expectedSrsName)) {
                throw new InvalidPublishTokenException(
                        "srsName mismatch: token=" + tokenSrsName + " expected=" + expectedSrsName);
            }

            // 3. Expiry check — contextual per Sol3
            if (streamStatus == StreamStatus.DRAFT) {
                // DRAFT → enforce expiry (authorization gate)
                Date exp = claims.getExpirationTime();
                if (exp != null && exp.before(new Date())) {
                    throw new InvalidPublishTokenException("Publish token expired");
                }
            }
            // LIVE → skip expiry (reconnect allowed indefinitely)

            String sub = claims.getSubject();
            String streamIdStr = claims.getStringClaim("streamId");
            UUID streamId = streamIdStr != null ? UUID.fromString(streamIdStr) : null;

            return new PublishTokenClaims(sub, streamId, tokenSrsName,
                    claims.getExpirationTime() != null
                            ? claims.getExpirationTime().toInstant() : null);
        });
    }

    /**
     * Validate a publish token for an unpublish event.
     *
     * <p>Unlike {@link #validateForPublish}, this variant skips expiry and
     * stream-status checks entirely — the stream may already be ENDED by the
     * time SRS delivers {@code on_unpublish}. Only JWT signature and
     * {@code srsName} match are verified.
     *
     * @param token           raw JWT string from the RTMP {@code ?token=} param
     * @param expectedSrsName the SRS stream name extracted from the RTMP path
     * @return validated claims on success, error signal on failure
     */
    public Mono<PublishTokenClaims> validateForUnpublish(
            String token, String expectedSrsName) {

        return Mono.fromCallable(() -> {
            SignedJWT jwt = SignedJWT.parse(token);

            // 1. Verify signature
            JWSVerifier verifier = new MACVerifier(signingKey());
            if (!jwt.verify(verifier)) {
                throw new InvalidPublishTokenException("Publish token signature invalid");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            // 2. Verify srsName matches
            String tokenSrsName = claims.getStringClaim("srsName");
            if (tokenSrsName == null || !tokenSrsName.equals(expectedSrsName)) {
                throw new InvalidPublishTokenException(
                        "srsName mismatch: token=" + tokenSrsName + " expected=" + expectedSrsName);
            }

            // 3. Expiry is intentionally skipped — the stream may already
            //    be ENDED when SRS delivers on_unpublish

            String sub = claims.getSubject();
            String streamIdStr = claims.getStringClaim("streamId");
            UUID streamId = streamIdStr != null ? UUID.fromString(streamIdStr) : null;

            return new PublishTokenClaims(sub, streamId, tokenSrsName,
                    claims.getExpirationTime() != null
                            ? claims.getExpirationTime().toInstant() : null);
        });
    }

    private byte[] signingKey() {
        return jwtProps.hmacSecret().getBytes(StandardCharsets.UTF_8);
    }

    // ── Inner types ─────────────────────────────────────────────────────────

    /** Parsed and verified publish token claims. */
    public record PublishTokenClaims(
            String sub,
            UUID streamId,
            String srsName,
            java.time.Instant expiresAt
    ) {}

    /** Thrown when publish token validation fails. */
    public static class InvalidPublishTokenException extends RuntimeException {
        public InvalidPublishTokenException(String message) {
            super(message);
        }
    }
}
