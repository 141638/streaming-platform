package com.streaming.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.auth.persistence.entity.PolicyEntity;
import com.streaming.auth.persistence.entity.UserAccountEntity;
import com.streaming.auth.persistence.entity.UserAccountRoleEntity;
import com.streaming.auth.persistence.repository.PolicyAttachmentRepository;
import com.streaming.auth.persistence.repository.PolicyRepository;
import com.streaming.auth.persistence.repository.UserAccountRepository;
import com.streaming.auth.persistence.repository.UserAccountRoleRepository;
import com.streaming.auth.token.IssuedAccessToken;
import com.streaming.auth.token.JwtIssuerProperties;
import com.streaming.auth.token.policy.EntitlementLinesMaterializer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccessTokenIssuanceService")
class AccessTokenIssuanceServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private UserAccountRoleRepository userAccountRoleRepository;
    @Mock
    private PolicyAttachmentRepository policyAttachmentRepository;
    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private EntitlementLinesMaterializer entitlementLinesMaterializer;
    @Mock
    private SubjectAttributeResolver subjectAttributeResolver;

    private AccessTokenIssuanceService service;

    private static final SecretKey SIGNING_KEY =
            Keys.hmacShaKeyFor(Base64.getDecoder().decode(
                    "MNH6CwQ7H4xAf69hpn0sc2Rn+wxT/d+I9QWELikQqgM="));
    private static final JwtIssuerProperties PROPS = new JwtIssuerProperties(
            "https://auth.streaming.local",
            "streaming-control-plane",
            900,
            604800,
            300,
            "test-secret",
            "stream-service-internal",
            3600,
            true,
            false,
            "Lax",
            "/api/auth"
    );

    private static final UUID USER_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new AccessTokenIssuanceService(
                userAccountRepository,
                userAccountRoleRepository,
                policyAttachmentRepository,
                policyRepository,
                entitlementLinesMaterializer,
                subjectAttributeResolver,
                PROPS,
                SIGNING_KEY
        );
    }

    @Nested
    @DisplayName("issueForUser")
    class IssueForUserTests {

        @Test
        @DisplayName("produces JWT with attr from SubjectAttributeResolver")
        void buildsJwtWithResolvedAttributes() {
            UserAccountEntity user = userEntity(USER_ID);
            user.setTierCode("PRO");
            user.setVerifiedStreamer(true);

            List<String> roleSlugs = List.of("streamer", "viewer");
            List<UserAccountRoleEntity> roleEntities = roleSlugs.stream()
                    .map(slug -> {
                        UserAccountRoleEntity r = new UserAccountRoleEntity();
                        r.setUserAccountId(USER_ID);
                        r.setRoleSlug(slug);
                        r.setGrantedAt(NOW);
                        return r;
                    })
                    .toList();

            UUID policyId = UUID.randomUUID();
            PolicyEntity policy = policyEntity(policyId, "policy.streamer.live",
                    "{\"statements\":[{\"effect\":\"allow\",\"resource\":\"stream:session:self\",\"actions\":[\"create\",\"read\"]}]}");

            Map<String, Object> attr = Map.of(
                    "roles", List.of("streamer", "viewer"),
                    "tier", "PRO",
                    "verified_streamer", Boolean.TRUE
            );

            when(userAccountRepository.findByIdAndDeleteFlagFalse(USER_ID)).thenReturn(Optional.of(user));
            when(userAccountRoleRepository.findByUserAccountId(USER_ID)).thenReturn(roleEntities);
            when(policyAttachmentRepository.findDistinctPolicyIdsByPrincipalUser(USER_ID)).thenReturn(List.of());
            when(policyAttachmentRepository.findDistinctPolicyIdsByRoleSlugs(roleSlugs)).thenReturn(List.of(policyId));
            when(policyRepository.findAllByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of(policy));
            when(entitlementLinesMaterializer.linesFromPolicies(any())).thenReturn(
                    List.of("allow stream:session:self create read"));
            when(subjectAttributeResolver.resolveForUser(user, roleSlugs)).thenReturn(attr);

            IssuedAccessToken issued = service.issueForUser(USER_ID);

            assertThat(issued.accessToken()).isNotBlank();
            assertThat(issued.expiresInSeconds()).isEqualTo(900);

            // Decode and verify claims
            Jwt<?, ?> parsed = Jwts.parser()
                    .verifyWith(SIGNING_KEY)
                    .build()
                    .parse(issued.accessToken());

            assertThat(parsed.getHeader().getType()).isEqualTo("at+jwt");
            Claims claims = (Claims) parsed.getPayload();
            assertThat(claims.getIssuer()).isEqualTo("https://auth.streaming.local");
            assertThat(claims.getAudience()).contains("streaming-control-plane");
            assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
            assertThat(claims.get("ver", Integer.class)).isEqualTo(1);
            assertThat(claims.get("ent", List.class)).contains("allow stream:session:self create read");

            @SuppressWarnings("unchecked")
            Map<String, Object> parsedAttr = claims.get("attr", Map.class);
            assertThat(parsedAttr).containsKeys("roles", "tier", "verified_streamer");
        }

        @Test
        @DisplayName("includes attr even when user has no tier or verified_streamer")
        void userWithRolesOnly() {
            UserAccountEntity user = userEntity(USER_ID);
            List<String> roleSlugs = List.of("viewer");
            List<UserAccountRoleEntity> roleEntities = List.of(roleEntity(USER_ID, "viewer"));

            UUID policyId = UUID.randomUUID();
            PolicyEntity policy = policyEntity(policyId, "policy.viewer.base",
                    "{\"statements\":[{\"effect\":\"allow\",\"resource\":\"media:playback:*\",\"actions\":[\"read\"]}]}");

            Map<String, Object> attr = Map.of("roles", List.of("viewer"));

            when(userAccountRepository.findByIdAndDeleteFlagFalse(USER_ID)).thenReturn(Optional.of(user));
            when(userAccountRoleRepository.findByUserAccountId(USER_ID)).thenReturn(roleEntities);
            when(policyAttachmentRepository.findDistinctPolicyIdsByPrincipalUser(USER_ID)).thenReturn(List.of());
            when(policyAttachmentRepository.findDistinctPolicyIdsByRoleSlugs(roleSlugs)).thenReturn(List.of(policyId));
            when(policyRepository.findAllByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of(policy));
            when(entitlementLinesMaterializer.linesFromPolicies(any())).thenReturn(
                    List.of("allow media:playback:* read"));
            when(subjectAttributeResolver.resolveForUser(user, roleSlugs)).thenReturn(attr);

            IssuedAccessToken issued = service.issueForUser(USER_ID);

            Jwt<?, ?> parsed = Jwts.parser()
                    .verifyWith(SIGNING_KEY)
                    .build()
                    .parse(issued.accessToken());
            Claims claims = (Claims) parsed.getPayload();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsedAttr = claims.get("attr", Map.class);
            assertThat(parsedAttr).containsOnlyKeys("roles");
        }
    }

    @Nested
    @DisplayName("issueForServiceAccount")
    class IssueForServiceAccountTests {

        @Test
        @DisplayName("produces service token with correct sub, aud, and narrow ent")
        void validPrincipal_producesServiceToken() {
            UUID policyId = UUID.randomUUID();
            PolicyEntity policy = policyEntity(policyId, "policy.service.srs-webhook",
                    "{\"statements\":[{\"effect\":\"allow\",\"resource\":\"stream:publish-key:*\",\"actions\":[\"validate_publish\"]}]}");

            when(policyAttachmentRepository.findDistinctPolicyIdsByPrincipalSubject("svc:srs-webhook"))
                    .thenReturn(List.of(policyId));
            when(policyRepository.findAllByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of(policy));
            when(entitlementLinesMaterializer.linesFromPolicies(any())).thenReturn(
                    List.of("allow stream:publish-key:* validate_publish"));

            IssuedAccessToken issued = service.issueForServiceAccount("svc:srs-webhook");

            assertThat(issued.accessToken()).isNotBlank();
            assertThat(issued.expiresInSeconds()).isEqualTo(3600);

            Jwt<?, ?> parsed = Jwts.parser()
                    .verifyWith(SIGNING_KEY)
                    .build()
                    .parse(issued.accessToken());
            Claims claims = (Claims) parsed.getPayload();

            assertThat(claims.getSubject()).isEqualTo("svc:srs-webhook");
            assertThat(claims.getAudience()).contains("stream-service-internal");
            assertThat(claims.getIssuer()).isEqualTo("https://auth.streaming.local");
            assertThat(claims.get("ver", Integer.class)).isEqualTo(1);
            assertThat(claims.get("ent", List.class)).contains("allow stream:publish-key:* validate_publish");
            // Service tokens have no attr claim
            assertThat(claims.containsKey("attr")).isFalse();
        }

        @Test
        @DisplayName("throws IllegalArgumentException when no policy attachments found")
        void unknownPrincipal_throws() {
            when(policyAttachmentRepository.findDistinctPolicyIdsByPrincipalSubject("svc:unknown"))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.issueForServiceAccount("svc:unknown"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No policy attachments found");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when principalSubject is blank")
        void blankPrincipal_throws() {
            assertThatThrownBy(() -> service.issueForServiceAccount("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when principalSubject is null")
        void nullPrincipal_throws() {
            assertThatThrownBy(() -> service.issueForServiceAccount(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when attached policies are all disabled")
        void allPoliciesDisabled_throws() {
            UUID policyId = UUID.randomUUID();
            when(policyAttachmentRepository.findDistinctPolicyIdsByPrincipalSubject("svc:srs-webhook"))
                    .thenReturn(List.of(policyId));
            when(policyRepository.findAllByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of());

            assertThatThrownBy(() -> service.issueForServiceAccount("svc:srs-webhook"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No enabled policies found");
        }

        @Test
        @DisplayName("trims principalSubject whitespace")
        void trimsPrincipalSubject() {
            UUID policyId = UUID.randomUUID();
            PolicyEntity policy = policyEntity(policyId, "policy.service.srs-webhook",
                    "{\"statements\":[{\"effect\":\"allow\",\"resource\":\"stream:publish-key:*\",\"actions\":[\"validate_publish\"]}]}");

            when(policyAttachmentRepository.findDistinctPolicyIdsByPrincipalSubject("svc:srs-webhook"))
                    .thenReturn(List.of(policyId));
            when(policyRepository.findAllByIdInAndEnabledTrue(anyCollection())).thenReturn(List.of(policy));
            when(entitlementLinesMaterializer.linesFromPolicies(any())).thenReturn(
                    List.of("allow stream:publish-key:* validate_publish"));

            IssuedAccessToken issued = service.issueForServiceAccount("  svc:srs-webhook  ");

            Jwt<?, ?> parsed = Jwts.parser()
                    .verifyWith(SIGNING_KEY)
                    .build()
                    .parse(issued.accessToken());
            Claims claims = (Claims) parsed.getPayload();
            assertThat(claims.getSubject()).isEqualTo("svc:srs-webhook");
        }
    }

    private static UserAccountEntity userEntity(UUID id) {
        UserAccountEntity user = new UserAccountEntity();
        user.setId(id);
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setPasswordHash("hashed");
        user.setCreatedAt(NOW);
        user.setUpdatedAt(NOW);
        user.setDeleteFlag(false);
        return user;
    }

    private static UserAccountRoleEntity roleEntity(UUID userId, String slug) {
        UserAccountRoleEntity r = new UserAccountRoleEntity();
        r.setUserAccountId(userId);
        r.setRoleSlug(slug);
        r.setGrantedAt(NOW);
        return r;
    }

    private static PolicyEntity policyEntity(UUID id, String key, String definition) {
        PolicyEntity p = new PolicyEntity();
        p.setId(id);
        p.setPolicyKey(key);
        p.setVersion(1);
        p.setDefinition(definition);
        p.setDefinitionFormat("JSON");
        p.setEnabled(true);
        p.setCreatedAt(NOW);
        p.setUpdatedAt(NOW);
        return p;
    }
}
