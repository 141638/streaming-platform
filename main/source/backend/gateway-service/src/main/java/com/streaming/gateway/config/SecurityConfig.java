package com.streaming.gateway.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.streaming.gateway.security.CustomServerAuthenticationEntryPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(JwtGatewayProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtGatewayProperties jwtProperties;

    public SecurityConfig(JwtGatewayProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * Public endpoints that must bypass JWT validation entirely.
     * <p>
     * A separate filter chain is required because the reactive
     * {@code AuthenticationWebFilter} (configured by
     * {@code .oauth2ResourceServer()}) runs <em>before</em> authorization —
     * even {@code permitAll()} paths would reject an expired JWT.
     * By omitting {@code oauth2ResourceServer} from this chain, requests
     * to these paths pass through regardless of bearer token validity.
     */
    @Bean
    @Order(0)
    public SecurityWebFilterChain publicEndpoints(ServerHttpSecurity http) {
        http
                .securityMatcher(new OrServerWebExchangeMatcher(
                        ServerWebExchangeMatchers.pathMatchers("/actuator/**"),
                        ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST,
                                "/api/auth/v1/login",
                                "/api/auth/v1/token/refresh",
                                "/api/auth/v1/logout",
                                "/api/auth/v1/password-reset/request",
                                "/api/auth/v1/password-reset/confirm"
                        ),
                        // SRS webhooks use publish-token auth, not JWT
                        ServerWebExchangeMatchers.pathMatchers(
                                "/api/streams/v1/webhooks/**"
                        )
                ))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // CORS is handled by Spring Cloud Gateway's globalcors config (application.yml).
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .anyExchange().permitAll()
                );
        return http.build();
    }

    /**
     * Protected endpoints — JWT validation with structured 401 responses.
     * <p>
     * This chain catches every request that the public chain does not match.
     */
    @Bean
    @Order(1)
    public SecurityWebFilterChain protectedEndpoints(
            ServerHttpSecurity http,
            ReactiveJwtDecoder decoder
    ) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(decoder))
                        .authenticationEntryPoint(new CustomServerAuthenticationEntryPoint())
                );
        return http.build();
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        String secret = jwtProperties.hmacSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "streaming.gateway.jwt.hmac-secret must be configured (JWT_HMAC_SECRET)");
        }

        // The auth service (JwtSigningKey) derives the HS256 key from the UTF-8 bytes
        // of the raw secret string via Keys.hmacShaKeyFor(secret.getBytes(UTF_8)).
        // We must use the same byte derivation to produce an identical key.
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "streaming.gateway.jwt.hmac-secret must be at least 32 UTF-8 bytes for HS256");
        }
        SecretKey secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");

        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.HS256,
                        new ImmutableSecret<>(secretKey)));
        // Accept both standard "JWT" and RFC 9068 "at+jwt" types
        jwtProcessor.setJWSTypeVerifier(
                new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt")));

        NimbusReactiveJwtDecoder decoder = new NimbusReactiveJwtDecoder(
                jwt -> Mono.fromCallable(() -> jwtProcessor.process(jwt, null))
                        .onErrorMap(com.nimbusds.jwt.proc.BadJWTException.class,
                                e -> new BadJwtException(e.getMessage(), e)));
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));

        log.info("Configured ReactiveJwtDecoder with issuer={} (secret length={} bytes)",
                jwtProperties.issuer(), secretBytes.length);
        return decoder;
    }
}
