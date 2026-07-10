package com.streaming.stream.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.stream.api.dto.SocialLink;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.lang.NonNull;

/**
 * Converts a JSONB string from PostgreSQL to {@code List<SocialLink>} when
 * reading rows. Returns an empty list on parse failure rather than throwing —
 * a malformed JSONB column should not block the channel page from rendering.
 */
@ReadingConverter
public class SocialLinksReadingConverter implements Converter<String, List<SocialLink>> {

    private static final Logger log = LoggerFactory.getLogger(SocialLinksReadingConverter.class);
    private static final TypeReference<List<SocialLink>> TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<SocialLink> convert(@NonNull String source) {
        if (source.isBlank()) {
            return List.of();
        }
        try {
            List<SocialLink> links = objectMapper.readValue(source, TYPE);
            return links != null ? List.copyOf(links) : List.of();
        } catch (Exception e) {
            log.warn("Failed to parse social_links JSONB, returning empty list: {}", e.getMessage());
            return List.of();
        }
    }
}
