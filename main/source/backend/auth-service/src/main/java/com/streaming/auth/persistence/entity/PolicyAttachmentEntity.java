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
@Table(name = "policy_attachment")
public class PolicyAttachmentEntity {

    @Id private UUID id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "principal_type", nullable = false, length = 32)
    private String principalType;

    @Column(name = "principal_user_id")
    private UUID principalUserId;

    @Column(name = "principal_subject", length = 256)
    private String principalSubject;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
