/*
 * Firebird Open Source JDBC Driver
 *
 * Distributable under LGPL license.
 * You may obtain a copy of the License at http://www.gnu.org/copyleft/lgpl.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * LGPL License for more details.
 *
 * This file was created by members of the firebird development team.
 * All individual contributions remain the Copyright (C) of those
 * individuals.  Contributors to this file are either listed here or
 * can be obtained from a source control history command.
 *
 * All rights reserved.
 */
package org.firebirdsql.jdbc;

import org.firebirdsql.encodings.EncodingFactory;
import org.firebirdsql.gds.impl.GDSFactory;
import org.firebirdsql.gds.impl.GDSHelper;
import org.firebirdsql.gds.impl.GDSType;
import org.firebirdsql.gds.ng.DatatypeCoder;
import org.firebirdsql.gds.ng.DefaultDatatypeCoder;
import org.firebirdsql.gds.ng.LockCloseable;
import org.firebirdsql.gds.ng.fields.RowDescriptor;
import org.firebirdsql.gds.ng.fields.RowDescriptorBuilder;
import org.firebirdsql.gds.ng.fields.RowValue;
import org.firebirdsql.jdbc.metadata.RowValueBuilder;
import org.firebirdsql.jaybird.Version;
import org.firebirdsql.jdbc.escape.FBEscapedFunctionHelper;
import org.firebirdsql.jdbc.metadata.*;
import org.firebirdsql.logging.Logger;
import org.firebirdsql.logging.LoggerFactory;
import org.firebirdsql.util.FirebirdSupportInfo;

import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.*;
import java.util.*;

import static org.firebirdsql.gds.ISCConstants.*;
import static org.firebirdsql.jdbc.metadata.FbMetadataConstants.*;
import static org.firebirdsql.util.FirebirdSupportInfo.supportInfoFor;

/**
 * Comprehensive information about the database as a whole.
 *
 * @author <a href="mailto:d_jencks@users.sourceforge.net">David Jencks</a>
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 */
@SuppressWarnings("RedundantThrows")
public class FBDatabaseMetaData implements FirebirdDatabaseMetaData {

    private final static Logger log = LoggerFactory.getLogger(FBDatabaseMetaData.class);

    protected static final DatatypeCoder datatypeCoder =
            DefaultDatatypeCoder.forEncodingFactory(EncodingFactory.createInstance(StandardCharsets.UTF_8));

    private static final byte[] NO_BYTES = getBytes("NO");
    private static final byte[] INT_ZERO = createInt(0);
    private static final byte[] RADIX_TEN = createInt(10);
    private static final byte[] BIGINT_PRECISION = createInt(FbMetadataConstants.BIGINT_PRECISION);

    private final GDSHelper gdsHelper;
    private final FBConnection connection;
    private final FirebirdSupportInfo firebirdSupportInfo;

    private static final int STATEMENT_CACHE_SIZE = 12;
    private final Map<String, FBPreparedStatement> statements = new LruPreparedStatementCache(STATEMENT_CACHE_SIZE);
    private final FirebirdVersionMetaData versionMetaData;

    protected FBDatabaseMetaData(FBConnection c) throws SQLException {
        this.gdsHelper = c.getGDSHelper();
        this.connection = c;
        firebirdSupportInfo = supportInfoFor(c);
        versionMetaData = FirebirdVersionMetaData.getVersionMetaDataFor(c);
    }

    @Override
    public void close() {
        try (LockCloseable ignored = connection.withLock()) {
            if (statements.isEmpty()) {
                return;
            }
            try {
                for (FBStatement stmt : statements.values()) {
                    try {
                        stmt.close();
                    } catch (Exception e) {
                        log.warnDebug("error closing cached statements in DatabaseMetaData.close", e);
                    }
                }
            } finally {
                statements.clear();
            }
        }
    }

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        //returns all procedures whether or not you have execute permission
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        //returns all tables matching criteria independent of access permissions.
        return false;
    }

    @Override
    public String getURL() throws SQLException {
        // TODO Think of a less complex way to obtain the url or just return null?
        GDSType gdsType = connection.mc.getManagedConnectionFactory().getGDSType();
        return GDSFactory.getJdbcUrl(gdsType, gdsHelper.getConnectionProperties());
    }

    @Override
    public String getUserName() throws SQLException {
        return gdsHelper.getUserName();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return false;//could be true, not yetimplemented
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        // in Firebird 1.5.x NULLs are always sorted at the end
        // in Firebird 2.0.x NULLs are sorted low
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        // in Firebird 1.5.x NULLs are always sorted at the end
        // in Firebird 2.0.x NULLs are sorted low
        return gdsHelper.compareToVersion(2, 0) >= 0;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        // in Firebird 1.5.x NULLs are always sorted at the end
        // in Firebird 2.0.x NULLs are sorted low
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        // in Firebird 1.5.x NULLs are always sorted at the end
        // in Firebird 2.0.x NULLs are sorted low
        return gdsHelper.compareToVersion(2, 0) < 0;
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        return gdsHelper.getDatabaseProductName();
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return gdsHelper.getDatabaseProductVersion();
    }

    @Override
    public String getDriverName() throws SQLException {
        // Retain JCA in name for compatibility with tools that consult metadata and use this string
        return "Jaybird JCA/JDBC driver";
    }

    @Override
    public String getDriverVersion() throws SQLException {
        return Version.JAYBIRD_SIMPLE_VERSION;
    }

    @Override
    public int getDriverMajorVersion() {
        return Version.JAYBIRD_MAJOR_VERSION;
    }

    @Override
    public int getDriverMinorVersion() {
        return Version.JAYBIRD_MINOR_VERSION;
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        return false;
    }

    // TODO implement statement pooling on the server.. then in the driver
    @Override
    public boolean supportsStatementPooling() throws SQLException {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        // Firebird creates a new blob when making changes
        return true;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        return true;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        return getConnectionDialect() == 1 ? " " : "\"";
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        return versionMetaData.getSqlKeywords();
    }

    /**
     * {@inheritDoc}
     * <p>
     * NOTE: Some of the functions listed may only work on Firebird 2.1 or higher, or when equivalent UDFs
     * are installed.
     * </p>
     */
    @Override
    public String getNumericFunctions() throws SQLException {
        return collectionToCommaSeparatedList(FBEscapedFunctionHelper.getSupportedNumericFunctions());
    }

    private static String collectionToCommaSeparatedList(Collection<String> collection) {
        StringBuilder sb = new StringBuilder();
        for (String item : collection) {
            sb.append(item);
            sb.append(',');
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     * <p>
     * NOTE: Some of the functions listed may only work on Firebird 2.1 or higher, or when equivalent UDFs
     * are installed.
     * </p>
     */
    @Override
    public String getStringFunctions() throws SQLException {
        return collectionToCommaSeparatedList(FBEscapedFunctionHelper.getSupportedStringFunctions());
    }

    /**
     * {@inheritDoc}
     * <p>
     * NOTE: Some of the functions listed may only work on Firebird 2.1 or higher, or when equivalent UDFs
     * are installed.
     * </p>
     */
    @Override
    public String getSystemFunctions() throws SQLException {
        return collectionToCommaSeparatedList(FBEscapedFunctionHelper.getSupportedSystemFunctions());
    }

    /**
     * {@inheritDoc}
     * <p>
     * NOTE: Some of the functions listed may only work on Firebird 2.1 or higher, or when equivalent UDFs
     * are installed.
     * </p>
     */
    @Override
    public String getTimeDateFunctions() throws SQLException {
        return collectionToCommaSeparatedList(FBEscapedFunctionHelper.getSupportedTimeDateFunctions());
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        return "$";
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * See also {@code org.firebirdsql.jdbc.escape.ConvertFunction} for caveats.
     * </p>
     */
    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        switch (fromType) {
        case JaybirdTypeCodes.DECFLOAT:
            if (!firebirdSupportInfo.supportsDecfloat()) {
                return false;
            }
            // Intentional fallthrough
        case Types.TINYINT: // Doesn't exist in Firebird; handled as if SMALLINT
        case Types.SMALLINT:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.FLOAT:
        case Types.REAL:
        case Types.DOUBLE:
        case Types.NUMERIC:
        case Types.DECIMAL:
            // Numerical values all convertible to the same types.
            switch (toType) {
            case Types.TINYINT: // Doesn't exist in Firebird; handled as if SMALLINT
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
            case Types.NCHAR:
            case Types.LONGNVARCHAR:
            case Types.NVARCHAR:
            case Types.NCLOB:
                return true;
            // casting numerical values to binary types will result in ASCII bytes of string conversion, not to the
            // binary representation of the number (eg 1 will be converted to binary 0x31 (ASCII '1'), not 0x01)
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return true;
            case JaybirdTypeCodes.DECFLOAT:
                return firebirdSupportInfo.supportsDecfloat();
            default:
                return false;
            }

        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
        case Types.CLOB:
        case Types.NCHAR:
        case Types.LONGNVARCHAR:
        case Types.NVARCHAR:
        case Types.NCLOB:
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BLOB:
        case Types.ROWID: // Internally rowid is not discernible from BINARY
            // String and binary values all convertible to the same types
            // Be aware though that casting of binary to non-string/binary will perform the same conversion as
            // if it is an ASCII string value. Eg the binary string value 0x31 cast to integer will be 1, not 49.
            switch (toType) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
            case Types.NCHAR:
            case Types.LONGNVARCHAR:
            case Types.NVARCHAR:
            case Types.NCLOB:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return true;
            case Types.TINYINT: // Doesn't exist in Firebird; handled as if SMALLINT
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
                return fromType != Types.ROWID;
            case JaybirdTypeCodes.DECFLOAT:
                return fromType != Types.ROWID && firebirdSupportInfo.supportsDecfloat();
            case Types.BOOLEAN:
                return fromType != Types.ROWID && firebirdSupportInfo.supportsBoolean();
            case Types.ROWID:
                // As size of rowid is context dependent, we can't cast to it using the convert escape
                return false;
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return fromType != Types.ROWID && firebirdSupportInfo.supportsTimeZones();
            default:
                return false;
            }

        case Types.DATE:
            switch(toType) {
            case Types.DATE:
            case Types.TIMESTAMP:
                return true;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return false;
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return firebirdSupportInfo.supportsTimeZones();
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
            case Types.NCHAR:
            case Types.LONGNVARCHAR:
            case Types.NVARCHAR:
            case Types.NCLOB:
                return true;
            // casting date/time values to binary types will result in ASCII bytes of string conversion
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return true;
            default:
                return false;
            }
        case Types.TIME:
            switch(toType) {
            case Types.TIMESTAMP:
            case Types.TIME:
                return true;
            case Types.DATE:
                return false;
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return firebirdSupportInfo.supportsTimeZones();
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
            case Types.NCHAR:
            case Types.LONGNVARCHAR:
            case Types.NVARCHAR:
            case Types.NCLOB:
                return true;
            // casting date/time values to binary types will result in ASCII bytes of string conversion
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return true;
            default:
                return false;
            }
        case Types.TIMESTAMP:
            switch(toType) {
            case Types.TIMESTAMP:
            case Types.TIME:
            case Types.DATE:
                return true;
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return firebirdSupportInfo.supportsTimeZones();
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CLOB:
            case Types.NCHAR:
            case Types.LONGNVARCHAR:
            case Types.NVARCHAR:
            case Types.NCLOB:
                return true;
            // casting date/time values to binary types will result in ASCII bytes of string conversion
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return true;
            default:
                return false;
            }

        case Types.NULL:
            // If a type can be cast to itself, then null can be cast to it as well
            return toType != Types.NULL && supportsConvert(toType, toType);

        case Types.BOOLEAN:
            if (firebirdSupportInfo.supportsBoolean()) {
                switch (toType) {
                case Types.BOOLEAN:
                case Types.CHAR:
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.CLOB:
                case Types.NCHAR:
                case Types.LONGNVARCHAR:
                case Types.NVARCHAR:
                case Types.NCLOB:
                    return true;
                // casting boolean values to binary types will result in ASCII bytes of string conversion
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.LONGVARBINARY:
                case Types.BLOB:
                    return true;
                default:
                    return false;
                }
            }
            return false;

        case Types.TIME_WITH_TIMEZONE:
            if (firebirdSupportInfo.supportsTimeZones()) {
                switch (toType) {
                case Types.TIME:
                case Types.TIMESTAMP:
                    return true;
                case Types.DATE:
                    return false;
                case Types.TIME_WITH_TIMEZONE:
                case Types.TIMESTAMP_WITH_TIMEZONE:
                    return true;
                case Types.CHAR:
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.CLOB:
                case Types.NCHAR:
                case Types.LONGNVARCHAR:
                case Types.NVARCHAR:
                case Types.NCLOB:
                    return true;
                // casting date/time values to binary types will result in ASCII bytes of string conversion
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.LONGVARBINARY:
                case Types.BLOB:
                    return true;
                default:
                    return false;
                }
            }
            return false;
        case Types.TIMESTAMP_WITH_TIMEZONE:
            if (firebirdSupportInfo.supportsTimeZones()) {
                switch (toType) {
                case Types.TIME:
                case Types.TIMESTAMP:
                case Types.DATE:
                case Types.TIME_WITH_TIMEZONE:
                case Types.TIMESTAMP_WITH_TIMEZONE:
                    return true;
                case Types.CHAR:
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.CLOB:
                case Types.NCHAR:
                case Types.LONGNVARCHAR:
                case Types.NVARCHAR:
                case Types.NCLOB:
                    return true;
                // casting date/time values to binary types will result in ASCII bytes of string conversion
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.LONGVARBINARY:
                case Types.BLOB:
                    return true;
                default:
                    return false;
                }
            }
            return false;

        case Types.ARRAY:
            // Arrays are not supported by Jaybird (and casting would be tricky anyway)
            return false;
        // Unsupported types
        case Types.BIT:
        case Types.OTHER:
        case Types.JAVA_OBJECT:
        case Types.DISTINCT:
        case Types.STRUCT:
        case Types.REF:
        case Types.DATALINK:
        case Types.SQLXML:
        case Types.REF_CURSOR:
        default:
            return false;
        }
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return firebirdSupportInfo.isVersionEqualOrAbove(1, 5);
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        // TODO Verify
        return false;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        // TODO Verify
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        // TODO Verify
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        // TODO Verify
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        return true; // rrokytskyy: yep, they call so foreign keys + cascade deletes
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return the vendor term, always {@code null} because schemas are not supported by database server (see JDBC CTS
     * for details).
     */
    @Override
    public String getSchemaTerm() throws SQLException {
        return null;
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        return "PROCEDURE";
    }

    /**
     * {@inheritDoc}
     *
     * @return the vendor term, always {@code null} because catalogs are not supported by database server (see JDBC CTS
     * for details).
     */
    @Override
    public String getCatalogTerm() throws SQLException {
        return null;
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @return the separator string, always {@code null} because catalogs are not supported by database server (see
     * JDBC CTS for details).
     */
    @Override
    public String getCatalogSeparator() throws SQLException {
        return null;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        return false;//only when commit retaining is executed I think
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        return false;//commit retaining only.
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        return true;
    }

    //----------------------------------------------------------------------
    // The following group of methods exposes various limitations
    // based on the target database with the current driver.
    // Unless otherwise specified, a result of zero means there is no
    // limit, or the limit is not known.

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        return 0; // TODO 32764 Test (assumed on length/2 and max string literal length)
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        return 32765;
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        return getMaxObjectNameLength();
    }

    private int getMaxObjectNameLength() {
        if (gdsHelper.compareToVersion(4, 0) < 0) {
            return OBJECT_NAME_LENGTH_BEFORE_V4_0;
        } else {
            return OBJECT_NAME_LENGTH_V4_0;
        }
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        return 0; //I don't know
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        return 0; //I don't know
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        return 0; //I don't know
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        return 0; //I don't know
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        return 32767; // Depends on datatypes and sizes, at most 64 kbyte excluding blobs (but including blob ids)
    }

    @Override
    public int getMaxConnections() throws SQLException {
        return 0; //I don't know
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        return 31;
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        if (gdsHelper.compareToVersion(2, 0) < 0) {
            return 252; // See http://www.firebirdsql.org/en/firebird-technical-specifications/
        } else {
            return 0; // 1/4 of page size, maybe retrieve page size and use that?
        }
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        return 0; //No schemas
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        return getMaxObjectNameLength();
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        return 0; //No catalogs
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        if (gdsHelper.compareToVersion(1, 5) >= 0)
            return 65531;
        else
            return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        return false; // Blob sizes are not included in rowsize
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        if (gdsHelper.compareToVersion(3, 0) >= 0) {
            // 10 MB
            return 10 * 1024 * 1024;
        } else {
            // 64 KB
            return 64 * 1024;
        }
    }

    @Override
    public int getMaxStatements() throws SQLException {
        // Limited by max handles, but this includes other objects than statements
        return 0;
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        return getMaxObjectNameLength();
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        // TODO Check if there is a max
        return 0;
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        return getMaxObjectNameLength();
    }

    //----------------------------------------------------------------------

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        switch (level) {
        case Connection.TRANSACTION_READ_COMMITTED:
        case Connection.TRANSACTION_REPEATABLE_READ:
        case Connection.TRANSACTION_SERIALIZABLE:
            return true;
        default:
            return false;
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Although Firebird supports both DML and DDL in transactions, it is not possible to use objects in the same
     * transaction that defines them. For example, it is not possible to insert into a table in the same transaction
     * that created it.
     * </p>
     */
    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return false;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Read the note on {@link #supportsDataDefinitionAndDataManipulationTransactions()}.
     * </p>
     */
    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return false;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Read the note on {@link #supportsDataDefinitionAndDataManipulationTransactions()}.
     * </p>
     */
    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return false;
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        return GetProcedures.create(getDbMetadataMediator()).getProcedures(procedureNamePattern);
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
            String columnNamePattern) throws SQLException {
        return GetProcedureColumns.create(getDbMetadataMediator())
                .getProcedureColumns(procedureNamePattern, columnNamePattern);
    }

    public static final String TABLE = "TABLE";
    public static final String SYSTEM_TABLE = "SYSTEM TABLE";
    public static final String VIEW = "VIEW";
    public static final String GLOBAL_TEMPORARY = "GLOBAL TEMPORARY";

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        return createGetTablesInstance().getTables(tableNamePattern, types);
    }

    private GetTables createGetTablesInstance() {
        return GetTables.create(getDbMetadataMediator());
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        final RowDescriptor rowDescriptor = new RowDescriptorBuilder(1, datatypeCoder)
                .at(0).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_CAT", "TABLECATALOGS").addField()
                .toRowDescriptor();

        return new FBResultSet(rowDescriptor, Collections.emptyList());
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return createGetTablesInstance().getTableTypes();
    }

    @Override
    public String[] getTableTypeNames() throws SQLException {
        return createGetTablesInstance().getTableTypeNames();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Jaybird defines these additional columns:
     * <ol start="25">
     * <li><b>JB_IS_IDENTITY</b> String  =&gt; Indicates whether this column is an identity column (<b>NOTE: Jaybird
     * specific column; retrieve by name!</b>).
     * There is a subtle difference with the meaning of {@code IS_AUTOINCREMENT}. This column indicates if the column
     * is a true identity column.
     * <ul>
     * <li> YES           --- if the column is an identity column</li>
     * <li> NO            --- if the column is not an identity column</li>
     * </ul>
     * </li>
     * <li><b>JB_IDENTITY_TYPE</b> String  =&gt; Type of identity column (<b>NOTE: Jaybird specific column; retrieve by
     * name!</b>)
     * <ul>
     * <li> ALWAYS        --- for a GENERATED ALWAYS AS IDENTITY column (not yet supported in Firebird 3!)</li>
     * <li> BY DEFAULT    --- for a GENERATED BY DEFAULT AS IDENTITY column</li>
     * <li> null          --- if the column is not an identity type (or the identity type is unknown)</li>
     * </ul>
     * </li>
     * </ol>
     * </p>
     */
    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        return GetColumns.create(getDbMetadataMediator()).getColumns(tableNamePattern, columnNamePattern);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Jaybird defines an additional column:
     * <ol start="9">
     * <li><b>JB_GRANTEE_TYPE</b> String  =&gt; Object type of {@code GRANTEE} (<b>NOTE: Jaybird specific column;
     * retrieve by name!</b>).</li>
     * </ol>
     * </p>
     * <p>
     * Privileges granted to the table as a whole are reported for each individual column.
     * </p>
     * <p>
     * <b>NOTE:</b> This implementation returns <b>all</b> privileges, not just applicable to the current user. It is
     * unclear if this complies with the JDBC requirements. This may change in the future to only return only privileges
     * applicable to the current user, user {@code PUBLIC} and &mdash; maybe &mdash; active roles. This note does not
     * apply to the {@code OOREMOTE} sub-protocol, which already restricts privileges to the current user and
     * {@code PUBLIC}.
     * </p>
     */
    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
            throws SQLException {
        return GetColumnPrivileges.create(getDbMetadataMediator()).getColumnPrivileges(table, columnNamePattern);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Jaybird defines an additional column:
     * <ol start="8">
     * <li><b>JB_GRANTEE_TYPE</b> String  =&gt; Object type of {@code GRANTEE} (<b>NOTE: Jaybird specific column;
     * retrieve by name!</b>).</li>
     * </ol>
     * </p>
     * <p>
     * <b>NOTE:</b> This implementation returns <b>all</b> privileges, not just applicable to the current user. It is
     * unclear if this complies with the JDBC requirements. This may change in the future to only return only privileges
     * applicable to the current user, user {@code PUBLIC} and &mdash; maybe &mdash; active roles. This note does not
     * apply to the {@code OOREMOTE} sub-protocol, which already restricts privileges to the current user and
     * {@code PUBLIC}.
     * </p>
     */
    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return GetTablePrivileges.create(getDbMetadataMediator()).getTablePrivileges(tableNamePattern);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Jaybird considers the primary key (scoped as {@code bestRowSession} as the best identifier for all scopes.
     * Pseudo column {@code RDB$DB_KEY} (scoped as {@code bestRowTransaction} is considered the second-best alternative
     * for scopes {@code bestRowTemporary} and {@code bestRowTransaction} if {@code table} has no primary key.
     * </p>
     * <p>
     * Jaybird currently considers {@code RDB$DB_KEY} to be {@link DatabaseMetaData#bestRowTransaction} even if the
     * dbkey_scope is set to 1 (session). This may change in the future. See also {@link #getRowIdLifetime()}.
     * </p>
     */
    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        return GetBestRowIdentifier.create(getDbMetadataMediator())
                .getBestRowIdentifier(catalog, schema, table, scope, nullable);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Jaybird considers both {@code RDB$DB_KEY} and {@code RDB$RECORD_VERSION} (Firebird 3 and higher) as version
     * columns.
     * </p>
     * <p>
     * Jaybird only returns pseudo-column as version columns, so 'last updated' columns updated by a trigger,
     * calculated columns, or other forms of change tracking are not reported by this method.
     * </p>
     */
    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return GetVersionColumns.create(getDbMetadataMediator())
                .getVersionColumns(catalog, schema, table);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return GetPrimaryKeys.create(getDbMetadataMediator()).getPrimaryKeys(table);
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return GetImportedKeys.create(getDbMetadataMediator()).getImportedKeys(table);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return GetExportedKeys.create(getDbMetadataMediator()).getExportedKeys(table);
    }

    @Override
    public ResultSet getCrossReference(
            String primaryCatalog, String primarySchema, String primaryTable,
            String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        return GetCrossReference.create(getDbMetadataMediator()).getCrossReference(primaryTable, foreignTable);
    }

    /**
     * Function to convert integer values to encoded byte arrays for integers.
     *
     * @param value
     *         integer value to convert
     * @return encoded byte array representing the value
     */
    private static byte[] createInt(int value) {
        return datatypeCoder.encodeInt(value);
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return GetTypeInfo.create(getDbMetadataMediator()).getTypeInfo();
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
            throws SQLException {
        return GetIndexInfo.create(getDbMetadataMediator()).getIndexInfo(table, unique, approximate);
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        // TODO Return false for TYPE_SCROLL_SENSITVE as we only support it by downgrading to INSENSITIVE?
        switch (type){
            case ResultSet.TYPE_FORWARD_ONLY:
            case ResultSet.TYPE_SCROLL_INSENSITIVE :
            case ResultSet.TYPE_SCROLL_SENSITIVE :
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        // TODO Return false for TYPE_SCROLL_SENSITVE as we only support it by downgrading to INSENSITIVE?
        switch(type) {
            case ResultSet.TYPE_FORWARD_ONLY:
            case ResultSet.TYPE_SCROLL_INSENSITIVE :
            case ResultSet.TYPE_SCROLL_SENSITIVE :
                return concurrency == ResultSet.CONCUR_READ_ONLY ||
                    concurrency == ResultSet.CONCUR_UPDATABLE;
            default:
                return false;
        }
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        // TODO Return false for TYPE_SCROLL_SENSITVE as we only support it by downgrading to INSENSITIVE?
        return ResultSet.TYPE_SCROLL_INSENSITIVE == type ||
                ResultSet.TYPE_SCROLL_SENSITIVE == type;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        // TODO Return false for TYPE_SCROLL_SENSITVE as we only support it by downgrading to INSENSITIVE?
        return ResultSet.TYPE_SCROLL_INSENSITIVE == type ||
                ResultSet.TYPE_SCROLL_SENSITIVE == type;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        // TODO Return false for TYPE_SCROLL_SENSITVE as we only support it by downgrading to INSENSITIVE?
        return ResultSet.TYPE_SCROLL_INSENSITIVE == type ||
                ResultSet.TYPE_SCROLL_SENSITIVE == type;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        // TODO Currently not correct when scrollableCursor=SERVER (and not a holdable cursor);
        //  change to return true when behaviour of EMULATED is the same
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        // TODO Currently not correct when scrollableCursor=SERVER (and not a holdable cursor);
        //  change to return true when behaviour of EMULATED is the same
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        // TODO Currently not correct when scrollableCursor=SERVER (and not a holdable cursor);
        //  change to return true when behaviour of EMULATED is the same
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * UDTs are not supported by Firebird. This method will always return an empty ResultSet.
     * </p>
     */
    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        final RowDescriptor rowDescriptor = new RowDescriptorBuilder(7, datatypeCoder)
                .at(0).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TYPE_CAT", "UDT").addField()
                .at(1).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TYPE_SCHEM", "UDT").addField()
                .at(2).simple(SQL_VARYING, 31, "TYPE_NAME", "UDT").addField()
                .at(3).simple(SQL_VARYING, 31, "CLASS_NAME", "UDT").addField()
                .at(4).simple(SQL_LONG, 0, "DATA_TYPE", "UDT").addField()
                .at(5).simple(SQL_VARYING, 31, "REMARKS", "UDT").addField()
                .at(6).simple(SQL_SHORT, 0, "BASE_TYPE", "UDT").addField()
                .toRowDescriptor();

        return new FBResultSet(rowDescriptor, Collections.emptyList());
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    /**
     * {@inheritDoc}
     * <p>
     * UDTs are not supported by Firebird. This method will always return an empty ResultSet.
     * </p>
     */
    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
            String attributeNamePattern) throws SQLException {
        final RowDescriptor rowDescriptor = new RowDescriptorBuilder(21, datatypeCoder)
                .at(0).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TYPE_CAT", "ATTRIBUTES").addField()
                .at(1).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TYPE_SCHEM", "ATTRIBUTES").addField()
                .at(2).simple(SQL_VARYING, 31, "TYPE_NAME", "ATTRIBUTES").addField()
                .at(3).simple(SQL_VARYING, 31, "ATTR_NAME", "ATTRIBUTES").addField()
                .at(4).simple(SQL_LONG, 0, "DATA_TYPE", "ATTRIBUTES").addField()
                .at(5).simple(SQL_VARYING, 31, "ATTR_TYPE_NAME", "ATTRIBUTES").addField()
                .at(6).simple(SQL_LONG, 0, "ATTR_SIZE", "ATTRIBUTES").addField()
                .at(7).simple(SQL_LONG, 0, "DECIMAL_DIGITS", "ATTRIBUTES").addField()
                .at(8).simple(SQL_LONG, 0, "NUM_PREC_RADIX", "ATTRIBUTES").addField()
                .at(9).simple(SQL_LONG, 0, "NULLABLE", "ATTRIBUTES").addField()
                .at(10).simple(SQL_VARYING, 80, "REMARKS", "ATTRIBUTES").addField()
                .at(11).simple(SQL_VARYING, 31, "ATTR_DEF", "ATTRIBUTES").addField()
                .at(12).simple(SQL_LONG, 0, "SQL_DATA_TYPE", "ATTRIBUTES").addField()
                .at(13).simple(SQL_LONG, 0, "SQL_DATETIME_SUB", "ATTRIBUTES").addField()
                .at(14).simple(SQL_LONG, 0, "CHAR_OCTET_LENGTH", "ATTRIBUTES").addField()
                .at(15).simple(SQL_SHORT, 0, "ORDINAL_POSITION", "ATTRIBUTES").addField()
                .at(16).simple(SQL_VARYING, 31, "IS_NULLABLE", "ATTRIBUTES").addField()
                .at(17).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "SCOPE_CATALOG", "ATTRIBUTES").addField()
                .at(18).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "SCOPE_SCHEMA", "ATTRIBUTES").addField()
                .at(19).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "SCOPE_TABLE", "ATTRIBUTES").addField()
                .at(20).simple(SQL_SHORT, 0, "SOURCE_DATA_TYPE", "ATTRIBUTES").addField()
                .toRowDescriptor();

        return new FBResultSet(rowDescriptor, Collections.emptyList());
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        return firebirdSupportInfo.supportsSavepoint();
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws SQLException {
        return firebirdSupportInfo.supportsInsertReturning()
                && connection.getGeneratedKeysSupport().supportsGetGeneratedKeys();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Supertypes are not supported by Firebird. This method will always return an empty ResultSet.
     * </p>
     */
    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        final RowDescriptor rowDescriptor = new RowDescriptorBuilder(6, datatypeCoder)
                .at(0).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TYPE_CAT", "SUPERTYPES").addField()
                .at(1).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TYPE_SCHEM", "SUPERTYPES").addField()
                .at(2).simple(SQL_VARYING, 31, "TYPE_NAME", "SUPERTYPES").addField()
                .at(3).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "SUPERTYPE_CAT", "SUPERTYPES").addField()
                .at(4).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "SUPERTYPE_SCHEM", "SUPERTYPES").addField()
                .at(5).simple(SQL_VARYING, 31, "SUPERTYPE_NAME", "SUPERTYPES").addField()
                .toRowDescriptor();

        return new FBResultSet(rowDescriptor, Collections.emptyList());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Supertables are not supported by Firebird. This method will always return an empty ResultSet.
     * </p>
     */
    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        final RowDescriptor rowDescriptor = new RowDescriptorBuilder(4, datatypeCoder)
                .at(0).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_CAT", "SUPERTABLES").addField()
                .at(1).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_SCHEM", "SUPERTABLES").addField()
                .at(2).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_NAME", "SUPERTABLES").addField()
                .at(3).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "SUPERTABLE_NAME", "SUPERTABLES").addField()
                .toRowDescriptor();

        return new FBResultSet(rowDescriptor, Collections.emptyList());
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT ||
                holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        // TODO Retrieve default holdable connection property
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return gdsHelper.getDatabaseProductMajorVersion();
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return gdsHelper.getDatabaseProductMinorVersion();
    }

    @Override
    public int getOdsMajorVersion() throws SQLException {
        return gdsHelper.getCurrentDatabase().getOdsMajor();
    }

    @Override
    public int getOdsMinorVersion() throws SQLException {
        return gdsHelper.getCurrentDatabase().getOdsMinor();
    }

    @Override
    public int getDatabaseDialect() throws SQLException {
        return gdsHelper.getCurrentDatabase().getDatabaseDialect();
    }

    @Override
    public int getConnectionDialect() throws SQLException {
        return gdsHelper.getCurrentDatabase().getConnectionDialect();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Firebird primarily uses SQL standard SQL states, but may occasionally use values from X/Open.
     * </p>
     */
    @Override
    public int getSQLStateType() throws SQLException {
        return DatabaseMetaData.sqlStateSQL;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return false;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The holdable result sets remain open, others are closed, but this happens before the statement is executed.
     * </p>
     */
    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        // the holdable result sets remain open, others are closed, but this
        // happens before the statement is executed
        return false;
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        // TODO Return context info?
        final RowDescriptor rowDescriptor = new RowDescriptorBuilder(4, datatypeCoder)
                .at(0).simple(SQL_VARYING, 31, "NAME", "CLIENTINFO").addField()
                .at(1).simple(SQL_LONG, 4, "MAX_LEN", "CLIENTINFO").addField()
                .at(2).simple(SQL_VARYING, 31, "DEFAULT", "CLIENTINFO").addField()
                .at(3).simple(SQL_VARYING, 31, "DESCRIPTION", "CLIENTINFO").addField()
                .toRowDescriptor();

        return new FBResultSet(rowDescriptor, Collections.emptyList());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * This method does not return columns of functions defined in packages.
     * </p>
     */
    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
            String columnNamePattern) throws SQLException {
        return GetFunctionColumns.create(getDbMetadataMediator())
                .getFunctionColumns(functionNamePattern, columnNamePattern);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Jaybird defines a number of additional columns. As these are not defined in JDBC, their position may change with
     * revisions of JDBC. We recommend to retrieve these columns by name. The following additional columns are
     * available:
     * <ol start="7">
     * <li><b>JB_FUNCTION_SOURCE</b> String  =&gt; The source of the function (for Firebird 3+ PSQL functions only)).</li>
     * <li><b>JB_FUNCTION_KIND</b> String =&gt; The kind of function, one of "UDF", "PSQL" (Firebird 3+) or
     * "UDR" (Firebird 3+)</li>
     * <li><b>JB_MODULE_NAME</b> String =&gt; Value of {@code RDB$MODULE_NAME} (is {@code null} for PSQL)</li>
     * <li><b>JB_ENTRYPOINT</b> String =&gt; Value of {@code RDB$ENTRYPOINT} (is {@code null} for PSQL)</li>
     * <li><b>JB_ENGINE_NAME</b> String =&gt; Value of {@code RDB$ENGINE_NAME} (is {@code null} for UDF and PSQL)</li>
     * </ol>
     * </p>
     * <p>
     * This method does not return functions defined in packages.
     * </p>
     */
    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        return GetFunctions.create(getDbMetadataMediator()).getFunctions(functionNamePattern);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        final RowDescriptor rowDescriptor = new RowDescriptorBuilder(2, datatypeCoder)
                .at(0).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_SCHEM", "TABLESCHEMAS").addField()
                .at(1).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_CATALOG", "TABLESCHEMAS").addField()
                .toRowDescriptor();

        return new FBResultSet(rowDescriptor, Collections.emptyList());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface != null && iface.isAssignableFrom(FBDatabaseMetaData.class);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (!isWrapperFor(iface))
            throw new SQLException("Unable to unwrap to class " + iface.getName());

        return iface.cast(this);
    }

    /**
     * Escapes the like wildcards and escape ({@code \_%} in the provided search string with a {@code \}.
     * <p>
     * Primary purpose is to escape object names with wildcards for use in metadata patterns for literal matches, but
     * it can also be used to escape for SQL {@code LIKE}.
     * </p>
     *
     * @param objectName
     *         Object name to escape.
     * @return Object name with wildcards escaped.
     */
    public static String escapeWildcards(String objectName) {
        return MetadataPattern.escapeWildcards(objectName);
    }

    //@formatter:off

    // Suitable for Firebird 2.5 and earlier
    private static final String GET_PSEUDO_COLUMNS_FRAGMENT_FB_25 =
            "select "
            + " RDB$RELATION_NAME, "
            + " RDB$DBKEY_LENGTH, "
            + " 'F' AS HAS_RECORD_VERSION, "
            + " '' AS RECORD_VERSION_NULLABLE " // unknown nullability (and doesn't matter, no RDB$RECORD_VERSION)
            + "from rdb$relations ";

    // Suitable for Firebird 3 and higher
    private static final String GET_PSEUDO_COLUMNS_FRAGMENT_FB_30 =
            "select "
            + " RDB$RELATION_NAME, "
            + " RDB$DBKEY_LENGTH, "
            + " RDB$DBKEY_LENGTH = 8 as HAS_RECORD_VERSION, "
            + " case "
            + "   when RDB$RELATION_TYPE in (0, 1, 4, 5) then 'NO' " // table, view, GTT preserve + delete: never null
            + "   when RDB$RELATION_TYPE in (2, 3) then 'YES' " // external + virtual: always null
            + "   else ''" // unknown or unsupported (by Jaybird) type: unknown nullability
            + " end as RECORD_VERSION_NULLABLE "
            + "from rdb$relations ";

    private static final String GET_PSEUDO_COLUMNS_END = " order by RDB$RELATION_NAME";

    //@formatter:on

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {

        final RowDescriptor rowDescriptor = new RowDescriptorBuilder(12, datatypeCoder)
                .at(0).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_CAT", "PSEUDOCOLUMNS").addField()
                .at(1).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_SCHEM", "PSEUDOCOLUMNS").addField()
                .at(2).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "TABLE_NAME", "PSEUDOCOLUMNS").addField()
                .at(3).simple(SQL_VARYING, OBJECT_NAME_LENGTH, "COLUMN_NAME", "PSEUDOCOLUMNS").addField()
                .at(4).simple(SQL_LONG, 0, "DATA_TYPE", "PSEUDOCOLUMNS").addField()
                .at(5).simple(SQL_LONG, 0, "COLUMN_SIZE", "PSEUDOCOLUMNS").addField()
                .at(6).simple(SQL_LONG, 0, "DECIMAL_DIGITS", "PSEUDOCOLUMNS").addField()
                .at(7).simple(SQL_LONG, 0, "NUM_PREC_RADIX", "PSEUDOCOLUMNS").addField()
                .at(8).simple(SQL_VARYING, 50, "COLUMN_USAGE", "PSEUDOCOLUMNS").addField()
                // Field in Firebird is actually a blob, using Integer.MAX_VALUE for length
                .at(9).simple(SQL_VARYING | 1, Integer.MAX_VALUE, "REMARKS", "PSEUDOCOLUMNS").addField()
                .at(10).simple(SQL_LONG, 0, "CHAR_OCTET_LENGTH", "PSEUDOCOLUMNS").addField()
                .at(11).simple(SQL_VARYING, 3, "IS_NULLABLE", "PSEUDOCOLUMNS").addField()
                .toRowDescriptor();

        if ("".equals(tableNamePattern) || "".equals(columnNamePattern)) {
            // Matching table and/or column not possible
            return new FBResultSet(rowDescriptor, Collections.emptyList());
        }

        final boolean supportsRecordVersion = firebirdSupportInfo.supportsRecordVersionPseudoColumn();
        final MetadataPatternMatcher matcher = MetadataPattern.compile(columnNamePattern).toMetadataPatternMatcher();
        final boolean retrieveDbKey = matcher.matches("RDB$DB_KEY");
        final boolean retrieveRecordVersion = supportsRecordVersion && matcher.matches("RDB$RECORD_VERSION");

        if (!(retrieveDbKey || retrieveRecordVersion)) {
            // No matching columns
            return new FBResultSet(rowDescriptor, Collections.emptyList());
        }

        Clause tableNameClause = new Clause("RDB$RELATION_NAME", tableNamePattern);
        String sql = (supportsRecordVersion ? GET_PSEUDO_COLUMNS_FRAGMENT_FB_30 : GET_PSEUDO_COLUMNS_FRAGMENT_FB_25);
        if (tableNameClause.hasCondition()) {
            sql += " where " + tableNameClause.getCondition(false);
        }
        sql += GET_PSEUDO_COLUMNS_END;
        List<String> params = tableNameClause.hasCondition()
                ? Collections.singletonList(tableNameClause.getValue())
                : Collections.emptyList();

        try (ResultSet rs = doQuery(sql, params)) {
            if (!rs.next()) {
                return new FBResultSet(rowDescriptor, Collections.emptyList());
            }

            byte[] dbKeyBytes = retrieveDbKey ? getBytes("RDB$DB_KEY") : null;
            byte[] dbKeyRemark = retrieveDbKey
                    ? getBytes("The RDB$DB_KEY column in a select list will be renamed by Firebird to DB_KEY in the "
                    + "result set (both as column name and label). Result set getters in Jaybird will map this, but "
                    + "in introspection of ResultSetMetaData, DB_KEY will be reported. Identification as a "
                    + "Types.ROWID will only work in a select list (ResultSetMetaData), not for parameters "
                    + "(ParameterMetaData), but Jaybird will allow setting a RowId value.")
                    : null;
            byte[] recordVersionBytes = retrieveRecordVersion ? getBytes("RDB$RECORD_VERSION") : null;
            byte[] noUsageRestrictions = getBytes(PseudoColumnUsage.NO_USAGE_RESTRICTIONS.name());

            List<RowValue> rows = new ArrayList<>();
            RowValueBuilder valueBuilder = new RowValueBuilder(rowDescriptor);
            do {
                byte[] tableNameBytes = getBytes(rs.getString("RDB$RELATION_NAME"));

                if (retrieveDbKey) {
                    int dbKeyLength = rs.getInt("RDB$DBKEY_LENGTH");
                    byte[] dbKeyLengthBytes = createInt(dbKeyLength);
                    valueBuilder
                            .at(2).set(tableNameBytes)
                            .at(3).set(dbKeyBytes)
                            .at(4).set(createInt(Types.ROWID))
                            .at(5).set(dbKeyLengthBytes)
                            .at(7).set(RADIX_TEN)
                            .at(8).set(noUsageRestrictions)
                            .at(9).set(dbKeyRemark)
                            .at(10).set(dbKeyLengthBytes)
                            .at(11).set(NO_BYTES);
                    rows.add(valueBuilder.toRowValue(true));
                }

                if (retrieveRecordVersion && rs.getBoolean("HAS_RECORD_VERSION")) {
                    valueBuilder
                            .at(2).set(tableNameBytes)
                            .at(3).set(recordVersionBytes)
                            .at(4).set(createInt(Types.BIGINT))
                            .at(5).set(BIGINT_PRECISION)
                            .at(6).set(INT_ZERO)
                            .at(7).set(RADIX_TEN)
                            .at(8).set(noUsageRestrictions)
                            .at(11).set(getBytes(rs.getString("RECORD_VERSION_NULLABLE")));
                    rows.add(valueBuilder.toRowValue(true));
                }
            } while (rs.next());

            return new FBResultSet(rowDescriptor, rows);
        }
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        // TODO Double check if this is correct
        return false;
    }

    @Override
    public String getProcedureSourceCode(String procedureName) throws SQLException {
        String sResult = null;
        String sql = "Select RDB$PROCEDURE_SOURCE From RDB$PROCEDURES Where "
                + "RDB$PROCEDURE_NAME = ?";
        List<String> params = new ArrayList<>();
        params.add(procedureName);
        try (ResultSet rs = doQuery(sql, params)) {
            if (rs.next()) sResult = rs.getString(1);
        }

        return sResult;
    }

    @Override
    public String getTriggerSourceCode(String triggerName) throws SQLException {
        String sResult = null;
        String sql = "Select RDB$TRIGGER_SOURCE From RDB$TRIGGERS Where RDB$TRIGGER_NAME = ?";
        List<String> params = new ArrayList<>();
        params.add(triggerName);
        try (ResultSet rs = doQuery(sql, params)) {
            if (rs.next()) sResult = rs.getString(1);
        }

        return sResult;
    }

    @Override
    public String getViewSourceCode(String viewName) throws SQLException {
        String sResult = null;
        String sql = "Select RDB$VIEW_SOURCE From RDB$RELATIONS Where RDB$RELATION_NAME = ?";
        List<String> params = new ArrayList<>();
        params.add(viewName);
        try (ResultSet rs = doQuery(sql, params)) {
            if (rs.next()) sResult = rs.getString(1);
        }

        return sResult;
    }

    protected static byte[] getBytes(String value) {
        return value != null ? value.getBytes(StandardCharsets.UTF_8) : null;
    }

    private FBPreparedStatement getStatement(String sql, boolean standalone) throws SQLException {
        try (LockCloseable ignored = connection.withLock()) {
            if (!standalone) {
                // Check cache
                FBPreparedStatement cachedStatement = statements.get(sql);

                if (cachedStatement != null) {
                    if (cachedStatement.isClosed()) {
                        //noinspection resource
                        statements.remove(sql);
                    } else {
                        return cachedStatement;
                    }
                }
            }

            InternalTransactionCoordinator.MetaDataTransactionCoordinator metaDataTransactionCoordinator =
                    new InternalTransactionCoordinator.MetaDataTransactionCoordinator(connection.txCoordinator);

            FBPreparedStatement newStatement = new FBPreparedStatement(gdsHelper, sql,
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT,
                    metaDataTransactionCoordinator, metaDataTransactionCoordinator, true, standalone, false);

            if (!standalone) {
                statements.put(sql, newStatement);
            }

            return newStatement;
        }
    }

    /**
     * Execute an sql query with a given set of parameters.
     *
     * @param sql
     *         The sql statement to be used for the query
     * @param params
     *         The parameters to be used in the query
     * @throws SQLException
     *         if a database access error occurs
     */
    protected ResultSet doQuery(String sql, List<String> params) throws SQLException {
        return doQuery(sql, params, false);
    }

    /**
     * Execute an sql query with a given set of parameters.
     *
     * @param sql
     *         The sql statement to be used for the query
     * @param params
     *         The parameters to be used in the query
     * @param standalone
     *         The query to be executed is a standalone query (should not be cached and be closed asap)
     * @throws SQLException
     *         if a database access error occurs
     */
    protected ResultSet doQuery(String sql, List<String> params, boolean standalone) throws SQLException {
        FBPreparedStatement s = getStatement(sql, standalone);

        for (int i = 0; i < params.size(); i++) {
            s.setString(i + 1, params.get(i));
        }

        return s.executeMetaDataQuery();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Minimum lifetime supported by Firebird is transaction-scope, and this can be changed to session-scope with
     * {@code isc_dpb_dbkey_scope} set to {@code 1} (eg connection property {@code dbkey_scope=1}). This implementation,
     * however, will always report {@link RowIdLifetime#ROWID_VALID_TRANSACTION}.
     * </p>
     */
    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        return RowIdLifetime.ROWID_VALID_TRANSACTION;
    }

    static final int JDBC_MAJOR_VERSION = 4;
    static final int JDBC_MINOR_VERSION;
    static {
        int tempVersion;
        try {
            String javaImplementation = getSystemPropertyPrivileged("java.specification.version");
            if (javaImplementation == null) {
                // Assume common case: JDBC 4.3
                tempVersion = 3;
            } else {
                int javaVersionMajor;
                try {
                    javaVersionMajor = (int) Double.parseDouble(javaImplementation);
                } catch (NumberFormatException e) {
                    javaVersionMajor = 1;
                }
                if (javaVersionMajor >= 9) {
                    // JDK 9 or higher: JDBC 4.3
                    tempVersion = 3;
                } else {
                    // JDK 1.8 or lower: JDBC 4.2
                    tempVersion = 2;
                }
            }
        } catch (RuntimeException ex) {
            // default to 3 (JDBC 4.3) when privileged call fails
            tempVersion = 3;
        }
        JDBC_MINOR_VERSION = tempVersion;
    }

    @Override
    public int getJDBCMajorVersion() {
        return JDBC_MAJOR_VERSION;
    }

    @Override
    public int getJDBCMinorVersion() {
        return JDBC_MINOR_VERSION;
    }

    @SuppressWarnings("SameParameterValue")
    private static String getSystemPropertyPrivileged(final String propertyName) {
        return AccessController.doPrivileged((PrivilegedAction<String>) () -> System.getProperty(propertyName));
    }

    private static class LruPreparedStatementCache extends LinkedHashMap<String, FBPreparedStatement> {
        private static final long serialVersionUID = -6600678461169652270L;
        private final int maxCapacity;

        private LruPreparedStatementCache(int maxCapacity) {
            super(16, 0.75f, true);
            this.maxCapacity = maxCapacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FBPreparedStatement> eldest) {
            if (size() <= maxCapacity) {
                return false;
            }
            try {
                FBPreparedStatement statement = eldest.getValue();
                statement.close();
            } catch (Exception e) {
                log.debug("Closing eldest cached metadata statement yielded an exception; ignored", e);
            }
            return true;
        }
    }

    protected DbMetadataMediator getDbMetadataMediator() {
        return new DbMetadataMediatorImpl();
    }

    private class DbMetadataMediatorImpl extends DbMetadataMediator {

        @Override
        protected FirebirdSupportInfo getFirebirdSupportInfo() {
            return firebirdSupportInfo;
        }

        @Override
        protected ResultSet performMetaDataQuery(MetadataQuery metadataQuery) throws SQLException {
            return doQuery(metadataQuery.getQueryText(), metadataQuery.getParameters(), metadataQuery.isStandalone());
        }

        @Override
        protected FBDatabaseMetaData getMetaData() {
            return FBDatabaseMetaData.this;
        }
    }
}
