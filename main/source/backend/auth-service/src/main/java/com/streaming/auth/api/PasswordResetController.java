package com.streaming.auth.api;

import com.streaming.auth.dto.PasswordResetConfirmRequest;
import com.streaming.auth.dto.PasswordResetRequest;
import com.streaming.auth.dto.PasswordResetResponse;
import com.streaming.auth.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @PostMapping("/request")
    public ResponseEntity<PasswordResetResponse> requestReset(
            @Valid @RequestBody PasswordResetRequest request
    ) {
        PasswordResetResponse response = passwordResetService.requestReset(request.email());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/confirm")
    public ResponseEntity<PasswordResetResponse> confirmReset(
            @Valid @RequestBody PasswordResetConfirmRequest request
    ) {
        PasswordResetResponse response =
                passwordResetService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.ok(response);
    }
}
