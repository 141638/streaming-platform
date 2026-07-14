package com.streaming.notification.config;

import com.streaming.notification.domain.NotificationCategory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.lang.NonNull;

/** Converts a database string to {@link NotificationCategory} when reading rows. */
@ReadingConverter
public class StringToCategoryConverter implements Converter<String, NotificationCategory> {

    @Override
    public NotificationCategory convert(@NonNull String source) {
        return NotificationCategory.fromWireValue(source);
    }
}
