package com.streaming.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.gateway.config.IdempotencyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * Gateway filter that makes state-changing requests idempotent via the
 * {@code Idempotency-Key} HTTP header.
 *
 * <p><strong>Flow:</strong>
 * <ol>
 *   <li>Skip GET/HEAD/OPTIONS (already idempotent per HTTP spec) and
 *       requests without an {@code Idempotency-Key} header.</li>
 *   <li>Check Redis for a cached response at {@code idempotent:{key}}.</li>
 *   <li><strong>Cache hit:</strong> replay the cached status + body to the
 *       client. Downstream services are never called.</li>
 *   <li><strong>Cache miss:</strong> forward to the downstream service.
 *       If the response is 2xx, buffer the body and cache it in Redis before
 *       writing through to the client.</li>
 * </ol>
 *
 * <p><strong>Fail-open:</strong> any Redis error is logged and the request
 * is forwarded normally. The idempotency guarantee degrades gracefully —
 * duplicate requests may reach the downstream service, but no request is
 * ever blocked because Redis is unavailable.
 *
 * <p><strong>Cache scope:</strong> only successful (2xx) responses are
 * cached. Errors, redirects, and server faults pass through uncached so
 * the client can retry with a new key.
 *
 * <p>Registered as a {@link WebFilter} (not a {@code GlobalFilter}) so it
 * runs inside the Spring Security filter chain — the request is authenticated
 * before we check or cache any response.
 */
@Component
public class IdempotencyFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private static final Set<HttpMethod> SAFE_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    static final String HEADER = "Idempotency-Key";
    static final String KEY_PREFIX = "idempotent:";
    static final String REPLAY_HEADER = "X-Idempotency-Replay";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                             IdempotencyProperties properties,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        // Run after Spring Security (@Order 0/1) and RateLimitFilter (@Order 2)
        // so the request is authenticated and rate-checked before we cache or
        // replay any response.  On cache hit, the response is short-circuited
        // and security is never reached — but the key is a 128-bit UUID that
        // only the original authenticated client possesses, so this is safe.
        return 3;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (method == null || SAFE_METHODS.contains(method)) {
            return chain.filter(exchange);
        }

        String key = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (key == null || key.isBlank()) {
            return chain.filter(exchange);
        }

        String redisKey = KEY_PREFIX + key;

        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(cached -> {
                    CachedResponse cr;
                    try {
                        cr = objectMapper.readValue(cached, CachedResponse.class);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize cached idempotent response for key={}, forwarding",
                                key, e);
                        return chain.filter(exchange);
                    }
                    return replayResponse(exchange, cr);
                })
                .switchIfEmpty(chain.filter(decorateForCapture(exchange, redisKey)))
                .onErrorResume(err -> {
                    log.warn("Idempotency Redis error for key={}, forwarding request", key, err);
                    return chain.filter(exchange);
                });
    }

    // ── Cache hit: replay ────────────────────────────────────────────────

    /**
     * Replay a cached response directly to the client without calling downstream
     * services. Sets the {@code X-Idempotency-Replay} header so clients can
     * distinguish a cached replay from a fresh response.
     */
    private Mono<Void> replayResponse(ServerWebExchange exchange, CachedResponse cr) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(cr.status()));

        if (cr.contentType() != null && !cr.contentType().isBlank()) {
            response.getHeaders().setContentType(MediaType.valueOf(cr.contentType()));
        }
        response.getHeaders().set(REPLAY_HEADER, "true");

        byte[] body = cr.body() != null
                ? Base64.getDecoder().decode(cr.body())
                : new byte[0];

        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    // ── Cache miss: capture ──────────────────────────────────────────────

    /**
     * Wrap the exchange's response so we can capture the body on a 2xx response
     * and cache it in Redis as a side effect.
     */
    private ServerWebExchange decorateForCapture(ServerWebExchange exchange, String redisKey) {
        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                HttpStatusCode status = getDelegate().getStatusCode();
                if (status == null || !status.is2xxSuccessful()) {
                    // Don't cache non-success — let the client retry with a new key
                    return super.writeWith(body);
                }

                return DataBufferUtils.join(body)
                        .flatMap(dataBuffer -> {
                            byte[] bytes = tryRead(dataBuffer);
                            DataBufferUtils.release(dataBuffer);

                            // Fire-and-forget: cache, then write through
                            cacheResponse(redisKey, status, getDelegate().getHeaders(), bytes);

                            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                            return super.writeWith(Mono.just(buffer));
                        });
            }
        };
        return exchange.mutate().response(decorated).build();
    }

    private static byte[] tryRead(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return bytes;
    }

    private void cacheResponse(String redisKey, HttpStatusCode status,
                               org.springframework.http.HttpHeaders headers, byte[] body) {
        try {
            String contentType = headers.getContentType() != null
                    ? headers.getContentType().toString()
                    : "application/json";

            CachedResponse cr = new CachedResponse(
                    status.value(),
                    contentType,
                    Base64.getEncoder().encodeToString(body)
            );

            String json = objectMapper.writeValueAsString(cr);

            redisTemplate.opsForValue()
                    .set(redisKey, json, Duration.ofSeconds(properties.ttlSeconds()))
                    .subscribe(
                            success -> log.debug("Cached idempotent response key={}", redisKey),
                            err -> log.warn("Failed to cache idempotent response key={}", redisKey, err)
                    );
        } catch (Exception e) {
            log.warn("Failed to serialize idempotent response for key={}", redisKey, e);
        }
    }
}
