package com.streaming.pbac.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Shared Spring auto-configuration that provides a {@link ReactiveJwtDecoder}
 * bean when one is not already present.
 *
 * <p>Services add this module to their classpath and the decoder is
 * automatically registered, driven by {@code streaming.jwt.*} configuration
 * properties.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class PbacSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PbacSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ReactiveJwtDecoder.class)
    public ReactiveJwtDecoder pbacJwtDecoder(JwtProperties props) {
        byte[] secretBytes = props.hmacSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "streaming.jwt.hmac-secret must be at least 32 UTF-8 bytes for HS256");
        }
        SecretKeySpec keySpec = new SecretKeySpec(secretBytes, "HmacSHA256");

        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.HS256,
                        new ImmutableSecret<>(keySpec)));
        // Accept both standard "JWT" and RFC 9068 "at+jwt" types
        jwtProcessor.setJWSTypeVerifier(
                new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt")));

        NimbusReactiveJwtDecoder decoder = new NimbusReactiveJwtDecoder(
                jwt -> Mono.fromCallable(() -> jwtProcessor.process(jwt, null))
                        .onErrorMap(com.nimbusds.jwt.proc.BadJWTException.class,
                                e -> new BadJwtException(e.getMessage(), e)));
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));

        log.info("Configured shared ReactiveJwtDecoder with issuer={} (secret length={} bytes)",
                props.issuer(), secretBytes.length);
        return decoder;
    }
}
