package com.streaming.stream.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.stream.api.dto.SocialLink;
import io.r2dbc.postgresql.codec.Json;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.lang.NonNull;

/**
 * Converts a PostgreSQL {@code jsonb} column value to {@code List<SocialLink>}
 * when reading rows. Accepts {@link Json} (the native R2DBC Postgres wire type
 * for jsonb) rather than {@code String} so the driver correctly matches this
 * converter for jsonb columns. Returns an empty list on parse failure — a
 * malformed JSONB column should not block the channel page from rendering.
 */
@ReadingConverter
public class SocialLinksReadingConverter implements Converter<Json, List<SocialLink>> {

    private static final Logger log = LoggerFactory.getLogger(SocialLinksReadingConverter.class);
    private static final TypeReference<List<SocialLink>> TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<SocialLink> convert(@NonNull Json source) {
        String raw = source.asString();
        if (raw.isBlank()) {
            return List.of();
        }
        try {
            List<SocialLink> links = objectMapper.readValue(raw, TYPE);
            return links != null ? List.copyOf(links) : List.of();
        } catch (Exception e) {
            log.warn("Failed to parse social_links JSONB, returning empty list: {}", e.getMessage());
            return List.of();
        }
    }
}
