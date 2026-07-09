package com.streaming.chat.config;

import com.streaming.chat.domain.MessageType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.lang.NonNull;

/** Converts a database string to {@link MessageType} when reading rows. */
@ReadingConverter
public class StringToMessageTypeConverter implements Converter<String, MessageType> {

    @Override
    public MessageType convert(@NonNull String source) {
        return MessageType.fromWireValue(source);
    }
}
