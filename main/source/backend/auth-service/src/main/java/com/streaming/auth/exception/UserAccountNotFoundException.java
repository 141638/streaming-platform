package com.streaming.auth.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserAccountNotFoundException extends RuntimeException {

    public UserAccountNotFoundException(UUID userId) {
        super("Active user not found: " + userId);
    }

    public UserAccountNotFoundException(String username) {
        super("Active user not found: " + username);
    }
}
