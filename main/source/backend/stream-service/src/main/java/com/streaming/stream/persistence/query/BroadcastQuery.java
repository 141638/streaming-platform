package com.streaming.stream.persistence.query;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable holder for a parameterized SQL string and its named bindings,
 * ready to pass to {@code DatabaseClient.sql().bindValues()}.
 */
public record BroadcastQuery(String sql, Map<String, Object> bindings) {

    public BroadcastQuery {
        bindings = Collections.unmodifiableMap(bindings);
    }

    public static BroadcastQuery of(String sql, Map<String, Object> bindings) {
        return new BroadcastQuery(sql, bindings);
    }
}
