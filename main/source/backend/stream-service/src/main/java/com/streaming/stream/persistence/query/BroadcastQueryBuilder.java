package com.streaming.stream.persistence.query;

import com.streaming.stream.persistence.entity.StreamStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds parameterized SQL for broadcast queries against
 * {@code stream_session}.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Status filters are <strong>server-enforced</strong> — never from
 *       client input.</li>
 *   <li>Keyword search uses PostgreSQL {@code ILIKE} for case-insensitive
 *       matching.</li>
 *   <li>Sort priority: status (live → scheduled → ended) then secondary
 *       column. Views sort ({@code NULLS LAST}) is pre-wired for Phase 7+.</li>
 *   <li>Every query includes {@code broadcaster_username = :username} as
 *       the mandatory filter.</li>
 * </ul>
 *
 * <p>Thread-safe: each {@code build*} call creates a fresh builder instance.
 */
public final class BroadcastQueryBuilder {

    private final StringBuilder where = new StringBuilder();
    private final Map<String, Object> bindings = new HashMap<>();
    private int paramSeq;

    /* ---- public factories ------------------------------------------------ */

    /**
     * Rail query: status=ENDED, newest first, max 10.
     */
    public static BroadcastQuery forRail(String username) {
        var b = new BroadcastQueryBuilder();
        b.bind("username", username);
        b.appendWhere("broadcaster_username = :username");
        b.appendWhere("status = 'ended'");
        return b.finish("ORDER BY created_at DESC LIMIT 10");
    }

    /**
     * Paginated data query with server-enforced status filter, optional
     * keyword search, and sort/pagination from the client.
     *
     * @param statuses status filter — server-enforced, NOT from client
     */
    public static BroadcastQuery forList(String username,
                                         List<StreamStatus> statuses,
                                         String keyword,
                                         String sort,
                                         String order,
                                         int page,
                                         int size) {
        var b = new BroadcastQueryBuilder();
        b.bind("username", username);
        b.appendWhere("broadcaster_username = :username");
        b.addStatusFilter(statuses);
        b.addKeywordFilter(keyword);
        String orderBy = b.buildOrderBy(sort, order);
        String pagination = "LIMIT " + size + " OFFSET " + (page * size);
        return b.finish(orderBy + " " + pagination);
    }

    /**
     * Count query matching the same filters as {@link #forList} (no
     * sorting/pagination).
     */
    public static BroadcastQuery count(String username,
                                       List<StreamStatus> statuses,
                                       String keyword) {
        var b = new BroadcastQueryBuilder();
        b.bind("username", username);
        b.appendWhere("broadcaster_username = :username");
        b.addStatusFilter(statuses);
        b.addKeywordFilter(keyword);
        String sql = "SELECT COUNT(*) AS total FROM stream_session"
                + b.whereClause();
        return BroadcastQuery.of(sql, b.bindings);
    }

    /* ---- internals ------------------------------------------------------- */

    private void appendWhere(String clause) {
        if (where.isEmpty()) {
            where.append(" WHERE ").append(clause);
        } else {
            where.append(" AND ").append(clause);
        }
    }

    private void addStatusFilter(List<StreamStatus> statuses) {
        if (statuses.isEmpty()) {
            return;
        }
        String inClause = statuses.stream()
                .map(s -> {
                    String param = nextParam();
                    bindings.put(param, s.wireValue());
                    return ":" + param;
                })
                .collect(Collectors.joining(", "));
        appendWhere("status IN (" + inClause + ")");
    }

    private void addKeywordFilter(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String param = nextParam();
        bindings.put(param, "%" + keyword.trim() + "%");
        appendWhere("title ILIKE :" + param);
    }

    private String buildOrderBy(String sort, String order) {
        boolean asc = "asc".equalsIgnoreCase(order);

        // Status priority: live(0) → scheduled(1) → ended(2) → other(99)
        String statusPriority =
                "CASE status"
                + " WHEN 'live' THEN 0"
                + " WHEN 'scheduled' THEN 1"
                + " WHEN 'ended' THEN 2"
                + " ELSE 99 END";

        String secondary;
        if ("views".equalsIgnoreCase(sort)) {
            // Phase 7+ — views column; nulls last for scheduled streams
            String dir = asc ? "ASC" : "DESC";
            secondary = "views " + dir + " NULLS LAST";
        } else {
            // Default: created_at
            secondary = "created_at " + (asc ? "ASC" : "DESC");
        }

        return "ORDER BY " + statusPriority + ", " + secondary;
    }

    private String whereClause() {
        return where.toString();
    }

    private BroadcastQuery finish(String suffix) {
        String sql = "SELECT * FROM stream_session" + whereClause()
                + " " + suffix;
        return BroadcastQuery.of(sql, bindings);
    }

    private void bind(String name, Object value) {
        bindings.put(name, value);
    }

    private String nextParam() {
        return "p" + (paramSeq++);
    }
}
