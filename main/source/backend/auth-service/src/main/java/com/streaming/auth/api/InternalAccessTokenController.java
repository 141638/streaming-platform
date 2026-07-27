package com.streaming.auth.api;

import com.streaming.auth.dto.internal.IssueAccessTokenRequest;
import com.streaming.auth.dto.internal.IssueAccessTokenResponse;
import com.streaming.auth.dto.internal.IssueServiceTokenRequest;
import com.streaming.auth.dto.internal.IssueServiceTokenResponse;
import com.streaming.auth.service.AccessTokenIssuanceService;
import com.streaming.auth.token.IssuedAccessToken;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues access JWTs for user and service-account principals. Protected by HTTP Basic —
 * configured via {@code spring.security.user} (see {@code application.yml}); not for browsers or end users.
 *
 * <p><b>Production hardening note:</b> these endpoints are protected by shared HTTP Basic
 * credentials only. In production, add caller service-account validation: resolve the
 * authenticated principal to a registered service account and verify it is authorized to
 * issue tokens for the requested scope (user or service-account subject).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/internal")
public class InternalAccessTokenController {

    private static final Logger log = LoggerFactory.getLogger(InternalAccessTokenController.class);

    private final AccessTokenIssuanceService accessTokenIssuanceService;

    @PostMapping(
            path = "/access-tokens",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IssueAccessTokenResponse issue(@Valid @RequestBody IssueAccessTokenRequest body) {
        String caller = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName() : "unknown";
        log.info("Internal access token issued: caller={} targetUser={}", caller, body.userId());
        IssuedAccessToken issued = accessTokenIssuanceService.issueForUser(body.userId());
        return new IssueAccessTokenResponse(
                issued.accessToken(),
                "Bearer",
                issued.expiresInSeconds(),
                issued.policyVersion()
        );
    }

    /**
     * Issues a narrow service-account token (e.g. for SRS webhook).
     * Resolves policies attached via {@code principal_type = 'SERVICE_ACCOUNT'}.
     */
    @PostMapping(
            path = "/service-tokens",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IssueServiceTokenResponse issueServiceToken(@Valid @RequestBody IssueServiceTokenRequest body) {
        String caller = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName() : "unknown";
        log.info("Internal service token issued: caller={} targetPrincipal={}",
                caller, body.principalSubject());
        IssuedAccessToken issued = accessTokenIssuanceService.issueForServiceAccount(body.principalSubject());
        return new IssueServiceTokenResponse(
                issued.accessToken(),
                "Bearer",
                issued.expiresInSeconds(),
                issued.policyVersion()
        );
    }
}
