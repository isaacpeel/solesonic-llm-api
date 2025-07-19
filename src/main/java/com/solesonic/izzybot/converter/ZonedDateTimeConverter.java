package com.solesonic.izzybot.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Converter(autoApply = true)
public class ZonedDateTimeConverter implements AttributeConverter<ZonedDateTime, Instant> {

    // Convert ZonedDateTime to Instant (UTC) for storage
    @Override
    public Instant convertToDatabaseColumn(ZonedDateTime zonedDateTime) {
        return zonedDateTime != null ? zonedDateTime.toInstant() : null;
    }

    // Convert Instant (UTC) from storage to ZonedDateTime in the system's default time zone
    @Override
    public ZonedDateTime convertToEntityAttribute(Instant instant) {
        return instant != null ? instant.atZone(ZoneId.systemDefault()) : null;
    }
}
