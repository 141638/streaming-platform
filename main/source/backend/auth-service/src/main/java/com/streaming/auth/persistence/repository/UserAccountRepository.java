package com.streaming.auth.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import com.streaming.auth.persistence.entity.UserAccountEntity;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {

    Optional<UserAccountEntity> findByIdAndDeleteFlagFalse(UUID id);

    Optional<UserAccountEntity> findByUsernameAndDeleteFlagFalse(@NotBlank String username);

    Optional<UserAccountEntity> findByEmailAndDeleteFlagFalse(@NotBlank String email);
}
