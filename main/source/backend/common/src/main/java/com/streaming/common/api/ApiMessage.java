package com.streaming.common.api;

/**
 * Lightweight service health / ping response used across all microservices.
 *
 * <p>This record lives in {@code common} because every service exposes a
 * {@code GET /v1/ping} endpoint and the contract is identical everywhere.
 */
public record ApiMessage(String service, String status) {}
