/*
 SPDX-FileCopyrightText: Copyright 2001-2002 David Jencks
 SPDX-FileCopyrightText: Copyright 2001-2002 Boix i Oltra, S.L.
 SPDX-FileContributor: Alejandro Alberola (Boix i Oltra, S.L.)
 SPDX-FileCopyrightText: Copyright 2002-2011 Roman Rokytskyy
 SPDX-FileCopyrightText: Copyright 2002-2003 Blas Rodriguez Somoza
 SPDX-FileCopyrightText: Copyright 2005-2007 Gabriel Reid
 SPDX-FileCopyrightText: Copyright 2011-2024 Mark Rotteveel
 SPDX-FileCopyrightText: Copyright 2019-2020 Vasiliy Yashkov
 SPDX-License-Identifier: LGPL-2.1-or-later
*/
package org.firebirdsql.jdbc;

import org.firebirdsql.gds.JaybirdErrorCodes;
import org.firebirdsql.gds.impl.GDSHelper;
import org.firebirdsql.gds.ng.FbExceptionBuilder;
import org.firebirdsql.gds.ng.FbStatement;
import org.firebirdsql.gds.ng.fields.FieldDescriptor;
import org.firebirdsql.gds.ng.fields.RowDescriptor;
import org.firebirdsql.gds.ng.fields.RowValue;
import org.firebirdsql.jaybird.props.PropertyConstants;
import org.firebirdsql.jaybird.util.SQLExceptionThrowingFunction;
import org.firebirdsql.jaybird.util.UncheckedSQLException;
import org.firebirdsql.jaybird.util.SQLExceptionChainBuilder;
import org.firebirdsql.jdbc.field.FBCloseableField;
import org.firebirdsql.jdbc.field.FBField;
import org.firebirdsql.jdbc.field.FieldDataProvider;
import org.firebirdsql.jdbc.field.TrimmableField;
import org.firebirdsql.util.InternalApi;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;
import static org.firebirdsql.gds.JaybirdErrorCodes.jb_concurrencyResetReadOnlyReasonNotUpdatable;

/**
 * Implementation of {@link ResultSet}.
 * <p>
 * This class is internal API of Jaybird. Future versions may radically change, move, or make inaccessible this type.
 * For the public API, refer to the {@link ResultSet} and {@link FirebirdResultSet} interfaces.
 * </p>
 *
 * @author David Jencks
 * @author Roman Rokytskyy
 * @author Mark Rotteveel
 */
@SuppressWarnings("RedundantThrows")
@InternalApi
@NullMarked
public class FBResultSet implements ResultSet, FirebirdResultSet, FBObjectListener.FetcherListener {

    private static final String UNICODE_STREAM_NOT_SUPPORTED = "Unicode stream not supported";
    private static final String TYPE_SQLXML = "SQLXML";

    private final @Nullable AbstractStatement statement;
    private final FBFetcher fbFetcher;
    private @Nullable FirebirdRowUpdater rowUpdater;

    protected final @Nullable FBConnection connection;
    protected final @Nullable GDSHelper gdsHelper;

    protected final RowDescriptor rowDescriptor;

    protected @Nullable RowValue row;

    private boolean wasNull;

    private final FBField[] fields;
    private final List<FBCloseableField> closeableFields;
    private final Map<String, Integer> colNames;

    private final @Nullable String cursorName;
    private final FBObjectListener.ResultSetListener listener;

    @Override
    public void rowChanged(FBFetcher fetcher, @Nullable RowValue newRow) throws SQLException {
        this.row = newRow;
    }

    /**
     * Creates a new {@code FBResultSet} instance.
     */
    @SuppressWarnings("java:S1141")
    public FBResultSet(AbstractStatement statement, FBObjectListener.@Nullable ResultSetListener listener,
            boolean metaDataQuery) throws SQLException {
        this.statement = requireNonNull(statement, "statement");
        FbStatement stmt = requireNonNull(statement.getStatementHandle(), "statement.statementHandle");
        try {
            connection = requireNonNull(statement.getConnection(), "statement.connection");
            gdsHelper = connection.getGDSHelper();
            cursorName = statement.getCursorName();
            this.listener = listener != null ? listener : FBObjectListener.NoActionResultSetListener.instance();
            rowDescriptor = stmt.getRowDescriptor();

            FetchConfig fetchConfig = statement.fetchConfig();
            ResultSetBehavior behavior = fetchConfig.resultSetBehavior();
            boolean serverSideScrollable =
                    behavior.isScrollable() && behavior.isCloseCursorsAtCommit() && !metaDataQuery
                    && connection.isScrollableCursor(PropertyConstants.SCROLLABLE_CURSOR_SERVER)
                    && stmt.supportsFetchScroll();
            boolean cached =
                    metaDataQuery
                    || behavior.isScrollable() && !serverSideScrollable
                    || behavior.isHoldCursorsOverCommit();

            fields = createFields(cached, metaDataQuery);
            closeableFields = toCloseableFields(fields);
            colNames = new HashMap<>(rowDescriptor.getCount(), 1);
            FBFetcher fbFetcher;
            if (cached) {
                fbFetcher = new FBCachedFetcher(gdsHelper, fetchConfig, stmt, this);
                if (behavior.isForwardOnly()) {
                    fbFetcher = new ForwardOnlyFetcherDecorator(fbFetcher);
                }
            } else if (serverSideScrollable) {
                fbFetcher = new FBServerScrollFetcher(fetchConfig, stmt, this);
            } else if (statement.getCursorName() != null) {
                fbFetcher = new FBUpdatableCursorFetcher(gdsHelper, fetchConfig, stmt, this);
            } else {
                fbFetcher = new FBStatementFetcher(gdsHelper, fetchConfig, stmt, this);
            }

            if (behavior.isUpdatable()) {
                try {
                    rowUpdater = new FBRowUpdater(connection, rowDescriptor, cached, listener);
                    if (behavior.isScrollable()) {
                        fbFetcher = new FBUpdatableFetcher(fbFetcher, this, rowDescriptor.createDeletedRowMarker());
                    }
                } catch (FBResultSetNotUpdatableException ex) {
                    statement.addWarning(FbExceptionBuilder.toWarning(jb_concurrencyResetReadOnlyReasonNotUpdatable));
                    fbFetcher.setReadOnly();
                }
            }
            this.fbFetcher = fbFetcher;
        } catch (SQLException e) {
            try {
                // Ensure cursor is closed to avoid problems with statement reuse
                stmt.closeCursor();
            } catch (SQLException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }

    /**
     * Creates a FBResultSet with the columns specified by {@code rowDescriptor} and the data in {@code rows}.
     * <p>
     * This constructor is intended for metadata result sets, but can be used for other purposes as well.
     * </p>
     * <p>
     * Current implementation will ensure that strings will be trimmed on retrieval.
     * </p>
     *
     * @param rowDescriptor
     *         column definition
     * @param rows
     *         row data
     */
    public FBResultSet(RowDescriptor rowDescriptor, List<RowValue> rows) throws SQLException {
        this(rowDescriptor, null, rows, null, false);
    }

    /**
     * Creates a FBResultSet with the columns specified by {@code rowDescriptor} and the data in {@code rows}.
     * <p>
     * Current implementation will ensure that strings will be trimmed on retrieval.
     * </p>
     *
     * @param rowDescriptor
     *         column definition
     * @param connection
     *         connection (cannot be {@code null} when {@code retrieveBlobs} is {@code true}
     * @param rows
     *         row data
     * @param listener
     *         result set listener
     * @param retrieveBlobs
     *         {@code true} retrieves the blob data
     * @since 5.0.1
     */
    public FBResultSet(RowDescriptor rowDescriptor, @Nullable FBConnection connection, List<RowValue> rows,
            FBObjectListener.@Nullable ResultSetListener listener, boolean retrieveBlobs) throws SQLException {
        // TODO Evaluate if we need to share more implementation with constructor above
        this.connection = connection;
        gdsHelper = connection != null ? connection.getGDSHelper() : null;
        statement = null;
        this.listener = listener != null ? listener : FBObjectListener.NoActionResultSetListener.instance();
        cursorName = null;
        // TODO Set specific result set types (see also previous todo)
        var fetchConfig = new FetchConfig(ResultSetBehavior.of());
        fbFetcher = new FBCachedFetcher(rows, fetchConfig, this, rowDescriptor, gdsHelper, retrieveBlobs);
        this.rowDescriptor = rowDescriptor;
        fields = createFields(true, false);
        closeableFields = toCloseableFields(fields);
        colNames = new HashMap<>(rowDescriptor.getCount(), 1);
    }

    private FBField[] createFields(boolean cached, boolean trimStrings) throws SQLException {
        int fieldCount = rowDescriptor.getCount();
        var fields = new FBField[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            fields[i] = FBField.createField(rowDescriptor.getFieldDescriptor(i), new DataProvider(i), gdsHelper, cached);
            if (trimStrings && fields[i] instanceof TrimmableField trimmableField) {
                trimmableField.setTrimTrailing(true);
            }
        }
        return fields;
    }

    private static List<FBCloseableField> toCloseableFields(FBField[] fields) {
        return Arrays.stream(fields)
                .filter(FBCloseableField.class::isInstance)
                .map(FBCloseableField.class::cast)
                .toList();
    }

    /**
     * Notify the row updater about the new row that was fetched. This method
     * must be called after each change in cursor position.
     */
    private void notifyRowUpdater() throws SQLException {
        if (rowUpdater != null) {
            rowUpdater.setRow(row);
        }
    }

    /**
     * Check if statement is open and prepare statement for cursor move.
     *
     * @throws SQLException
     *         if statement is closed.
     */
    protected void checkCursorMove() throws SQLException {
        checkOpen();
        closeFields();
    }

    /**
     * Check if ResultSet is open.
     *
     * @throws SQLException
     *         if ResultSet is closed.
     */
    protected void checkOpen() throws SQLException {
        if (isClosed()) {
            throw FbExceptionBuilder.toNonTransientException(JaybirdErrorCodes.jb_resultSetClosed);
        }
    }

    /**
     * Checks if the result set is scrollable
     *
     * @throws SQLException
     *         if ResultSet is not scrollable
     */
    protected void checkScrollable() throws SQLException {
        if (behavior().isForwardOnly()) {
            throw FbExceptionBuilder.toNonTransientException(JaybirdErrorCodes.jb_operationNotAllowedOnForwardOnly);
        }
    }

    /**
     * Close the fields if they were open (applies mainly to the stream fields).
     *
     * @throws SQLException
     *         if something wrong happened.
     */
    protected void closeFields() throws SQLException {
        // TODO See if we can apply completion reason logic (e.g. no need to close blob on commit)
        wasNull = false;
        // if there are no fields to close, then nothing else to do
        if (closeableFields.isEmpty()) return;

        var chain = new SQLExceptionChainBuilder();
        // close current fields, so that resources are freed.
        for (final FBCloseableField field : closeableFields) {
            try {
                field.close();
            } catch (SQLException ex) {
                chain.append(ex);
            }
        }

        chain.throwIfPresent();
    }

    @Override
    public boolean next() throws SQLException {
        checkCursorMove();
        boolean result = fbFetcher.next();

        if (result)
            notifyRowUpdater();

        return result;
    }

    @Override
    public void close() throws SQLException {
        close(true, CompletionReason.OTHER);
    }

    @Override
    public boolean isClosed() throws SQLException {
        return fbFetcher.isClosed();
    }

    void close(boolean notifyListener, CompletionReason completionReason) throws SQLException {
        if (isClosed()) return;
        var chain = new SQLExceptionChainBuilder();

        try {
            closeFields();
        } catch (SQLException ex) {
            chain.append(ex);
        } finally {
            try {
                try {
                    fbFetcher.close(completionReason);
                } catch (SQLException ex) {
                    chain.append(ex);
                }

                if (rowUpdater != null) {
                    try {
                        rowUpdater.close();
                    } catch (SQLException ex) {
                        chain.append(ex);
                    }
                }

                if (notifyListener) {
                    try {
                        listener.resultSetClosed(this);
                    } catch (SQLException ex) {
                        chain.append(ex);
                    }
                }
            } finally {
                rowUpdater = null;
            }
        }

        chain.throwIfPresent();
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: works identical to {@link #getBinaryStream(int)}.
     * </p>
     */
    @Override
    public final @Nullable InputStream getAsciiStream(int columnIndex) throws SQLException {
        return getBinaryStream(columnIndex);
    }

    @Override
    public @Nullable BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return getField(columnIndex).getBigDecimal();
    }

    @Override
    public @Nullable InputStream getBinaryStream(int columnIndex) throws SQLException {
        return getField(columnIndex).getBinaryStream();
    }

    @Override
    public @Nullable Blob getBlob(int columnIndex) throws SQLException {
        return getField(columnIndex).getBlob();
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return getField(columnIndex).getBoolean();
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return getField(columnIndex).getByte();
    }

    @Override
    public byte @Nullable [] getBytes(int columnIndex) throws SQLException {
        return getField(columnIndex).getBytes();
    }

    @Override
    public @Nullable Date getDate(int columnIndex) throws SQLException {
        return getField(columnIndex).getDate();
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return getField(columnIndex).getDouble();
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return getField(columnIndex).getFloat();
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return getField(columnIndex).getInt();
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return getField(columnIndex).getLong();
    }

    @Override
    public @Nullable Object getObject(int columnIndex) throws SQLException {
        return getField(columnIndex).getObject();
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return getField(columnIndex).getShort();
    }

    @Override
    public @Nullable String getString(int columnIndex) throws SQLException {
        return getField(columnIndex).getString();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #getString(int)}.
     * </p>
     */
    @Override
    public @Nullable String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public @Nullable Time getTime(int columnIndex) throws SQLException {
        return getField(columnIndex).getTime();
    }

    @Override
    public @Nullable Timestamp getTimestamp(int columnIndex) throws SQLException {
        return getField(columnIndex).getTimestamp();
    }

    /**
     * Method is no longer supported since Jaybird 3.0.
     * <p>
     * For old behavior use {@link #getBinaryStream(int)}. For JDBC suggested behavior,
     * use {@link #getCharacterStream(int)}.
     * </p>
     *
     * @throws SQLFeatureNotSupportedException
     *         Always
     * @deprecated
     */
    @Deprecated(since = "1")
    public @Nullable InputStream getUnicodeStream(int columnIndex) throws SQLException {
        throw new SQLFeatureNotSupportedException(UNICODE_STREAM_NOT_SUPPORTED);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #getCharacterStream(int)}.
     * </p>
     */
    @Override
    public @Nullable Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    /**
     * Get the {@code FBField} object at the given column index
     *
     * @param columnIndex
     *         The index of the parameter, 1 is the first index
     * @throws SQLException
     *         If there is an error accessing the field
     */
    public FBField getField(int columnIndex) throws SQLException {
        FBField field = getField(columnIndex, true);
        wasNull = field.isNull();
        return field;
    }

    /**
     * Factory method for the field access objects
     */
    public FBField getField(int columnIndex, boolean checkRowPosition) throws SQLException {
        checkOpen();

        if (checkRowPosition && row == null && rowUpdater == null) {
            throw new SQLException("The result set is not in a row, use next", SQLStateConstants.SQL_STATE_NO_ROW_AVAIL);
        }

        if (columnIndex > rowDescriptor.getCount()) {
            throw new SQLException("Invalid column index: " + columnIndex,
                    SQLStateConstants.SQL_STATE_INVALID_DESC_FIELD_ID);
        }

        return rowUpdater != null ? rowUpdater.getField(columnIndex - 1) : fields[columnIndex - 1];
    }

    /**
     * Get a {@code FBField} by name.
     *
     * @param columnName
     *         The name of the field to be retrieved
     * @throws SQLException
     *         if the field cannot be retrieved
     */
    public FBField getField(String columnName) throws SQLException {
        try {
            int fieldNum = colNames.computeIfAbsent(columnName,
                    SQLExceptionThrowingFunction.toFunction(this::findColumn));
            return getField(fieldNum);
        } catch (UncheckedSQLException e) {
            throw e.getCause();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: ignores {@code scale} and behaves identical to {@link #getBigDecimal(int)}.
     * </p>
     */
    @Deprecated(since = "1")
    @Override
    public @Nullable BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return getField(columnIndex).getBigDecimal(scale);
    }

    @Override
    public @Nullable String getString(String columnName) throws SQLException {
        return getField(columnName).getString();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #getString(String)}.
     * </p>
     */
    @Override
    public @Nullable String getNString(String columnLabel) throws SQLException {
        return getString(columnLabel);
    }

    @Override
    public boolean getBoolean(String columnName) throws SQLException {
        return getField(columnName).getBoolean();
    }

    @Override
    public byte getByte(String columnName) throws SQLException {
        return getField(columnName).getByte();
    }

    @Override
    public short getShort(String columnName) throws SQLException {
        return getField(columnName).getShort();
    }

    @Override
    public int getInt(String columnName) throws SQLException {
        return getField(columnName).getInt();
    }

    @Override
    public long getLong(String columnName) throws SQLException {
        return getField(columnName).getLong();
    }

    @Override
    public float getFloat(String columnName) throws SQLException {
        return getField(columnName).getFloat();
    }

    @Override
    public double getDouble(String columnName) throws SQLException {
        return getField(columnName).getDouble();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: ignores {@code scale} and behaves identical to {@link #getBigDecimal(String)}.
     * </p>
     */
    @Deprecated(since = "1")
    @Override
    public @Nullable BigDecimal getBigDecimal(String columnName, int scale) throws SQLException {
        return getField(columnName).getBigDecimal(scale);
    }

    @Override
    public byte @Nullable [] getBytes(String columnName) throws SQLException {
        return getField(columnName).getBytes();
    }

    @Override
    public @Nullable Date getDate(String columnName) throws SQLException {
        return getField(columnName).getDate();
    }

    @Override
    public @Nullable Time getTime(String columnName) throws SQLException {
        return getField(columnName).getTime();
    }

    @Override
    public @Nullable Timestamp getTimestamp(String columnName) throws SQLException {
        return getField(columnName).getTimestamp();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: works identical to {@link #getBinaryStream(String)}.
     * </p>
     */
    @Override
    public final @Nullable InputStream getAsciiStream(String columnName) throws SQLException {
        return getBinaryStream(columnName);
    }

    /**
     * Method is no longer supported since Jaybird 3.0.
     * <p>
     * For old behavior use {@link #getBinaryStream(String)}. For JDBC suggested behavior,
     * use {@link #getCharacterStream(String)}.
     * </p>
     *
     * @throws SQLFeatureNotSupportedException
     *         Always
     * @deprecated
     */
    @Deprecated(since = "1")
    @Override
    public @Nullable InputStream getUnicodeStream(String columnName) throws SQLException {
        throw new SQLFeatureNotSupportedException(UNICODE_STREAM_NOT_SUPPORTED);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #getCharacterStream(String)}.
     * </p>
     */
    @Override
    public @Nullable Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(columnLabel);
    }

    @Override
    public @Nullable InputStream getBinaryStream(String columnName) throws SQLException {
        return getField(columnName).getBinaryStream();
    }

    /**
     * {@inheritDoc}
     * <p>
     * If connection property {@code reportSQLWarnings} is set to {@code NONE} (case-insensitive), this method will
     * not report warnings and always return {@code null}.
     * </p>
     * <p>
     * <b>NOTE:</b> The implementation currently always returns {@code null} as warnings are never recorded for result
     * sets.
     * </p>
     */
    @Override
    public @Nullable SQLWarning getWarnings() throws SQLException {
        // Warnings are never recorded
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        // nothing to do
    }

    @Override
    public @Nullable String getCursorName() throws SQLException {
        return cursorName;
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new FBResultSetMetaData(rowDescriptor, connection);
    }

    @Override
    public @Nullable Object getObject(String columnName) throws SQLException {
        return getField(columnName).getObject();
    }

    // See section 14.2.3 of jdbc-3.0 specification
    // "Column names supplied to getter methods are case-insensitive
    // If a select list contains the same column more than once, 
    // the first instance of the column will be returned"
    @Override
    public int findColumn(String columnName) throws SQLException {
        requireNonEmpty(columnName);
        Predicate<String> columnNamePredicate;
        if (columnName.startsWith("\"") && columnName.endsWith("\"")) {
            String caseSensitiveColumnName = columnName.substring(1, columnName.length() - 1);
            requireNonEmpty(caseSensitiveColumnName);
            // case-sensitively check columns
            columnNamePredicate = caseSensitiveColumnName::equals;
        } else {
            // case-insensitively check columns
            columnNamePredicate = columnName::equalsIgnoreCase;
        }

        OptionalInt position = findColumn(columnNamePredicate);
        if (position.isPresent()) return position.getAsInt();

        if (columnNamePredicate.test("RDB$DB_KEY")) {
            // Fix up: RDB$DB_KEY is identified as DB_KEY in the result set
            OptionalInt dbKeyPosition = findColumn("DB_KEY"::equals);
            if (dbKeyPosition.isPresent()) return dbKeyPosition.getAsInt();
        }

        throw new SQLException("Column name " + columnName + " not found in result set",
                SQLStateConstants.SQL_STATE_INVALID_DESC_FIELD_ID);
    }

    private static void requireNonEmpty(String columnName) throws SQLException {
        if (columnName == null || columnName.isEmpty()) {
            throw new SQLException("Empty string or null does not identify a column",
                    SQLStateConstants.SQL_STATE_INVALID_DESC_FIELD_ID);
        }
    }

    private OptionalInt findColumn(Predicate<@Nullable String> columnNamePredicate) {
        // Check labels (aliases) first
        OptionalInt position = findColumn(columnNamePredicate, FieldDescriptor::getFieldName);
        if (position.isPresent()) return position;
        // then check underlying column names
        return findColumn(columnNamePredicate, FieldDescriptor::getOriginalName);
    }

    private OptionalInt findColumn(Predicate<@Nullable String> columnNamePredicate,
            Function<FieldDescriptor, @Nullable String> columnNameAccessor) {
        for (int i = 0; i < rowDescriptor.getCount(); i++) {
            if (columnNamePredicate.test(columnNameAccessor.apply(rowDescriptor.getFieldDescriptor(i)))) {
                return OptionalInt.of(i + 1);
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public @Nullable Reader getCharacterStream(int columnIndex) throws SQLException {
        return getField(columnIndex).getCharacterStream();
    }

    @Override
    public @Nullable Reader getCharacterStream(String columnName) throws SQLException {
        return getField(columnName).getCharacterStream();
    }

    @Override
    public @Nullable BigDecimal getBigDecimal(String columnName) throws SQLException {
        return getField(columnName).getBigDecimal();
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return !fbFetcher.isEmpty() && fbFetcher.isBeforeFirst();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return !fbFetcher.isEmpty() && fbFetcher.isAfterLast();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return fbFetcher.isFirst();
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return fbFetcher.isLast();
    }

    @Override
    public void beforeFirst() throws SQLException {
        checkCursorMove();
        fbFetcher.beforeFirst();
        notifyRowUpdater();
    }

    @Override
    public void afterLast() throws SQLException {
        checkCursorMove();
        fbFetcher.afterLast();
        notifyRowUpdater();
    }

    @Override
    public boolean first() throws SQLException {
        checkCursorMove();
        boolean result = fbFetcher.first();
        if (result)
            notifyRowUpdater();
        return result;
    }

    @Override
    public boolean last() throws SQLException {
        checkCursorMove();
        boolean result = fbFetcher.last();
        if (result)
            notifyRowUpdater();
        return result;
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return fbFetcher.getRowNum();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        checkCursorMove();
        boolean result = fbFetcher.absolute(row);
        if (result)
            notifyRowUpdater();
        return result;
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        checkCursorMove();
        boolean result = fbFetcher.relative(rows);
        if (result)
            notifyRowUpdater();
        return result;
    }

    @Override
    public boolean previous() throws SQLException {
        checkCursorMove();
        boolean result = fbFetcher.previous();
        if (result)
            notifyRowUpdater();
        return result;
    }

    private ResultSetBehavior behavior() {
        return fbFetcher.getFetchConfig().resultSetBehavior();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        if (direction == ResultSet.FETCH_REVERSE || direction == ResultSet.FETCH_UNKNOWN) {
            checkScrollable();
        }
        fbFetcher.setFetchDirection(direction);
    }

    @SuppressWarnings("MagicConstant")
    @Override
    public int getFetchDirection() throws SQLException {
        return fbFetcher.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        fbFetcher.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return fbFetcher.getFetchSize();
    }

    @SuppressWarnings("MagicConstant")
    @Override
    public int getType() throws SQLException {
        checkOpen();
        return behavior().type();
    }

    @SuppressWarnings("MagicConstant")
    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return behavior().concurrency();
    }

    @SuppressWarnings("MagicConstant")
    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return behavior().holdability();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkUpdatable();
        return fbFetcher.rowUpdated();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkUpdatable();
        return fbFetcher.rowInserted();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        checkUpdatable();
        return fbFetcher.rowDeleted();
    }

    /**
     * Checks if the result set is updatable, throwing {@link FBResultSetNotUpdatableException} otherwise.
     *
     * @throws FBResultSetNotUpdatableException
     *         when this result set is not updatable
     * @see #requireRowUpdater()
     */
    private void checkUpdatable() throws SQLException {
        checkOpen();
        if (rowUpdater == null) {
            throw new FBResultSetNotUpdatableException();
        }
    }

    /**
     * Checks if the result set is updatable, returning the row updater, throwing
     * {@link FBResultSetNotUpdatableException} otherwise.
     *
     * @return row updater
     * @throws FBResultSetNotUpdatableException
     *         when this result set is not updatable
     * @see #checkUpdatable()
     */
    private FirebirdRowUpdater requireRowUpdater() throws SQLException {
        checkOpen();
        FirebirdRowUpdater rowUpdater = this.rowUpdater;
        if (rowUpdater == null) {
            throw new FBResultSetNotUpdatableException();
        }
        return rowUpdater;
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setNull();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setBoolean(x);
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setByte(x);
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setShort(x);
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setInteger(x);
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setLong(x);
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setFloat(x);
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setDouble(x);
    }

    @Override
    public void updateBigDecimal(int columnIndex, @Nullable BigDecimal x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setBigDecimal(x);
    }

    @Override
    public void updateString(int columnIndex, @Nullable String x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setString(x);
    }

    @Override
    public void updateBytes(int columnIndex, byte @Nullable [] x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setBytes(x);
    }

    @Override
    public void updateDate(int columnIndex, @Nullable Date x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setDate(x);
    }

    @Override
    public void updateTime(int columnIndex, @Nullable Time x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setTime(x);
    }

    @Override
    public void updateTimestamp(int columnIndex, @Nullable Timestamp x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setTimestamp(x);
    }

    @Override
    public void updateBinaryStream(int columnIndex, @Nullable InputStream x, int length) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setBinaryStream(x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, @Nullable InputStream x, long length) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setBinaryStream(x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, @Nullable InputStream x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setBinaryStream(x);
    }

    @Override
    public void updateBinaryStream(String columnName, @Nullable InputStream x, int length) throws SQLException {
        checkUpdatable();
        getField(columnName).setBinaryStream(x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, @Nullable InputStream x, long length) throws SQLException {
        checkUpdatable();
        getField(columnLabel).setBinaryStream(x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, @Nullable InputStream x) throws SQLException {
        checkUpdatable();
        getField(columnLabel).setBinaryStream(x);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Jaybird delegates to {@link #updateObject(int, Object)} and ignores the value of {@code scaleOrLength}, if
     * {@code x} is anything other than a {@link Reader} or {@link InputStream}.
     * </p>
     */
    @Override
    public void updateObject(int columnIndex, @Nullable Object x, int scaleOrLength) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setObject(x, scaleOrLength);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Jaybird delegates to {@link #updateObject(int, Object, int)} and ignores the value of {@code targetSqlType}.
     * </p>
     */
    @Override
    public void updateObject(int columnIndex, @Nullable Object x, SQLType targetSqlType, int scaleOrLength)
            throws SQLException {
        updateObject(columnIndex, x, scaleOrLength);
    }

    @Override
    public void updateObject(int columnIndex, @Nullable Object x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setObject(x);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Jaybird delegates to {@link #updateObject(int, Object)} and ignores the value of {@code targetSqlType}
     * </p>
     */
    @Override
    public void updateObject(int columnIndex, @Nullable Object x, SQLType targetSqlType) throws SQLException {
        updateObject(columnIndex, x);
    }

    @Override
    public void updateNull(String columnName) throws SQLException {
        checkUpdatable();
        getField(columnName).setNull();
    }

    @Override
    public void updateBoolean(String columnName, boolean x) throws SQLException {
        checkUpdatable();
        getField(columnName).setBoolean(x);
    }

    @Override
    public void updateByte(String columnName, byte x) throws SQLException {
        checkUpdatable();
        getField(columnName).setByte(x);
    }

    @Override
    public void updateShort(String columnName, short x) throws SQLException {
        checkUpdatable();
        getField(columnName).setShort(x);
    }

    @Override
    public void updateInt(String columnName, int x) throws SQLException {
        checkUpdatable();
        getField(columnName).setInteger(x);
    }

    @Override
    public void updateLong(String columnName, long x) throws SQLException {
        checkUpdatable();
        getField(columnName).setLong(x);
    }

    @Override
    public void updateFloat(String columnName, float x) throws SQLException {
        checkUpdatable();
        getField(columnName).setFloat(x);
    }

    @Override
    public void updateDouble(String columnName, double x) throws SQLException {
        checkUpdatable();
        getField(columnName).setDouble(x);
    }

    @Override
    public void updateBigDecimal(String columnName, @Nullable BigDecimal x) throws SQLException {
        checkUpdatable();
        getField(columnName).setBigDecimal(x);
    }

    @Override
    public void updateString(String columnName, @Nullable String x) throws SQLException {
        checkUpdatable();
        getField(columnName).setString(x);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateString(int, String)}.
     * </p>
     */
    @Override
    public void updateNString(int columnIndex, @Nullable String string) throws SQLException {
        updateString(columnIndex, string);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateString(String, String)}.
     * </p>
     */
    @Override
    public void updateNString(String columnLabel, @Nullable String string) throws SQLException {
        updateString(columnLabel, string);
    }

    @Override
    public void updateBytes(String columnName, byte @Nullable [] x) throws SQLException {
        checkUpdatable();
        getField(columnName).setBytes(x);
    }

    @Override
    public void updateDate(String columnName, @Nullable Date x) throws SQLException {
        checkUpdatable();
        getField(columnName).setDate(x);
    }

    @Override
    public void updateTime(String columnName, @Nullable Time x) throws SQLException {
        checkUpdatable();
        getField(columnName).setTime(x);
    }

    @Override
    public void updateTimestamp(String columnName, @Nullable Timestamp x) throws SQLException {
        checkUpdatable();
        getField(columnName).setTimestamp(x);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: works identical to {@link #updateBinaryStream(int, InputStream, int)}.
     * </p>
     */
    @Override
    public final void updateAsciiStream(int columnIndex, @Nullable InputStream x, int length) throws SQLException {
        updateBinaryStream(columnIndex, x, length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: works identical to {@link #updateBinaryStream(String, InputStream, int)}.
     * </p>
     */
    @Override
    public final void updateAsciiStream(String columnName, @Nullable InputStream x, int length) throws SQLException {
        updateBinaryStream(columnName, x, length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: works identical to {@link #updateBinaryStream(int, InputStream, long)}.
     * </p>
     */
    @Override
    public final void updateAsciiStream(int columnIndex, @Nullable InputStream x, long length) throws SQLException {
        updateBinaryStream(columnIndex, x, length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: works identical to {@link #updateBinaryStream(int, InputStream)}.
     * </p>
     */
    @Override
    public final void updateAsciiStream(int columnIndex, @Nullable InputStream x) throws SQLException {
        updateBinaryStream(columnIndex, x);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: works identical to {@link #updateBinaryStream(String, InputStream, long)}.
     * </p>
     */
    @Override
    public final void updateAsciiStream(String columnLabel, @Nullable InputStream x, long length) throws SQLException {
        updateBinaryStream(columnLabel, x, length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: works identical to {@link #updateBinaryStream(String, InputStream)}.
     * </p>
     */
    @Override
    public final void updateAsciiStream(String columnLabel, @Nullable InputStream x) throws SQLException {
        updateBinaryStream(columnLabel, x);
    }

    @Override
    public void updateCharacterStream(int columnIndex, @Nullable Reader x, int length) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setCharacterStream(x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, @Nullable Reader x, long length) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setCharacterStream(x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, @Nullable Reader x) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setCharacterStream(x);
    }

    @Override
    public void updateCharacterStream(String columnName, @Nullable Reader reader, int length) throws SQLException {
        checkUpdatable();
        getField(columnName).setCharacterStream(reader, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, @Nullable Reader reader, long length) throws SQLException {
        checkUpdatable();
        getField(columnLabel).setCharacterStream(reader, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, @Nullable Reader reader) throws SQLException {
        checkUpdatable();
        getField(columnLabel).setCharacterStream(reader);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateCharacterStream(int, Reader, long)}.
     * </p>
     */
    @Override
    public void updateNCharacterStream(int columnIndex, @Nullable Reader x, long length) throws SQLException {
        updateCharacterStream(columnIndex, x, length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateCharacterStream(int, Reader)}.
     * </p>
     */
    @Override
    public void updateNCharacterStream(int columnIndex, @Nullable Reader x) throws SQLException {
        updateCharacterStream(columnIndex, x);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateClob(String, Reader, long)}.
     * </p>
     */
    @Override
    public void updateNCharacterStream(String columnLabel, @Nullable Reader reader, long length) throws SQLException {
        updateCharacterStream(columnLabel, reader, length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateCharacterStream(String, Reader)}.
     * </p>
     */
    @Override
    public void updateNCharacterStream(String columnLabel, @Nullable Reader reader) throws SQLException {
        updateCharacterStream(columnLabel, reader);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Jaybird delegates to {@link #updateObject(String, Object)} and ignores the value of {@code scaleOrLength}, if
     * {@code x} is anything other than a {@link Reader} or {@link InputStream}.
     * </p>
     */
    @Override
    public void updateObject(String columnName, @Nullable Object x, int scaleOrLength) throws SQLException {
        checkUpdatable();
        getField(columnName).setObject(x, scaleOrLength);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Jaybird delegates to {@link #updateObject(String, Object, int)} and ignores the value of {@code targetSqlType}.
     * </p>
     */
    @Override
    public void updateObject(String columnLabel, @Nullable Object x, SQLType targetSqlType, int scaleOrLength)
            throws SQLException {
        updateObject(columnLabel, x, scaleOrLength);
    }

    @Override
    public void updateObject(String columnName, @Nullable Object x) throws SQLException {
        checkUpdatable();
        getField(columnName).setObject(x);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Jaybird delegates to {@link #updateObject(String, Object)} and ignores the value of {@code targetSqlType}.
     * </p>
     */
    @Override
    public void updateObject(String columnLabel, @Nullable Object x, SQLType targetSqlType) throws SQLException {
        updateObject(columnLabel, x);
    }

    @Override
    public void insertRow() throws SQLException {
        FirebirdRowUpdater rowUpdater = requireRowUpdater();
        fbFetcher.beforeExecuteInsert();
        rowUpdater.insertRow();
        fbFetcher.insertRow(rowUpdater.getInsertRow());
        notifyRowUpdater();
    }

    @Override
    public void updateRow() throws SQLException {
        FirebirdRowUpdater rowUpdater = requireRowUpdater();
        rowUpdater.updateRow();
        fbFetcher.updateRow(rowUpdater.getNewRow());
        notifyRowUpdater();
    }

    @Override
    public void deleteRow() throws SQLException {
        requireRowUpdater().deleteRow();
        fbFetcher.deleteRow();
        notifyRowUpdater();
    }

    @Override
    public void refreshRow() throws SQLException {
        FirebirdRowUpdater rowUpdater = requireRowUpdater();
        rowUpdater.refreshRow();
        fbFetcher.updateRow(rowUpdater.getOldRow());
        // this is excessive, but we do this to keep the code uniform
        notifyRowUpdater();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        requireRowUpdater().cancelRowUpdates();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        requireRowUpdater().moveToInsertRow();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        requireRowUpdater().moveToCurrentRow();
        // Make sure we have the correct data of the row
        fbFetcher.renotifyCurrentRow();
        notifyRowUpdater();
    }

    @Override
    public @Nullable Statement getStatement() {
        return statement;
    }

    @Override
    public @Nullable Object getObject(int i, Map<String, Class<?>> map) throws SQLException {
        return getField(i).getObject(map);
    }

    @Override
    public @Nullable Ref getRef(int i) throws SQLException {
        return getField(i).getRef();
    }

    @Override
    public @Nullable Clob getClob(int i) throws SQLException {
        return getField(i).getClob();
    }

    @Override
    public @Nullable Array getArray(int i) throws SQLException {
        return getField(i).getArray();
    }

    @Override
    public @Nullable Object getObject(String columnName, Map<String, Class<?>> map) throws SQLException {
        return getField(columnName).getObject(map);
    }

    @Override
    public @Nullable Ref getRef(String columnName) throws SQLException {
        return getField(columnName).getRef();
    }

    @Override
    public @Nullable Blob getBlob(String columnName) throws SQLException {
        return getField(columnName).getBlob();
    }

    @Override
    public @Nullable Clob getClob(String columnName) throws SQLException {
        return getField(columnName).getClob();
    }

    @Override
    public @Nullable Array getArray(String columnName) throws SQLException {
        return getField(columnName).getArray();
    }

    @Override
    public @Nullable Date getDate(int columnIndex, @Nullable Calendar cal) throws SQLException {
        return getField(columnIndex).getDate(cal);
    }

    @Override
    public @Nullable Date getDate(String columnName, @Nullable Calendar cal) throws SQLException {
        return getField(columnName).getDate(cal);
    }

    @Override
    public @Nullable Time getTime(int columnIndex, @Nullable Calendar cal) throws SQLException {
        return getField(columnIndex).getTime(cal);
    }

    @Override
    public @Nullable Time getTime(String columnName, @Nullable Calendar cal) throws SQLException {
        return getField(columnName).getTime(cal);
    }

    @Override
    public @Nullable Timestamp getTimestamp(int columnIndex, @Nullable Calendar cal) throws SQLException {
        return getField(columnIndex).getTimestamp(cal);
    }

    @Override
    public @Nullable Timestamp getTimestamp(String columnName, @Nullable Calendar cal) throws SQLException {
        return getField(columnName).getTimestamp(cal);
    }

    @Override
    public @Nullable URL getURL(int param1) throws SQLException {
        throw typeNotSupported("URL");
    }

    @Override
    public @Nullable URL getURL(String param1) throws SQLException {
        throw typeNotSupported("URL");
    }

    @Override
    public <T extends @Nullable Object> @Nullable T getObject(int columnIndex, Class<T> type) throws SQLException {
        return getField(columnIndex).getObject(type);
    }

    @Override
    public <T extends @Nullable Object> @Nullable T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getField(columnLabel).getObject(type);
    }

    @Override
    public void updateRef(int param1, @Nullable Ref param2) throws SQLException {
        throw typeNotSupported("REF");
    }

    @Override
    public void updateRef(String param1, @Nullable Ref param2) throws SQLException {
        throw typeNotSupported("REF");
    }

    @Override
    public void updateBlob(int columnIndex, @Nullable Blob blob) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setBlob(blob);
    }

    @Override
    public void updateBlob(String columnLabel, @Nullable Blob blob) throws SQLException {
        checkUpdatable();
        getField(columnLabel).setBlob(blob);
    }

    @Override
    public void updateBlob(int columnIndex, @Nullable InputStream inputStream, long length) throws SQLException {
        updateBinaryStream(columnIndex, inputStream, length);
    }

    @Override
    public void updateBlob(int columnIndex, @Nullable InputStream inputStream) throws SQLException {
        updateBinaryStream(columnIndex, inputStream);
    }

    @Override
    public void updateBlob(String columnLabel, @Nullable InputStream inputStream, long length) throws SQLException {
        updateBinaryStream(columnLabel, inputStream, length);
    }

    @Override
    public void updateBlob(String columnLabel, @Nullable InputStream inputStream) throws SQLException {
        updateBinaryStream(columnLabel, inputStream);
    }

    @Override
    public void updateClob(int columnIndex, @Nullable Clob clob) throws SQLException {
        checkUpdatable();
        getField(columnIndex).setClob(clob);
    }

    @Override
    public void updateClob(String columnLabel, @Nullable Clob clob) throws SQLException {
        checkUpdatable();
        getField(columnLabel).setClob(clob);
    }

    @Override
    public void updateClob(int columnIndex, @Nullable Reader reader, long length) throws SQLException {
        updateCharacterStream(columnIndex, reader, length);
    }

    @Override
    public void updateClob(int columnIndex, @Nullable Reader reader) throws SQLException {
        updateCharacterStream(columnIndex, reader);
    }

    @Override
    public void updateClob(String columnLabel, @Nullable Reader reader, long length) throws SQLException {
        updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateClob(String columnLabel, @Nullable Reader reader) throws SQLException {
        updateCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateArray(int param1, @Nullable Array param2) throws SQLException {
        throw new FBDriverNotCapableException("Type ARRAY not yet supported");
    }

    @Override
    public void updateArray(String param1, @Nullable Array param2) throws SQLException {
        throw new FBDriverNotCapableException("Type ARRAY not yet supported");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #getClob(int)}.
     * </p>
     */
    @Override
    public @Nullable NClob getNClob(int columnIndex) throws SQLException {
        return (NClob) getClob(columnIndex);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #getClob(String)}.
     * </p>
     */
    @Override
    public @Nullable NClob getNClob(String columnLabel) throws SQLException {
        return (NClob) getClob(columnLabel);
    }

    @Override
    public @Nullable RowId getRowId(int columnIndex) throws SQLException {
        return getField(columnIndex).getRowId();
    }

    @Override
    public @Nullable RowId getRowId(String columnLabel) throws SQLException {
        return getField(columnLabel).getRowId();
    }

    @Override
    public @Nullable SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw typeNotSupported(TYPE_SQLXML);
    }

    @Override
    public @Nullable SQLXML getSQLXML(String columnLabel) throws SQLException {
        throw typeNotSupported(TYPE_SQLXML);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateClob(int, Clob)}.
     * </p>
     */
    @Override
    public void updateNClob(int columnIndex, @Nullable NClob clob) throws SQLException {
        updateClob(columnIndex, clob);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateClob(int, Reader, long)}.
     * </p>
     */
    @Override
    public void updateNClob(int columnIndex, @Nullable Reader reader, long length) throws SQLException {
        updateCharacterStream(columnIndex, reader, length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateClob(int, Reader)}.
     * </p>
     */
    @Override
    public void updateNClob(int columnIndex, @Nullable Reader reader) throws SQLException {
        updateCharacterStream(columnIndex, reader);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateClob(String, Clob)}.
     * </p>
     */
    @Override
    public void updateNClob(String columnLabel, @Nullable NClob clob) throws SQLException {
        updateClob(columnLabel, clob);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateClob(int, Reader, long)}.
     * </p>
     */
    @Override
    public void updateNClob(String columnLabel, @Nullable Reader reader, long length) throws SQLException {
        updateCharacterStream(columnLabel, reader, length);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note: This method behaves exactly the same as {@link #updateClob(String, Reader)}.
     * </p>
     */
    @Override
    public void updateNClob(String columnLabel, @Nullable Reader reader) throws SQLException {
        updateCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateRowId(int columnIndex, @Nullable RowId x) throws SQLException {
        rowIdNotUpdatable();
    }

    @Override
    public void updateRowId(String columnLabel, @Nullable RowId x) throws SQLException {
        rowIdNotUpdatable();
    }

    private void rowIdNotUpdatable() throws SQLException {
        checkUpdatable();
        throw new FBDriverNotCapableException("Firebird rowId (RDB$DB_KEY) is not updatable");
    }

    @Override
    public void updateSQLXML(int columnIndex, @Nullable SQLXML xmlObject) throws SQLException {
        throw typeNotSupported(TYPE_SQLXML);
    }

    @Override
    public void updateSQLXML(String columnLabel, @Nullable SQLXML xmlObject) throws SQLException {
        throw typeNotSupported(TYPE_SQLXML);
    }

    @Override
    public @Nullable String getExecutionPlan() throws SQLException {
        checkCursorMove();
        if (statement == null) return "";
        return statement.getExecutionPlan();
    }

    @Override
    public @Nullable String getExplainedExecutionPlan() throws SQLException {
        checkCursorMove();
        if (statement == null) return "";
        return statement.getExplainedExecutionPlan();
    }

    @SuppressWarnings("ConstantValue")
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface != null && iface.isAssignableFrom(this.getClass());
    }

    @SuppressWarnings("ConstantValue")
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (!isWrapperFor(iface)) {
            throw FbExceptionBuilder.forException(JaybirdErrorCodes.jb_unableToUnwrap)
                    .messageParameter(iface != null ? iface.getName() : "(null)")
                    .toSQLException();
        }

        return iface.cast(this);
    }

    private static SQLException typeNotSupported(String typeName) {
        return new FBDriverNotCapableException("Type " + typeName + " not supported");
    }

    @SuppressWarnings("DataFlowIssue")
    private final class DataProvider implements FieldDataProvider {

        private final int fieldPosition;

        private DataProvider(int fieldPosition) {
            this.fieldPosition = fieldPosition;
        }

        @Override
        public byte @Nullable [] getFieldData() {
            return row.getFieldData(fieldPosition);
        }

        @Override
        public void setFieldData(byte @Nullable [] data) {
            row.setFieldData(fieldPosition, data);
        }
    }

}
