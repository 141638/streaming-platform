package com.streaming.auth.persistence.repository;

import java.util.List;
import java.util.UUID;

import com.streaming.auth.persistence.entity.UserAccountRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRoleRepository extends JpaRepository<UserAccountRoleEntity, UserAccountRoleEntity.Pk> {

    List<UserAccountRoleEntity> findByUserAccountId(UUID userAccountId);
}
