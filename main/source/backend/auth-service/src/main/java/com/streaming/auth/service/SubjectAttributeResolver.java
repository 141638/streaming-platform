package com.streaming.auth.service;

import com.streaming.auth.persistence.entity.CatalogSubjectAttributeEntity;
import com.streaming.auth.persistence.entity.UserAccountEntity;
import com.streaming.auth.persistence.repository.CatalogSubjectAttributeRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves per-user subject attribute values for JWT {@code attr} claims,
 * driven by the {@code catalog_subject_attribute} schema.
 * <p>
 * Each attribute key in the catalog is looked up for the given user;
 * keys whose resolved value is {@code null} or empty are omitted from the result.
 */
@Service
@RequiredArgsConstructor
public class SubjectAttributeResolver {

    private final CatalogSubjectAttributeRepository catalogSubjectAttributeRepository;

    private static final String KEY_ROLES = "roles";
    private static final String KEY_TIER = "tier";
    private static final String KEY_VERIFIED_STREAMER = "verified_streamer";
    private static final String KEY_USERNAME = "username";

    /**
     * Resolve all catalog-defined attribute keys to their values for {@code user}.
     *
     * @param user      the authenticated user entity (must not be {@code null})
     * @param roleSlugs the user's role slugs from {@code user_account_role}
     * @return ordered map of attribute key → value, suitable for JWT {@code attr} claim
     */
    public Map<String, Object> resolveForUser(UserAccountEntity user, List<String> roleSlugs) {
        List<CatalogSubjectAttributeEntity> catalog = catalogSubjectAttributeRepository.findAll();
        Map<String, Object> attrs = new LinkedHashMap<>(catalog.size());

        for (CatalogSubjectAttributeEntity entry : catalog) {
            Object value = resolveValue(entry.getAttributeKey(), user, roleSlugs);
            if (value != null) {
                attrs.put(entry.getAttributeKey(), value);
            }
        }
        return attrs;
    }

    private Object resolveValue(String attributeKey, UserAccountEntity user, List<String> roleSlugs) {
        return switch (attributeKey) {
            case KEY_ROLES -> resolveRoles(roleSlugs);
            case KEY_TIER -> resolveTier(user);
            case KEY_VERIFIED_STREAMER -> resolveVerifiedStreamer(user);
            case KEY_USERNAME -> resolveUsername(user);
            default -> null; // unknown attribute keys are silently skipped
        };
    }

    private List<String> resolveRoles(List<String> roleSlugs) {
        if (roleSlugs == null || roleSlugs.isEmpty()) {
            return null;
        }
        return List.copyOf(roleSlugs);
    }

    private String resolveTier(UserAccountEntity user) {
        String tierCode = user.getTierCode();
        return (tierCode == null || tierCode.isBlank()) ? null : tierCode.trim();
    }

    private Boolean resolveVerifiedStreamer(UserAccountEntity user) {
        return user.isVerifiedStreamer() ? Boolean.TRUE : null;
    }

    private String resolveUsername(UserAccountEntity user) {
        return user.getUsername(); // NEVER null per schema (NOT NULL constraint)
    }
}
