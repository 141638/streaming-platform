package com.streaming.auth.service;

import com.streaming.auth.authorization.EntitlementGrammarVersion;
import com.streaming.auth.persistence.repository.CatalogSubjectAttributeRepository;
import com.streaming.auth.persistence.repository.PolicyAttachmentRepository;
import com.streaming.auth.persistence.entity.PolicyEntity;
import com.streaming.auth.persistence.repository.PolicyRepository;
import com.streaming.auth.persistence.entity.UserAccountEntity;
import com.streaming.auth.persistence.repository.UserAccountRepository;
import com.streaming.auth.persistence.entity.UserAccountRoleEntity;
import com.streaming.auth.persistence.repository.UserAccountRoleRepository;
import com.streaming.auth.token.IssuedAccessToken;
import com.streaming.auth.token.JwtIssuerProperties;
import com.streaming.auth.exception.UserAccountNotFoundException;
import com.streaming.auth.token.policy.EntitlementLinesMaterializer;
import io.jsonwebtoken.Jwts;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues HS256-signed access JWTs carrying PBAC claims ({@code ent}, {@code pv}, {@code ver}, {@code attr})
 * for user principals (via {@link #issueForUser(UUID)}) and service-account principals
 * (via {@link #issueForServiceAccount(String)}).
 */
@Service
@RequiredArgsConstructor
public class AccessTokenIssuanceService {

    private final UserAccountRepository userAccountRepository;
    private final UserAccountRoleRepository userAccountRoleRepository;
    private final PolicyAttachmentRepository policyAttachmentRepository;
    private final PolicyRepository policyRepository;
    private final EntitlementLinesMaterializer entitlementLinesMaterializer;
    private final SubjectAttributeResolver subjectAttributeResolver;
    private final JwtIssuerProperties jwtIssuerProperties;
    private final SecretKey accessTokenSigningKey;

    /** @throws UserAccountNotFoundException when user absent or logically deleted */
    @Transactional
    public IssuedAccessToken issueForUser(UUID userId) {
        UserAccountEntity user =
                userAccountRepository
                        .findByIdAndDeleteFlagFalse(userId)
                        .orElseThrow(() -> new UserAccountNotFoundException(userId));

        List<String> roleSlugs =
                userAccountRoleRepository.findByUserAccountId(userId).stream()
                        .sorted(Comparator.comparing(UserAccountRoleEntity::getRoleSlug))
                        .map(UserAccountRoleEntity::getRoleSlug)
                        .toList();

        LinkedHashSet<UUID> policyIds = new LinkedHashSet<>(
                policyAttachmentRepository.findDistinctPolicyIdsByPrincipalUser(userId)
        );
        if (!roleSlugs.isEmpty()) {
            policyIds.addAll(policyAttachmentRepository.findDistinctPolicyIdsByRoleSlugs(roleSlugs));
        }

        List<PolicyEntity> policies = new ArrayList<>(policyRepository.findAllByIdInAndEnabledTrue(policyIds));
        policies.sort(
                Comparator.comparing(PolicyEntity::getPolicyKey).thenComparingInt(PolicyEntity::getVersion));

        List<String> definitions =
                policies.stream().map(PolicyEntity::getDefinition).toList();
        List<String> entLines = entitlementLinesMaterializer.linesFromPolicies(definitions);

        OffsetDateTime policyVersionInstant =
                policies.stream()
                        .map(PolicyEntity::getUpdatedAt)
                        .max(Comparator.naturalOrder())
                        .orElse(OffsetDateTime.now(ZoneOffset.UTC));
        String policyVersion = policyVersionInstant.toString();

        long issuedAtMs = System.currentTimeMillis();
        long ttlMs = jwtIssuerProperties.accessTokenTtlSeconds() * 1000L;
        long expiresAtMs = issuedAtMs + ttlMs;
        Date issuedAt = new Date(issuedAtMs);
        Date expiresAt = new Date(expiresAtMs);
        String jti = UUID.randomUUID().toString();

        Map<String, Object> attr = subjectAttributeResolver.resolveForUser(user, roleSlugs);

        String compact =
                Jwts.builder()
                        .header()
                        .type("at+jwt")
                        .and()
                        .issuer(jwtIssuerProperties.issuer())
                        .subject(user.getId().toString())
                        .audience()
                        .add(jwtIssuerProperties.audience())
                        .and()
                        .issuedAt(issuedAt)
                        .expiration(expiresAt)
                        .id(jti)
                        .claim("ver", EntitlementGrammarVersion.current().numericClaim())
                        .claim("pv", policyVersion)
                        .claim("ent", entLines)
                        .claim("attr", attr)
                        .signWith(accessTokenSigningKey, Jwts.SIG.HS256)
                        .compact();

        return new IssuedAccessToken(compact, jwtIssuerProperties.accessTokenTtlSeconds(), policyVersion);
    }

    /**
     * Issues a service-account access token whose {@code sub} is the principal subject slug
     * (e.g. {@code svc:srs-webhook}) and {@code aud} is the service-internal audience.
     * <p>
     * Only policies attached via {@code principal_type = 'SERVICE_ACCOUNT'} are resolved.
     * The token carries no {@code attr} claim (no user attributes).
     *
     * @param principalSubject the service-account principal subject (must match a
     *                         {@code policy_attachment.principal_subject})
     * @throws IllegalArgumentException when no enabled policies are attached for the given principal
     */
    @Transactional
    public IssuedAccessToken issueForServiceAccount(String principalSubject) {
        if (principalSubject == null || principalSubject.isBlank()) {
            throw new IllegalArgumentException("principalSubject must not be blank");
        }

        LinkedHashSet<UUID> policyIds = new LinkedHashSet<>(
                policyAttachmentRepository.findDistinctPolicyIdsByPrincipalSubject(principalSubject.trim())
        );

        if (policyIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "No policy attachments found for service account: " + principalSubject.trim());
        }

        List<PolicyEntity> policies = new ArrayList<>(policyRepository.findAllByIdInAndEnabledTrue(policyIds));
        if (policies.isEmpty()) {
            throw new IllegalArgumentException(
                    "No enabled policies found for service account: " + principalSubject.trim());
        }
        policies.sort(
                Comparator.comparing(PolicyEntity::getPolicyKey).thenComparingInt(PolicyEntity::getVersion));

        List<String> definitions =
                policies.stream().map(PolicyEntity::getDefinition).toList();
        List<String> entLines = entitlementLinesMaterializer.linesFromPolicies(definitions);

        OffsetDateTime policyVersionInstant =
                policies.stream()
                        .map(PolicyEntity::getUpdatedAt)
                        .max(Comparator.naturalOrder())
                        .orElse(OffsetDateTime.now(ZoneOffset.UTC));
        String policyVersion = policyVersionInstant.toString();

        long issuedAtMs = System.currentTimeMillis();
        long ttlMs = jwtIssuerProperties.serviceTokenTtlSeconds() * 1000L;
        long expiresAtMs = issuedAtMs + ttlMs;
        Date issuedAt = new Date(issuedAtMs);
        Date expiresAt = new Date(expiresAtMs);
        String jti = UUID.randomUUID().toString();

        String compact =
                Jwts.builder()
                        .header()
                        .type("at+jwt")
                        .and()
                        .issuer(jwtIssuerProperties.issuer())
                        .subject(principalSubject.trim())
                        .audience()
                        .add(jwtIssuerProperties.serviceAudience())
                        .and()
                        .issuedAt(issuedAt)
                        .expiration(expiresAt)
                        .id(jti)
                        .claim("ver", EntitlementGrammarVersion.current().numericClaim())
                        .claim("pv", policyVersion)
                        .claim("ent", entLines)
                        .signWith(accessTokenSigningKey, Jwts.SIG.HS256)
                        .compact();

        return new IssuedAccessToken(compact, jwtIssuerProperties.serviceTokenTtlSeconds(), policyVersion);
    }

    /** Dev convenience: subject string suitable for {@code sub} without DB. */
    public static String subjectForUserId(UUID userId) {
        return userId.toString();
    }

    /**
     * Decodes {@code sub} as UUID for ownership checks in domain services (when sub is the user id).
     */
    public static UUID userIdFromSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is blank");
        }
        return UUID.fromString(subject.trim());
    }
}
