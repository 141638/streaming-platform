package com.streaming.stream.api.dto;

/**
 * A category name with how many times the broadcaster streamed in it.
 * Used for the category breakdown in the About tab sidebar.
 */
public record CategoryCount(String category, long count) {}
