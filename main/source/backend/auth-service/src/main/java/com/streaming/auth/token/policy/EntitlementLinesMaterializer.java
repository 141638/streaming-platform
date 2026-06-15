package com.streaming.auth.token.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.auth.authorization.EntitlementStatements;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Turns policy.definition JSON strings into JWT {@code ent} lines ({@linkplain EntitlementStatements} grammar). */
@Component
public class EntitlementLinesMaterializer {

    private final ObjectMapper objectMapper;

    public EntitlementLinesMaterializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @return deterministic order: first occurrence wins; duplicates collapsed */
    public List<String> linesFromPolicies(Collection<String> definitionJsonBodies) {
        LinkedHashSet<String> orderedDistinct = new LinkedHashSet<>();
        for (String body : definitionJsonBodies) {
            if (body == null || body.isBlank()) {
                continue;
            }
            PolicyStatementsDocument doc;
            try {
                doc = objectMapper.readValue(body, PolicyStatementsDocument.class);
            } catch (Exception e) {
                throw new IllegalStateException("policy.definition JSON is unreadable", e);
            }
            for (PolicyStatementsDocument.Statement st : doc.statements()) {
                String line = toEntLine(st);
                if (!line.isEmpty()) {
                    orderedDistinct.add(line);
                }
            }
        }
        return List.copyOf(orderedDistinct);
    }

    /**
     * {@code deny} statements are skipped here (optional explicit-deny modeled later). Unknown effects ignored.
     */
    private static String toEntLine(PolicyStatementsDocument.Statement statement) {
        if (statement.effect() == null || statement.resource() == null || statement.resource().isBlank()) {
            return "";
        }
        if (!"allow".equalsIgnoreCase(statement.effect().trim())) {
            return "";
        }
        if (statement.actions().isEmpty()) {
            return "";
        }
        String resource = Objects.requireNonNull(statement.resource()).trim();
        List<String> actionStrings = normalizeActions(statement.actions());
        if (actionStrings.isEmpty()) {
            return "";
        }
        return EntitlementStatements.allowLine(resource, actionStrings);
    }

    private static List<String> normalizeActions(List<String> actions) {
        List<String> out = new ArrayList<>();
        for (String a : actions) {
            if (a != null && !a.isBlank()) {
                out.add(a.trim());
            }
        }
        return out;
    }
}
