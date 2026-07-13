package com.streaming.stream.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} support for periodic tasks
 * (e.g. Redis view-count flush).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
