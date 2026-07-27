package com.streaming.gateway.filter;

import com.streaming.gateway.config.RateLimitProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gateway-level rate limiting via a Redis-backed sliding-window-log algorithm.
 *
 * <p><strong>Algorithm:</strong> each client IP owns a Redis sorted set keyed
 * on epoch-millisecond timestamps.  An atomic Lua script prunes expired
 * entries, counts the remainder, records this request, and returns the count.
 * If the count exceeds the configured limit the filter short-circuits with
 * HTTP 429.
 *
 * <p><strong>Order:</strong> runs at {@code @Order(2)} — after Spring Security
 * ({@code @Order(0)} public, {@code @Order(1)} protected) but before the
 * {@link IdempotencyFilter} ({@code @Order(3)}).  Rate limiting should
 * reject excessive traffic before any idempotency-cache work is done.
 *
 * <p><strong>Fail-open:</strong> any Redis error (connection refused, script
 * failure, timeout) is logged and the request is forwarded normally.  Rate
 * limiting degrades gracefully — no request is ever blocked because Redis is
 * unavailable.
 *
 * <p><strong>IP resolution:</strong> honours {@code X-Forwarded-For} when
 * present (the gateway is the entry point and trusts its own header);
 * otherwise falls back to the socket remote address.
 */
@Component
public class RateLimitFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String KEY_PREFIX = "rl:";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RateLimitProperties properties;
    private final RedisScript<Long> script;

    public RateLimitFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                           RateLimitProperties properties) throws IOException {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        String lua = new ClassPathResource("redis/rate_limit.lua")
                .getContentAsString(Objects.requireNonNull(StandardCharsets.UTF_8));
        this.script = new DefaultRedisScript<>(lua, Long.class);
    }

    @Override
    public int getOrder() {
        // Run after Spring Security (@Order 0/1) so the request is
        // authenticated, but before IdempotencyFilter (@Order 3) so
        // excessive traffic is rejected before any cache work.
        return 2;
    }

    @Override
    public @NonNull Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        String ip = resolveIp(exchange);
        String key = KEY_PREFIX + ip;
        long nowMs = System.currentTimeMillis();
        String nonce = String.valueOf(ThreadLocalRandom.current().nextInt());

        return Objects.requireNonNull(redisTemplate.execute(Objects.requireNonNull(script),
                        Objects.requireNonNull(List.of(key)),
                        Objects.requireNonNull(List.of(
                                String.valueOf(nowMs),
                                String.valueOf(properties.windowMs()),
                                String.valueOf(properties.limit()),
                                String.valueOf(properties.ttlSeconds()),
                                nonce)))
                .next()  // Flux<Long> → Mono<Long> (script returns a single value)
                .flatMap(count -> {
                    if (count > properties.limit()) {
                        return write429(exchange, ip, count);
                    }
                    return chain.filter(exchange);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Rate-limit Lua script returned empty result for key={}, forwarding", key);
                    return chain.filter(exchange);
                }))
                .onErrorResume(err -> {
                    log.warn("Rate-limit Redis error key={} ip={}, failing open", key, ip, err);
                    return chain.filter(exchange);
                }));
    }

    // ── IP resolution ──────────────────────────────────────────────────

    /**
     * Resolve the client IP, preferring {@code X-Forwarded-For} when present.
     *
     * <p>In local dev (no reverse proxy) this returns {@code 127.0.0.1}.
     * In deployment the gateway sets {@code X-Forwarded-For} from the real
     * client address.
     */
    private String resolveIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For can be a comma-separated chain; the leftmost is the client
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    // ── 429 response ────────────────────────────────────────────────────

    /**
     * Write a structured 429 Too Many Requests response.
     *
     * <p>Body format matches the existing {@code Structured401AuthenticationEntryPoint}
     * convention: {@code {"error":"...","error_code":"...","message":"..."}}.
     */
    private Mono<Void> write429(ServerWebExchange exchange, String ip, long count) {
        long retryAfter = properties.windowSeconds();

        String body = String.format(
                "{\"error\":\"Too Many Requests\"," +
                        "\"error_code\":\"rate_limit_exceeded\"," +
                        "\"message\":\"Rate limit exceeded. Retry after %d seconds.\"}",
                retryAfter);

        log.warn("Rate limit exceeded — ip={} count={} limit={} path={}",
                ip, count, properties.limit(),
                exchange.getRequest().getURI().getPath());

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfter));

        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(Objects.requireNonNull(body.getBytes(StandardCharsets.UTF_8)));
        return exchange.getResponse().writeWith(Objects.requireNonNull(Mono.just(buffer)));
    }
}
