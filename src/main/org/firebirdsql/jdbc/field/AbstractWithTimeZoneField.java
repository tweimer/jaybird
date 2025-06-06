// SPDX-FileCopyrightText: Copyright 2019-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.jdbc.field;

import org.firebirdsql.gds.ng.fields.FieldDescriptor;
import org.firebirdsql.gds.ng.tz.TimeZoneDatatypeCoder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;

import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Calendar;

/**
 * Superclass for {@link FBTimeTzField}, {@link FBTimestampTzField} to handle legacy date/time types and common behaviour.
 *
 * @author Mark Rotteveel
 * @since 4.0
 */
@SuppressWarnings("RedundantThrows")
abstract class AbstractWithTimeZoneField extends FBField {

    private ZoneId defaultZoneId;
    private final TimeZoneDatatypeCoder.@NonNull TimeZoneCodec timeZoneCodec;

    @NullMarked
    AbstractWithTimeZoneField(FieldDescriptor fieldDescriptor, FieldDataProvider dataProvider, int requiredType)
            throws SQLException {
        super(fieldDescriptor, dataProvider, requiredType);
        timeZoneCodec = TimeZoneDatatypeCoder.getInstanceFor(getDatatypeCoder())
                .getTimeZoneCodecFor(fieldDescriptor);
    }

    @Override
    final OffsetDateTime getOffsetDateTime() throws SQLException {
        return timeZoneCodec.decodeOffsetDateTime(getFieldData());
    }

    @Override
    final void setOffsetDateTime(OffsetDateTime offsetDateTime) throws SQLException {
        setFieldData(getTimeZoneCodec().encodeOffsetDateTime(offsetDateTime));
    }

    @Override
    final OffsetTime getOffsetTime() throws SQLException {
        return timeZoneCodec.decodeOffsetTime(getFieldData());
    }

    @Override
    final void setOffsetTime(OffsetTime offsetTime) throws SQLException {
        setFieldData(timeZoneCodec.encodeOffsetTime(offsetTime));
    }

    @Override
    final ZonedDateTime getZonedDateTime() throws SQLException {
        return timeZoneCodec.decodeZonedDateTime(getFieldData());
    }

    @Override
    final void setZonedDateTime(ZonedDateTime zonedDateTime) throws SQLException {
        setFieldData(timeZoneCodec.encodeZonedDateTime(zonedDateTime));
    }

    @Override
    public Time getTime() throws SQLException {
        OffsetDateTime offsetDateTime = getOffsetDateTime();
        return offsetDateTime != null ? new Time(offsetDateTime.toInstant().toEpochMilli()) : null;
    }

    @Override
    public Time getTime(Calendar cal) throws SQLException {
        // Intentionally ignoring calendar, see jdp-2019-03
        return getTime();
    }

    @Override
    public void setTime(Time value) throws SQLException {
        setOffsetDateTime(value != null
                ? ZonedDateTime.of(LocalDate.now(), value.toLocalTime(), getDefaultZoneId()).toOffsetDateTime()
                : null);
    }

    @Override
    public void setTime(Time value, Calendar cal) throws SQLException {
        // Intentionally ignoring calendar, see jdp-2019-03
        setTime(value);
    }

    @Override
    public Timestamp getTimestamp() throws SQLException {
        OffsetDateTime offsetDateTime = getOffsetDateTime();
        if (offsetDateTime == null) return null;

        Instant instant = offsetDateTime.toInstant();
        Timestamp timestamp = new Timestamp(instant.toEpochMilli());
        timestamp.setNanos(instant.getNano());
        return timestamp;
    }

    @Override
    public Timestamp getTimestamp(Calendar cal) throws SQLException {
        // Intentionally ignoring calendar, see jdp-2019-03
        return getTimestamp();
    }

    @Override
    public void setTimestamp(Timestamp value) throws SQLException {
        setOffsetDateTime(value != null ? value.toLocalDateTime().atZone(getDefaultZoneId()).toOffsetDateTime() : null);
    }

    @Override
    public void setTimestamp(Timestamp value, Calendar cal) throws SQLException {
        // Intentionally ignoring calendar, see jdp-2019-03
        setTimestamp(value);
    }

    final TimeZoneDatatypeCoder.@NonNull TimeZoneCodec getTimeZoneCodec() {
        return timeZoneCodec;
    }

    final @NonNull ZoneId getDefaultZoneId() {
        if (defaultZoneId != null) {
            return defaultZoneId;
        }
        return defaultZoneId = ZoneId.systemDefault();
    }

    private void setStringParse(@NonNull String value) throws SQLException {
        // TODO Better way to do this?
        // TODO More lenient parsing?
        if (value.indexOf('T') != -1) {
            OffsetDateTime offsetDateTime = OffsetDateTime.parse(value);
            setOffsetDateTime(offsetDateTime);
        } else {
            OffsetTime offsetTime = OffsetTime.parse(value);
            setOffsetTime(offsetTime);
        }
    }

    @Override
    public void setString(String value) throws SQLException {
        if (setWhenNull(value)) return;
        String string = value.trim();
        try {
            setStringParse(string);
        } catch (DateTimeParseException e) {
            throw invalidSetConversion(String.class, string, e);
        }
    }
    
}
