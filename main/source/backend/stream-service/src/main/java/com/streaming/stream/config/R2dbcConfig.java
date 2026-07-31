package com.streaming.stream.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/** Registers custom type converters and reactive transaction support. */
@Configuration
public class R2dbcConfig {

    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions() {
        return R2dbcCustomConversions.of(
                PostgresDialect.INSTANCE,
                new StringToStreamStatusConverter(),
                new StreamStatusToStringConverter(),
                new SocialLinksReadingConverter(),
                new SocialLinksWritingConverter()
        );
    }

    /**
     * Reactive transaction manager for R2DBC.
     * Enables {@link TransactionalOperator#transactional} wrapping
     * around entity-save + outbox-write pairs so they share a single
     * database transaction.
     */
    @Bean
    public TransactionalOperator transactionalOperator(ConnectionFactory connectionFactory) {
        return TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    }
}
