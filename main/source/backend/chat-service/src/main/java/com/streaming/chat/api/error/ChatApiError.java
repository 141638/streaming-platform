package com.streaming.chat.api.error;

/**
 * Error envelope returned by the chat API.
 *
 * <p>The {@code code} field carries a stable, machine-readable identifier that
 * disambiguates rejections which may share an HTTP status. In particular the two
 * {@code 403}s — PBAC authorization denial ({@code AUTHZ_DENIED}) versus a room
 * ban ({@code CHAT_USER_BANNED}) — are distinguished by {@code code}, not by
 * status. Clients and logs should branch on {@code code}, never on status alone.
 */
public record ChatApiError(String code, String message) {}
