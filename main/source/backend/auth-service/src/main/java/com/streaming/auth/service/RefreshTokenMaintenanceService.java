package com.streaming.auth.service;

import com.streaming.auth.persistence.repository.RefreshTokenRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits revocation in a dedicated transaction so failed refresh does not roll it back. */
@Service
@RequiredArgsConstructor
public class RefreshTokenMaintenanceService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllActiveInFamily(UUID familyId) {
        refreshTokenRepository.revokeActiveByFamily(familyId, OffsetDateTime.now());
    }
}
