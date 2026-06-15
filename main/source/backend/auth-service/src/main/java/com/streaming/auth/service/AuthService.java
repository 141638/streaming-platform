package com.streaming.auth.service;

import com.streaming.auth.dto.LoginRequest;
import com.streaming.auth.exception.InvalidCredentialsException;
import com.streaming.auth.exception.UserAccountNotFoundException;
import com.streaming.auth.persistence.entity.UserAccountEntity;
import com.streaming.auth.persistence.repository.UserAccountRepository;
import com.streaming.auth.token.IssuedSessionTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserAccountRepository userAccountRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;

    public IssuedSessionTokens authenticate(LoginRequest loginRequest) {
        final UserAccountEntity requestUser = this.userAccountRepository
                .findByUsernameAndDeleteFlagFalse(loginRequest.username())
                .orElseThrow(() -> new UserAccountNotFoundException(loginRequest.username()));

        if (!passwordEncoder.matches(loginRequest.password(), requestUser.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return refreshTokenService.issueNewFamilySession(requestUser.getId());
    }
}
