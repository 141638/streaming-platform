package com.streaming.stream.config;

import com.streaming.stream.persistence.entity.StreamStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.lang.NonNull;

/** Converts a database string to {@link StreamStatus} when reading rows. */
@ReadingConverter
public class StringToStreamStatusConverter implements Converter<String, StreamStatus> {

    @Override
    public StreamStatus convert(@NonNull String source) {
        return StreamStatus.fromWireValue(source);
    }
}
