package com.streaming.auth.persistence.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.streaming.auth.persistence.entity.PolicyAttachmentEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyAttachmentRepository extends JpaRepository<PolicyAttachmentEntity, UUID> {

    @Query("""
            select distinct pa.policyId from PolicyAttachmentEntity pa
            where pa.principalType = 'USER' and pa.principalUserId = :userId
            """)
    List<UUID> findDistinctPolicyIdsByPrincipalUser(@Param("userId") UUID userId);

    @Query("""
            select distinct pa.policyId from PolicyAttachmentEntity pa
            where pa.principalType = 'ROLE' and pa.principalSubject in :roleSlugs
            """)
    List<UUID> findDistinctPolicyIdsByRoleSlugs(@Param("roleSlugs") Collection<String> roleSlugs);
}
