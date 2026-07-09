package com.streaming.chat.config;

import com.streaming.chat.domain.MessageType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.lang.NonNull;

/** Converts {@link MessageType} to a database string when writing rows. */
@WritingConverter
public class MessageTypeToStringConverter implements Converter<MessageType, String> {

    @Override
    public String convert(@NonNull MessageType source) {
        return source.wireValue();
    }
}
