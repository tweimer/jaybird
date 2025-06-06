/*
 SPDX-FileCopyrightText: Copyright 2002-2008 Roman Rokytskyy
 SPDX-FileCopyrightText: Copyright 2002-2003 Blas Rodriguez Somoza
 SPDX-FileCopyrightText: Copyright 2002 David Jencks
 SPDX-FileCopyrightText: Copyright 2003 Ryan Baldwin
 SPDX-FileCopyrightText: Copyright 2012-2024 Mark Rotteveel
 SPDX-License-Identifier: LGPL-2.1-or-later
*/
package org.firebirdsql.jdbc.field;

import org.firebirdsql.encodings.EncodingDefinition;
import org.firebirdsql.gds.ng.DatatypeCoder;
import org.firebirdsql.gds.ng.fields.FieldDescriptor;
import org.firebirdsql.jaybird.util.FbDatetimeConversion;
import org.firebirdsql.jaybird.util.IOUtils;
import org.firebirdsql.jaybird.util.LegacyDatetimeConversions;
import org.firebirdsql.jdbc.FBDriverNotCapableException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.util.Calendar;
import java.util.function.Function;

/**
 * Field implementation for {@code CHAR} and {@code VARCHAR}.
 *
 * @author Roman Rokytskyy
 * @author David Jencks
 * @author Mark Rotteveel
 */
class FBStringField extends FBField implements TrimmableField {

    // TODO think about the right setBoolean and getBoolean (currently it is "Y" and "N", or "TRUE" and "FALSE")

    static final String SHORT_TRUE = "Y";
    static final String SHORT_FALSE = "N";
    static final String LONG_TRUE = "true";
    static final String LONG_FALSE = "false";
    static final String SHORT_TRUE_2 = "T";
    static final String SHORT_TRUE_3 = "1";

    protected final int possibleCharLength;
    private boolean trimTrailing;

    @NullMarked
    FBStringField(FieldDescriptor fieldDescriptor, FieldDataProvider dataProvider, int requiredType)
            throws SQLException {
        super(fieldDescriptor, dataProvider, requiredType);

        int charLength = fieldDescriptor.getCharacterLength();
        // TODO This might wreak havoc if field is a FBLongVarcharField
        // TODO currently avoiding -1 to avoid problems in FBLongVarcharField (eg with setBoolean); need to fix that
        possibleCharLength = charLength != -1 ? charLength : fieldDescriptor.getLength();
    }

    @Override
    public final void setTrimTrailing(boolean trimTrailing) {
        this.trimTrailing = trimTrailing;
    }

    @Override
    public final boolean isTrimTrailing() {
        return trimTrailing;
    }

    @Override
    public Object getObject() throws SQLException {
        return getString();
    }

    @Override
    public byte getByte() throws SQLException {
        if (isNull()) return BYTE_NULL_VALUE;
        String string = getString().trim();
        try {
            return Byte.parseByte(string);
        } catch (NumberFormatException nfex) {
            throw invalidGetConversion("byte", string, nfex);
        }
    }

    @Override
    public short getShort() throws SQLException {
        if (isNull()) return SHORT_NULL_VALUE;
        String string = getString().trim();
        try {
            return Short.parseShort(string);
        } catch (NumberFormatException nfex) {
            throw invalidGetConversion("short", string, nfex);
        }
    }

    @Override
    public int getInt() throws SQLException {
        if (isNull()) return INT_NULL_VALUE;
        String string = getString().trim();
        try {
            return Integer.parseInt(string);
        } catch (NumberFormatException nfex) {
            throw invalidGetConversion("int", string, nfex);
        }
    }

    @Override
    public long getLong() throws SQLException {
        if (isNull()) return LONG_NULL_VALUE;
        String string = getString().trim();
        try {
            return Long.parseLong(string);
        } catch (NumberFormatException nfex) {
            throw invalidGetConversion("long", string, nfex);
        }
    }

    @Override
    public BigDecimal getBigDecimal() throws SQLException {
        return getValueAs(BigDecimal.class, BigDecimal::new);
    }

    @Override
    public float getFloat() throws SQLException {
        if (isNull()) return FLOAT_NULL_VALUE;
        String string = getString().trim();
        try {
            return Float.parseFloat(string);
        } catch (NumberFormatException nfex) {
            throw invalidGetConversion("float", string, nfex);
        }
    }

    @Override
    public double getDouble() throws SQLException {
        if (isNull()) return DOUBLE_NULL_VALUE;
        String string = getString().trim();
        try {
            return Double.parseDouble(string);
        } catch (NumberFormatException nfex) {
            throw invalidGetConversion("double", string, nfex);
        }
    }

    //----- getBoolean, getString and getObject code

    @Override
    public boolean getBoolean() throws SQLException {
        if (isNull()) return BOOLEAN_NULL_VALUE;
        final String trimmedValue = getString().trim();
        return trimmedValue.equalsIgnoreCase(LONG_TRUE) ||
                trimmedValue.equalsIgnoreCase(SHORT_TRUE) ||
                trimmedValue.equalsIgnoreCase(SHORT_TRUE_2) ||
                trimmedValue.equals(SHORT_TRUE_3);
    }

    @Override
    public String getString() throws SQLException {
        String result = applyTrimTrailing(getDatatypeCoder().decodeString(getFieldData()));
        if (requiredType == Types.VARCHAR || isTrimTrailing()) {
            return result;
        }

        return fixPadding(result);
    }

    private String fixPadding(String result) {
        // fix incorrect padding of multibyte charsets (e.g. a CHAR(5) UTF8 can have upto 20 spaces, instead of 5)
        // NOTE: For Firebird 3.0 and earlier, this prevents access to oversized CHAR(n) CHARACTER SET UNICODE_FSS.
        // We accept that limitation because the workaround is to cast to VARCHAR, and because Firebird 4.0 no longer
        // allows storing oversized UNICODE_FSS values
        if (result != null && result.length() > possibleCharLength
            && result.codePointCount(0, result.length()) > possibleCharLength) {
            return result.substring(0, result.offsetByCodePoints(0, possibleCharLength));
        }
        return result;
    }

    /**
     * Applies trim trailing if enabled.
     *
     * @param value
     *         value to trim
     * @return {@code value} when trim trailing is disabled, or trimmed value when enabled
     */
    final String applyTrimTrailing(String value) {
        return trimTrailing ? TrimmableField.trimTrailing(value) : value;
    }

    //----- getXXXStream code

    @SuppressWarnings("DataFlowIssue")
    @Override
    public InputStream getBinaryStream() throws SQLException {
        if (isNull()) return null;
        return new ByteArrayInputStream(getFieldData());
    }

    @Override
    @SuppressWarnings({ "java:S1168", "DataFlowIssue" })
    public byte[] getBytes() throws SQLException {
        if (isNull()) return null;
        // protect against unintentional modification of cached or shared byte-arrays (eg in DatabaseMetaData)
        return getFieldData().clone();
    }

    @Override
    LocalDate getLocalDate() throws SQLException {
        return getValueAs(LocalDate.class, FbDatetimeConversion::parseSqlDate);
    }

    @Override
    LocalTime getLocalTime() throws SQLException {
        return getValueAs(LocalTime.class, LocalTime::parse);
    }

    @Override
    public Timestamp getTimestamp() throws SQLException {
        return convertForGet(getLocalDateTime(), Timestamp::valueOf, Timestamp.class);
    }

    @Override
    LocalDateTime getLocalDateTime() throws SQLException {
        return getValueAs(LocalDateTime.class, FbDatetimeConversion::parseIsoOrSqlTimestamp);
    }

    @Override
    OffsetTime getOffsetTime() throws SQLException {
        return getValueAs(OffsetTime.class, OffsetTime::parse);
    }

    @Override
    OffsetDateTime getOffsetDateTime() throws SQLException {
        return getValueAs(OffsetDateTime.class, OffsetDateTime::parse);
    }

    @Override
    ZonedDateTime getZonedDateTime() throws SQLException {
        return getValueAs(ZonedDateTime.class, ZonedDateTime::parse);
    }

    @Override
    public BigInteger getBigInteger() throws SQLException {
        return getValueAs(BigInteger.class, BigInteger::new);
    }

    //--- setXXX methods

    //----- Math code

    @Override
    public void setByte(byte value) throws SQLException {
        setString(Byte.toString(value));
    }

    @Override
    public void setShort(short value) throws SQLException {
        setString(Short.toString(value));
    }

    @Override
    public void setInteger(int value) throws SQLException {
        setString(Integer.toString(value));
    }

    @Override
    public void setLong(long value) throws SQLException {
        setString(Long.toString(value));
    }

    @Override
    public void setFloat(float value) throws SQLException {
        setString(Float.toString(value));
    }

    @Override
    public void setDouble(double value) throws SQLException {
        setString(Double.toString(value));
    }

    @Override
    public void setBigDecimal(BigDecimal value) throws SQLException {
        setAsString(value);
    }

    //----- setBoolean, setString and setObject code

    @Override
    public void setBoolean(boolean value) throws SQLException {
        if (possibleCharLength > 4) {
            setString(value ? LONG_TRUE : LONG_FALSE);
        } else {
            setString(value ? SHORT_TRUE : SHORT_FALSE);
        }
    }

    @Override
    public void setString(String value) throws SQLException {
        if (setWhenNull(value)) return;
        DatatypeCoder datatypeCoder = getDatatypeCoder();
        EncodingDefinition encodingDefinition = datatypeCoder.getEncodingDefinition();
        // Special rules for UTF8 (but not UNICODE_FSS), compare by codepoint count
        if (encodingDefinition.getFirebirdCharacterSetId() == 4 /* UTF8 */ && value.length() > possibleCharLength) {
            int codePointCount = value.codePointCount(0, value.length());
            if (codePointCount > possibleCharLength) {
                // NOTE: We're reporting the codepoint lengths, not the maximum size in bytes
                throw new DataTruncation(fieldDescriptor.getPosition() + 1, true, false, codePointCount,
                        possibleCharLength);
            }
        }
        byte[] data = datatypeCoder.encodeString(value);
        if (data.length > fieldDescriptor.getLength()) {
            // NOTE: This doesn't catch truncation errors for oversized strings with multibyte character sets that
            // still fit, those are handled by the server on execute. For UTF8, the earlier check should handle this.
            throw new DataTruncation(fieldDescriptor.getPosition() + 1, true, false, data.length,
                    fieldDescriptor.getLength());
        }
        setFieldData(data);
    }

    //----- setXXXStream code

    @Override
    protected void setBinaryStreamInternal(InputStream in, long length) throws SQLException {
        if (setWhenNull(in)) return;
        // TODO More specific value
        if (length > Integer.MAX_VALUE) {
            throw new FBDriverNotCapableException("Only length <= Integer.MAX_VALUE supported");
        }

        try {
            setBytes(IOUtils.toBytes(in, (int) length));
        } catch (IOException ioex) {
            throw invalidSetConversion(InputStream.class, ioex);
        }
    }

    @Override
    protected void setCharacterStreamInternal(Reader in, long length) throws SQLException {
        if (setWhenNull(in)) return;
        // TODO More specific value
        if (length > Integer.MAX_VALUE) {
            throw new FBDriverNotCapableException("Only length <= Integer.MAX_VALUE supported");
        }

        try {
            setString(IOUtils.toString(in, (int) length));
        } catch (IOException ioex) {
            throw invalidSetConversion(Reader.class, ioex);
        }
    }

    @Override
    public void setBytes(byte[] value) throws SQLException {
        if (setWhenNull(value)) return;
        if (value.length > fieldDescriptor.getLength()) {
            throw new DataTruncation(fieldDescriptor.getPosition() + 1, true, false, value.length,
                    fieldDescriptor.getLength());
        }

        setFieldData(value);
    }

    @Override
    void setLocalDate(LocalDate localDate) throws SQLException {
        setAsString(localDate);
    }

    @Override
    void setLocalTime(LocalTime value) throws SQLException {
        setAsString(value);
    }

    @Override
    public void setTimestamp(Timestamp value, Calendar cal) throws SQLException {
        setString(convertForSet(value,
                v -> FbDatetimeConversion.formatSqlTimestamp(LegacyDatetimeConversions.toLocalDateTime(v, cal)),
                Timestamp.class));
    }

    @Override
    public void setTimestamp(Timestamp value) throws SQLException {
        setString(convertForSet(value,
                v -> FbDatetimeConversion.formatSqlTimestamp(v.toLocalDateTime()), Timestamp.class));
    }

    @Override
    void setLocalDateTime(LocalDateTime value) throws SQLException {
        setAsString(value);
    }

    @Override
    void setOffsetTime(OffsetTime value) throws SQLException {
        setAsString(value);
    }

    @Override
    void setOffsetDateTime(OffsetDateTime value) throws SQLException {
        setAsString(value);
    }

    @Override
    void setZonedDateTime(ZonedDateTime value) throws SQLException {
        setAsString(value);
    }

    @Override
    public void setBigInteger(BigInteger value) throws SQLException {
        setAsString(value);
    }

    private void setAsString(Object value) throws SQLException {
        setString(value != null ? value.toString() : null);
    }

    private <T> T getValueAs(@NonNull Class<T> type, @NonNull Function<String, T> converter) throws SQLException {
        return convertForGet(getString(), converter.compose(String::trim), type);
    }

}
