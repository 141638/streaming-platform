package com.streaming.insight.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Registers reactive transaction support for the insight schema.
 *
 * <p>Phase A has no custom type converters (all columns are Tier 1 types:
 * UUID, VARCHAR, BIGINT, TIMESTAMPTZ). Converters will be added in Phase B
 * when JSONB columns are introduced.
 */
@Configuration
public class R2dbcConfig {

    /**
     * Reactive transaction manager for R2DBC.
     * Enables {@link TransactionalOperator#transactional} wrapping
     * around entity-save operations.
     */
    @Bean
    public TransactionalOperator transactionalOperator(ConnectionFactory connectionFactory) {
        return TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    }
}
