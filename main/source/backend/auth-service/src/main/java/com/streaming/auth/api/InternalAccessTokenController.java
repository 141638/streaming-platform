package com.streaming.auth.api;

import com.streaming.auth.dto.internal.IssueAccessTokenRequest;
import com.streaming.auth.dto.internal.IssueAccessTokenResponse;
import com.streaming.auth.service.AccessTokenIssuanceService;
import com.streaming.auth.token.IssuedAccessToken;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues access JWTs given only a user id (credentials check is assumed upstream). Protected by HTTP Basic —
 * configured via {@code spring.security.user} (see {@code application.yml}); not for browsers or end users.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/internal")
public class InternalAccessTokenController {
    private final AccessTokenIssuanceService accessTokenIssuanceService;

    @PostMapping(
            path = "/access-tokens",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IssueAccessTokenResponse issue(@Valid @RequestBody IssueAccessTokenRequest body) {
        IssuedAccessToken issued = accessTokenIssuanceService.issueForUser(body.userId());
        return new IssueAccessTokenResponse(
                issued.accessToken(),
                "Bearer",
                issued.expiresInSeconds(),
                issued.policyVersion()
        );
    }
}
