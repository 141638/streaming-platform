package com.streaming.auth.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "policy")
public class PolicyEntity {

    @Id private UUID id;

    @Column(name = "policy_key", nullable = false, length = 128)
    private String policyKey;

    @Column(nullable = false)
    private int version;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String definition;

    @Column(name = "definition_format", nullable = false, length = 32)
    private String definitionFormat;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
