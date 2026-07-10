package com.streaming.stream.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.stream.api.dto.SocialLink;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.lang.NonNull;

/**
 * Converts {@code List<SocialLink>} to a JSONB string for PostgreSQL writes.
 */
@WritingConverter
public class SocialLinksWritingConverter implements Converter<List<SocialLink>, String> {

    private static final Logger log = LoggerFactory.getLogger(SocialLinksWritingConverter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convert(@NonNull List<SocialLink> source) {
        if (source.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(source);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize social links — returning null: {}", e.getMessage());
            return null;
        }
    }
}
