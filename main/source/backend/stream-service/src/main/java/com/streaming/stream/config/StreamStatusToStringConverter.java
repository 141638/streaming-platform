package com.streaming.stream.config;

import com.streaming.stream.persistence.entity.StreamStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.lang.NonNull;

/** Converts {@link StreamStatus} to a database string when writing rows. */
@WritingConverter
public class StreamStatusToStringConverter implements Converter<StreamStatus, String> {

    @Override
    public String convert(@NonNull StreamStatus source) {
        return source.wireValue();
    }
}
