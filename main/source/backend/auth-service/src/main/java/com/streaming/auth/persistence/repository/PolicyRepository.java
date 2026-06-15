package com.streaming.auth.persistence.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.streaming.auth.persistence.entity.PolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<PolicyEntity, UUID> {

    List<PolicyEntity> findAllByIdInAndEnabledTrue(Collection<UUID> ids);
}
