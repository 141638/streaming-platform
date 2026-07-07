package com.streaming.stream.persistence.entity;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Managed category vocabulary for stream discovery.
 *
 * <p>Categories are seeded via Flyway migration and exposed through
 * {@code GET /v1/categories} for frontend dropdowns.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stream_category")
public class StreamCategoryEntity {

    @Id
    private UUID id;

    @Column("name")
    private String name;

    @Column("slug")
    private String slug;

    @Column("display_order")
    private int displayOrder;

    @Column("created_at")
    private OffsetDateTime createdAt;
}
