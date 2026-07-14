package com.streaming.notification.config;

import com.streaming.notification.domain.NotificationCategory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.lang.NonNull;

/** Converts {@link NotificationCategory} to a database string when writing rows. */
@WritingConverter
public class CategoryToStringConverter implements Converter<NotificationCategory, String> {

    @Override
    public String convert(@NonNull NotificationCategory source) {
        return source.wireValue();
    }
}
