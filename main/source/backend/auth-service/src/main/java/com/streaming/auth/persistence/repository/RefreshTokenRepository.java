package com.streaming.auth.persistence.repository;

import com.streaming.auth.persistence.entity.RefreshTokenEntity;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    /** Row lock reduces double-refresh races for the same token. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshTokenEntity r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> lockByTokenHash(@Param("tokenHash") byte[] tokenHash);

    /** Non-locking lookup for idempotent operations (e.g. logout) where concurrency is not a concern. */
    Optional<RefreshTokenEntity> findByTokenHash(byte[] tokenHash);

    @Modifying
    @Query(
            "UPDATE RefreshTokenEntity r SET r.revokedAt = :now WHERE r.tokenFamilyId = :familyId "
                    + "AND r.revokedAt IS NULL")
    int revokeActiveByFamily(@Param("familyId") UUID familyId, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.revokedAt = :now WHERE r.userAccountId = :userId AND r.revokedAt IS NULL")
    int revokeAllActiveByUser(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);
}
