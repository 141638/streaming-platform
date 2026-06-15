package com.streaming.auth.api;

import com.streaming.auth.dto.PasswordResetResponse;
import com.streaming.auth.exception.InvalidPasswordResetTokenException;
import com.streaming.auth.exception.PasswordValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<PasswordResetResponse> handleInvalidPasswordResetToken(
            InvalidPasswordResetTokenException ex) {
        return ResponseEntity.badRequest()
                .body(new PasswordResetResponse("error", ex.getMessage()));
    }

    @ExceptionHandler(PasswordValidationException.class)
    public ResponseEntity<PasswordResetResponse> handlePasswordValidation(
            PasswordValidationException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new PasswordResetResponse("error", ex.getMessage()));
    }
}
