package com.streaming.auth.token.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * JSON stored in auth.policy.definition (seed format from Flyway): {@code {"statements":[...]}}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyStatementsDocument(List<Statement> statements) {

    public PolicyStatementsDocument {
        statements = statements == null ? List.of() : List.copyOf(statements);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Statement(
            String effect,
            String resource,
            List<String> actions
    ) {
        public Statement {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }
}
