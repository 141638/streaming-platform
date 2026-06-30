package com.streaming.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.streaming.auth.persistence.entity.CatalogSubjectAttributeEntity;
import com.streaming.auth.persistence.entity.UserAccountEntity;
import com.streaming.auth.persistence.repository.CatalogSubjectAttributeRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubjectAttributeResolver")
class SubjectAttributeResolverTest {

    @Mock
    private CatalogSubjectAttributeRepository catalogSubjectAttributeRepository;

    private SubjectAttributeResolver resolver;

    private static final List<CatalogSubjectAttributeEntity> CATALOG = List.of(
            entity("roles", "STRING_ARRAY"),
            entity("tier", "STRING"),
            entity("verified_streamer", "BOOLEAN")
    );

    @BeforeEach
    void setUp() {
        resolver = new SubjectAttributeResolver(catalogSubjectAttributeRepository);
        when(catalogSubjectAttributeRepository.findAll()).thenReturn(CATALOG);
    }

    @Nested
    @DisplayName("resolveForUser")
    class ResolveForUser {

        @Test
        @DisplayName("resolves all three attributes when user has tier and verified_streamer")
        void allAttributesPresent() {
            UserAccountEntity user = new UserAccountEntity();
            user.setTierCode("PRO");
            user.setVerifiedStreamer(true);

            Map<String, Object> result = resolver.resolveForUser(user, List.of("streamer", "viewer"));

            assertThat(result).containsKeys("roles", "tier", "verified_streamer");
            assertThat(result.get("roles")).isEqualTo(List.of("streamer", "viewer"));
            assertThat(result.get("tier")).isEqualTo("PRO");
            assertThat(result.get("verified_streamer")).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("omits tier when user has null tier_code")
        void nullTier_omitted() {
            UserAccountEntity user = new UserAccountEntity();
            user.setTierCode(null);
            user.setVerifiedStreamer(false);

            Map<String, Object> result = resolver.resolveForUser(user, List.of("viewer"));

            assertThat(result).containsKeys("roles");
            assertThat(result).doesNotContainKeys("tier", "verified_streamer");
        }

        @Test
        @DisplayName("omits verified_streamer when user is not verified")
        void notVerified_omitted() {
            UserAccountEntity user = new UserAccountEntity();
            user.setTierCode("FREE");
            user.setVerifiedStreamer(false);

            Map<String, Object> result = resolver.resolveForUser(user, List.of("viewer"));

            assertThat(result).containsKeys("roles", "tier");
            assertThat(result).doesNotContainKey("verified_streamer");
        }

        @Test
        @DisplayName("omits roles when roleSlugs is empty")
        void emptyRoles_omitted() {
            UserAccountEntity user = new UserAccountEntity();
            user.setTierCode("PRO");
            user.setVerifiedStreamer(true);

            Map<String, Object> result = resolver.resolveForUser(user, List.of());

            assertThat(result).containsKeys("tier", "verified_streamer");
            assertThat(result).doesNotContainKey("roles");
        }

        @Test
        @DisplayName("omits roles when roleSlugs is null")
        void nullRoles_omitted() {
            UserAccountEntity user = new UserAccountEntity();
            user.setTierCode("FREE");
            user.setVerifiedStreamer(false);

            Map<String, Object> result = resolver.resolveForUser(user, null);

            assertThat(result).containsKeys("tier");
            assertThat(result).doesNotContainKeys("roles", "verified_streamer");
        }

        @Test
        @DisplayName("omits blank tier_code")
        void blankTier_omitted() {
            UserAccountEntity user = new UserAccountEntity();
            user.setTierCode("   ");
            user.setVerifiedStreamer(true);

            Map<String, Object> result = resolver.resolveForUser(user, List.of("admin"));

            assertThat(result).containsKeys("roles", "verified_streamer");
            assertThat(result).doesNotContainKey("tier");
        }

        @Test
        @DisplayName("roles list is an immutable copy")
        void rolesIsImmutableCopy() {
            UserAccountEntity user = new UserAccountEntity();
            List<String> mutableRoles = new java.util.ArrayList<>(List.of("viewer"));

            Map<String, Object> result = resolver.resolveForUser(user, mutableRoles);
            @SuppressWarnings("unchecked")
            List<String> resolved = (List<String>) result.get("roles");

            assertThat(resolved).isEqualTo(List.of("viewer"));
            // modifying the original list does not affect the resolved list
            mutableRoles.add("admin");
            assertThat(resolved).hasSize(1);
        }
    }

    private static CatalogSubjectAttributeEntity entity(String key, String kind) {
        CatalogSubjectAttributeEntity e = new CatalogSubjectAttributeEntity();
        e.setAttributeKey(key);
        e.setJsonValueKind(kind);
        e.setDescription("Test: " + key);
        return e;
    }
}
