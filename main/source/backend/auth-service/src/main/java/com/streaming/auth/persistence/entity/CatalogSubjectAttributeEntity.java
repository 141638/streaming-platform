package com.streaming.auth.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps {@code auth.catalog_subject_attribute}: allowed keys and value types for JWT {@code attr} claims.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "catalog_subject_attribute")
public class CatalogSubjectAttributeEntity {

    @Id
    @Column(name = "attribute_key", nullable = false, length = 64)
    private String attributeKey;

    @Column(name = "json_value_kind", nullable = false, length = 32)
    private String jsonValueKind;

    @Column(nullable = false, length = 512)
    private String description;
}
