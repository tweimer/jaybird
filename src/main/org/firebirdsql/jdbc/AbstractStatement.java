 /*
 * Firebird Open Source J2ee connector - jdbc driver
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
 * can be obtained from a CVS history command.
 *
 * All rights reserved.
 */

package org.firebirdsql.jdbc;


import java.sql.*;
import java.util.*;

import org.firebirdsql.gds.GDSException;
import org.firebirdsql.gds.isc_stmt_handle;

/**
 * <P>The object used for executing a static SQL statement
 * and obtaining the results produced by it.
 *
 * <P>Only one <code>ResultSet</code> object  per <code>Statement</code> object
 * can be open at any point in
 * time. Therefore, if the reading of one <code>ResultSet</code> object is interleaved
 * with the reading of another, each must have been generated by
 * different <code>Statement</code> objects. All statement <code>execute</code>
 * methods implicitly close a statment's current <code>ResultSet</code> object
 * if an open one exists.
 *
 * @see Connection#createStatement
 * @see ResultSet
 * 
 * @author <a href="mailto:d_jencks@users.sourceforge.net">David Jencks</a>
 */
public abstract class AbstractStatement implements FirebirdStatement, Synchronizable {
    
    private final Object statementSynchronizationObject = new Object();

    protected AbstractConnection c;

    protected isc_stmt_handle fixedStmt;
    
    //The normally retrieved resultset. (no autocommit, not a cached rs).
    private FBResultSet currentRs;

    private boolean closed;
    private boolean escapedProcessing = true;

	 protected SQLWarning firstWarning = null;

	 // If the last executedStatement returns ResultSet or UpdateCount
	protected boolean isResultSet;
    protected boolean hasMoreResults;
    //Holds a result set from an execute call using autocommit.
    //This is a cached result set and is used to allow a call to getResultSet()
    private ResultSet currentCachedResultSet;

    protected int maxRows = 0;	 
    protected int fetchSize = 0;
    private int maxFieldSize = 0;
    private int queryTimeout = 0;
    private String cursorName;

    private int rsConcurrency;
    private int rsType;
    
    private FBObjectListener.ResultSetListener resultSetListener = new RSListener();
    
    /**
     * Listener for the result sets.
     */
    private class RSListener implements FBObjectListener.ResultSetListener {
        
        /**
         * Notify that result set was closed. This method cleans the result
         * set reference, so that call to {@link #close()} method will not cause
         * exception.
         * 
         * @param rs result set that was closed.
         */
        public void resultSetClosed(ResultSet rs) {
            if (currentRs == rs)
                currentRs = null;
            else
            if (currentCachedResultSet == rs)
                currentCachedResultSet = null;
        }
    }

    protected AbstractStatement(AbstractConnection c, int rsType, int rsConcurrency) {
        this.c = c;
        this.rsConcurrency = rsConcurrency;
        this.rsType = rsType;
        
        closed = false;
    }
    
    String getCursorName() {
        return cursorName;
    }
    
    
    public boolean isValid() {
        return fixedStmt != null && fixedStmt.isValid();
    }
    
    /**
     * Get synchronization object for this statement object.
     * 
     * @return object that will be used for synchronization.
     * 
     * @throws SQLException if something went wrong.
     */
    public Object getSynchronizationObject() throws SQLException {
        synchronized(c) {
            if (c.getAutoCommit())
                return c;
            else
                return this;
        }
    }
    
    protected void finalize() throws Throwable {
        if (!closed)
            close();
    }
    
    /**
     * Executes an SQL statement that returns a single <code>ResultSet</code> object.
     *
     * @param sql typically this is a static SQL <code>SELECT</code> statement
     * @return a <code>ResultSet</code> object that contains the data produced by the
     * given query; never <code>null</code>
     * @exception SQLException if a database access error occurs
     */
    public ResultSet executeQuery(String sql) throws  SQLException {
        if (closed)
            throw new FBSQLException("Statement is closed");
            
        Object syncObject = getSynchronizationObject();
            
        synchronized(syncObject) {
            try {
                c.ensureInTransaction();
                if (!internalExecute(sql)) {
                    throw new FBSQLException(
                        "Query did not return a result set.",
                        FBSQLException.SQL_STATE_NO_RESULT_SET);
                }
                if (c.willEndTransaction()) {
                    ResultSet rs = getCachedResultSet(false);
                    //autocommits.
                    return rs;
                } else { // end of if ()
                    return getResultSet();
                } // end of else
            } catch (GDSException ge) {
                throw new FBSQLException(ge);
            } finally { // end of try-catch
                c.checkEndTransaction();
            } // end of finally
        }
    }


    /**
     * Executes an SQL <code>INSERT</code>, <code>UPDATE</code> or
     * <code>DELETE</code> statement. In addition,
     * SQL statements that return nothing, such as SQL DDL statements,
     * can be executed.
     *
     * @param sql an SQL <code>INSERT</code>, <code>UPDATE</code> or
     * <code>DELETE</code> statement or an SQL statement that returns nothing
     * @return either the row count for <code>INSERT</code>, <code>UPDATE</code>
     * or <code>DELETE</code> statements, or 0 for SQL statements that return nothing
     * @exception SQLException if a database access error occurs
     */
    public int executeUpdate(String sql) throws  SQLException {
        if(closed)
            throw new FBSQLException("Statement is closed");
            
        Object syncObject = getSynchronizationObject();
            
        synchronized(syncObject) {
            try {
                c.ensureInTransaction();
                if (internalExecute(sql)) {
                    throw new FBSQLException("Update statement returned results.");
                }
                return getUpdateCount();
            } catch (GDSException ge) {
                throw new FBSQLException(ge);
            } finally { // end of try-catch
                c.checkEndTransaction();
            } // end of finally
        }
    }

    /**
     * Releases this <code>Statement</code> object's database
     * and JDBC resources immediately instead of waiting for
     * this to happen when it is automatically closed.
     * It is generally good practice to release resources as soon as
     * you are finished with them to avoid tying up database
     * resources.
     * <P><B>Note:</B> A <code>Statement</code> object is automatically closed when it is
     * garbage collected. When a <code>Statement</code> object is closed, its current
     * <code>ResultSet</code> object, if one exists, is also closed.
     *
     * @exception SQLException if a database access error occurs
     */
    public void close() throws  SQLException {
        if (closed)
            throw new FBSQLException("This statement is already closed.");
            
        Object syncObject = getSynchronizationObject();
        
        synchronized(syncObject) {
            try {
                if (fixedStmt != null) {
                    try {
                        try {
                            if (currentRs != null)
                                currentRs.close();

                            if (currentCachedResultSet != null)
                                currentCachedResultSet.close();

                        } finally {
                            currentRs = null;
                            currentCachedResultSet = null;

                            //may need ensureTransaction?
                            c.closeStatement(fixedStmt, true);
                        }
                    } catch (GDSException ge) {
                        throw new FBSQLException(ge);
                    } finally {
                        fixedStmt = null;
                        closed = true;
                    }
                } else
                    closed = true;
            } finally {
                c.notifyStatementClosed(this);
            }
        }
    }
    
    /**
     * Check if this statement was closed. This is quick workaround to avoid
     * additional {@link #close()} in our cleanup code.
     * 
     * @return <code>true</code> if this statement was already closed.
     */
    boolean isClosed() {
        return closed;
    }


    //----------------------------------------------------------------------

    /**
     * Returns the maximum number of bytes allowed
     * for any column value.
     * This limit is the maximum number of bytes that can be
     * returned for any column value.
     * The limit applies only to <code>BINARY</code>,
     * <code>VARBINARY</code>, <code>LONGVARBINARY</code>, <code>CHAR</code>, <code>VARCHAR</code>, and <code>LONGVARCHAR</code>
     * columns.  If the limit is exceeded, the excess data is silently
     * discarded.
     *
     * @return the current max column size limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public int getMaxFieldSize() throws  SQLException {
        return maxFieldSize;
    }


    /**
     * Sets the limit for the maximum number of bytes in a column to
     * the given number of bytes.  This is the maximum number of bytes
     * that can be returned for any column value.  This limit applies
     * only to <code>BINARY</code>, <code>VARBINARY</code>,
     * <code>LONGVARBINARY</code>, <code>CHAR</code>, <code>VARCHAR</code>, and
     * <code>LONGVARCHAR</code> fields.  If the limit is exceeded, the excess data
     * is silently discarded. For maximum portability, use values
     * greater than 256.
     *
     * @param max the new max column size limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public void setMaxFieldSize(int max) throws  SQLException {
        if (max<0)
            throw new FBSQLException("Can't set max field size negative",
                    FBSQLException.SQL_STATE_INVALID_ARG_VALUE);
        else
            maxFieldSize = max;
    }


    /**
     * Retrieves the maximum number of rows that a
     * <code>ResultSet</code> object can contain.  If the limit is exceeded, the excess
     * rows are silently dropped.
     *
     * @return the current max row limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public int getMaxRows() throws  SQLException {
        return maxRows;
    }


    /**
     * Sets the limit for the maximum number of rows that any
     * <code>ResultSet</code> object can contain to the given number.
     * If the limit is exceeded, the excess
     * rows are silently dropped.
     *
     * @param max the new max rows limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public void setMaxRows(int max) throws  SQLException {
        if (max<0)
            throw new FBSQLException("Max rows can't be less than 0",
                    FBSQLException.SQL_STATE_INVALID_ARG_VALUE);
        else
            maxRows = max;
    }


    /**
     * Sets escape processing on or off.
     * If escape scanning is on (the default), the driver will do
     * escape substitution before sending the SQL to the database.
     *
     * Note: Since prepared statements have usually been parsed prior
     * to making this call, disabling escape processing for prepared
     * statements will have no effect.
     *
     * @param enable <code>true</code> to enable; <code>false</code> to disable
     * @exception SQLException if a database access error occurs
     */
    public void setEscapeProcessing(boolean enable) throws  SQLException {
        escapedProcessing = enable;
    }


    /**
     * Retrieves the number of seconds the driver will
     * wait for a <code>Statement</code> object to execute. If the limit is exceeded, a
     * <code>SQLException</code> is thrown.
     *
     * @return the current query timeout limit in seconds; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public int getQueryTimeout() throws  SQLException {
        return queryTimeout;
    }


    /**
     * Sets the number of seconds the driver will
     * wait for a <code>Statement</code> object to execute to the given number of seconds.
     * If the limit is exceeded, an <code>SQLException</code> is thrown.
     *
     * @param seconds the new query timeout limit in seconds; zero means
     * unlimited
     * @exception SQLException if a database access error occurs
     */
    public void setQueryTimeout(int seconds) throws  SQLException {
        if (seconds<0)
            throw new FBSQLException("Can't set query timeout negative",
                    FBSQLException.SQL_STATE_INVALID_ARG_VALUE);
        else
            queryTimeout = seconds;
    }


    /**
     * Cancels this <code>Statement</code> object if both the DBMS and
     * driver support aborting an SQL statement.
     * This method can be used by one thread to cancel a statement that
     * is being executed by another thread.
     *
     * @exception SQLException if a database access error occurs
     */
    public void cancel() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Retrieves the first warning reported by calls on this <code>Statement</code> object.
     * Subsequent <code>Statement</code> object warnings will be chained to this
     * <code>SQLWarning</code> object.
     *
     * <p>The warning chain is automatically cleared each time
     * a statement is (re)executed.
     *
     * <P><B>Note:</B> If you are processing a <code>ResultSet</code> object, any
     * warnings associated with reads on that <code>ResultSet</code> object
     * will be chained on it.
     *
     * @return the first <code>SQLWarning</code> object or <code>null</code>
     * @exception SQLException if a database access error occurs
     */
    public SQLWarning getWarnings() throws  SQLException {
        return firstWarning;
    }


    /**
     * Clears all the warnings reported on this <code>Statement</code>
     * object. After a call to this method,
     * the method <code>getWarnings</code> will return
     * <code>null</code> until a new warning is reported for this
     * <code>Statement</code> object.
     *
     * @exception SQLException if a database access error occurs
     */
    public void clearWarnings() throws  SQLException {
        firstWarning = null;
    }


    /**
     * Defines the SQL cursor name that will be used by
     * subsequent <code>Statement</code> object <code>execute</code> methods.
     * This name can then be
     * used in SQL positioned update/delete statements to identify the
     * current row in the <code>ResultSet</code> object generated by this statement.  If
     * the database doesn't support positioned update/delete, this
     * method is a noop.  To insure that a cursor has the proper isolation
     * level to support updates, the cursor's <code>SELECT</code> statement should be
     * of the form 'select for update ...'. If the 'for update' phrase is
     * omitted, positioned updates may fail.
     *
     * <P><B>Note:</B> By definition, positioned update/delete
     * execution must be done by a different <code>Statement</code> object than the one
     * which generated the <code>ResultSet</code> object being used for positioning. Also,
     * cursor names must be unique within a connection.
     *
     * @param name the new cursor name, which must be unique within
     *             a connection
     * @exception SQLException if a database access error occurs
     */
    public void setCursorName(String name) throws  SQLException {
        this.cursorName = name;
    }

    boolean isUpdatableCursor() {
        return cursorName != null;
    }

    //----------------------- Multiple Results --------------------------

    /**
     * Executes an SQL statement that may return multiple results.
     * Under some (uncommon) situations a single SQL statement may return
     * multiple result sets and/or update counts.  Normally you can ignore
     * this unless you are (1) executing a stored procedure that you know may
     * return multiple results or (2) you are dynamically executing an
     * unknown SQL string.  The  methods <code>execute</code>,
     * <code>getMoreResults</code>, <code>getResultSet</code>,
     * and <code>getUpdateCount</code> let you navigate through multiple results.
     *
     * The <code>execute</code> method executes an SQL statement and indicates the
     * form of the first result.  You can then use the methods
     * <code>getResultSet</code> or <code>getUpdateCount</code>
     * to retrieve the result, and <code>getMoreResults</code> to
     * move to any subsequent result(s).
     *
     * @param sql any SQL statement
     * @return <code>true</code> if the next result is a <code>ResultSet</code> object;
     * <code>false</code> if it is an update count or there are no more results
     * @exception SQLException if a database access error occurs
     * @see #getResultSet
     * @see #getUpdateCount
     * @see #getMoreResults
     */
    public boolean execute(String sql) throws SQLException {
        if (closed)
            throw new FBSQLException("Statement is closed");
        
        Object syncObject = getSynchronizationObject();
            
        synchronized(syncObject) {
            try {
                c.ensureInTransaction();
                boolean hasResultSet = internalExecute(sql);
                if (hasResultSet && c.willEndTransaction()) {
                    getCachedResultSet(false);
                } // end of if ()
                return hasResultSet;
            } catch (GDSException ge) {
                throw new FBSQLException(ge);
            } finally { // end of try-catch
                c.checkEndTransaction();
            } // end of finally
        }
    }

    /**
     *  Returns the current result as a <code>ResultSet</code> object.
     *  This method should be called only once per result.
     * Calling this method twice with autocommit on and used will probably
     * throw an inappropriate or uninformative exception.
     *
     * @return the current result as a <code>ResultSet</code> object;
     * <code>null</code> if the result is an update count or there are no more results
     * @exception SQLException if a database access error occurs
     * @see #execute
     */
    public ResultSet getResultSet() throws  SQLException {
        try {
            if (cursorName != null)
                c.setCursorName(fixedStmt, cursorName);
        } catch(GDSException ex) {
            throw new FBSQLException(ex);
        }
        
        
        if (currentRs != null) {
            throw new FBSQLException("Only one resultset at a time/statement.");
        }
        if (fixedStmt == null) {
            throw new FBSQLException("No statement was executed.");
        }
        if (currentCachedResultSet != null)
        {
            ResultSet rs = currentCachedResultSet;
            currentCachedResultSet = null;
            return rs;
        } // end of if ()
        else {
            if (isResultSet){
                currentRs = new FBResultSet(
                        c, this, fixedStmt, resultSetListener, rsType, rsConcurrency);
                return currentRs;
            }
            else
                return null;
        } // end of else
    }

    ResultSet getCachedResultSet(boolean trimStrings) throws SQLException {
        if (currentRs != null) {
            throw new FBSQLException("Only one resultset at a time/statement.");
        }
        if (fixedStmt == null) {
            throw new FBSQLException("No statement was executed.");
        }
        currentCachedResultSet = new FBResultSet(c, this, fixedStmt, trimStrings, resultSetListener);
        return currentCachedResultSet;
    }

	public boolean hasOpenResultSet() {
		return currentRs != null;
	}

    /**
     *  Returns the current result as an update count;
     *  if the result is a <code>ResultSet</code> object or there are no more results, -1
     *  is returned. This method should be called only once per result.
     *
     * @return the current result as an update count; -1 if the current result is a
     * <code>ResultSet</code> object or there are no more results
     * @exception SQLException if a database access error occurs
     * @see #execute
     */
    public int getUpdateCount() throws  SQLException {
        if (isResultSet || !hasMoreResults)
            return -1;
        else {
            try {
                c.getSqlCounts(fixedStmt);
                int insCount = fixedStmt.getInsertCount();
                int updCount = fixedStmt.getUpdateCount();
                int delCount = fixedStmt.getDeleteCount();
                int resCount = ((updCount>delCount) ? updCount:delCount);
                resCount = ((resCount>insCount) ? resCount:insCount);
                return resCount;
            }
            catch (GDSException ge) {
                throw new FBSQLException(ge);
            } finally {
                hasMoreResults = false;
            }
        }
    }

    private static final int INSERTED_ROWS_COUNT = 1;
    private static final int UPDATED_ROWS_COUNT = 2;
    private static final int DELETED_ROWS_COUNT = 3;
    
	private int getChangedRowsCount(int type) throws SQLException {
        if (isResultSet || !hasMoreResults)
            return -1;
        else {
        	try {
        		c.getSqlCounts(fixedStmt);
                switch(type) {
                    case INSERTED_ROWS_COUNT :
                    	return fixedStmt.getInsertCount();
                    case UPDATED_ROWS_COUNT :
                        return fixedStmt.getUpdateCount();
                    case DELETED_ROWS_COUNT :
                        return fixedStmt.getDeleteCount();
                    default :
                        throw new IllegalArgumentException(
                            "Specified type is unknown.");
                }
            } catch(GDSException ex) {
            	throw new FBSQLException(ex);
            }
        }
	}
    
    public int getDeletedRowsCount() throws SQLException {
    	return getChangedRowsCount(DELETED_ROWS_COUNT);
    }

	public int getInsertedRowsCount() throws SQLException {
		return getChangedRowsCount(INSERTED_ROWS_COUNT);
	}

	public int getUpdatedRowsCount() throws SQLException {
		return getChangedRowsCount(UPDATED_ROWS_COUNT);
	}

    /**
     * Moves to a <code>Statement</code> object's next result.  It returns
     * <code>true</code> if this result is a <code>ResultSet</code> object.
     * This method also implicitly closes any current <code>ResultSet</code>
     * object obtained with the method <code>getResultSet</code>.
     *
     * <P>There are no more results when the following is true:
     * <PRE>
     *      <code>(!getMoreResults() && (getUpdateCount() == -1)</code>
     * </PRE>
     *
     * @return <code>true</code> if the next result is a <code>ResultSet</code> object;
     * <code>false</code> if it is an update count or there are no more results
     * @exception SQLException if a database access error occurs
     * @see #execute
     */
    public boolean getMoreResults() throws  SQLException {
        hasMoreResults = false;
        
        if (currentRs != null) {
            try {
                currentRs.close();
            } finally {
                currentRs = null;
            }
        } else
        if (currentCachedResultSet != null) {
            try {
                currentCachedResultSet.close();
            } finally {
                currentCachedResultSet = null;
            }
        }
        
        return hasMoreResults;
    }


    //--------------------------JDBC 2.0-----------------------------


    /**
     * Gives the driver a hint as to the direction in which
     * the rows in a result set
     * will be processed. The hint applies only to result sets created
     * using this <code>Statement</code> object.  The default value is
     * <code>ResultSet.FETCH_FORWARD</code>.
     * <p>Note that this method sets the default fetch direction for
     * result sets generated by this <code>Statement</code> object.
     * Each result set has its own methods for getting and setting
     * its own fetch direction.
     * @param direction the initial direction for processing rows
     * @exception SQLException if a database access error occurs
     * or the given direction
     * is not one of <code>ResultSet.FETCH_FORWARD</code>,
     * <code>ResultSet.FETCH_REVERSE</code>, or <code>ResultSet.FETCH_UNKNOWN</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void setFetchDirection(int direction) throws  SQLException {
        if (direction != ResultSet.FETCH_FORWARD)
            throw new FBDriverNotCapableException();
    }


    /**
     * Retrieves the direction for fetching rows from
     * database tables that is the default for result sets
     * generated from this <code>Statement</code> object.
     * If this <code>Statement</code> object has not set
     * a fetch direction by calling the method <code>setFetchDirection</code>,
     * the return value is implementation-specific.
     *
     * @return the default fetch direction for result sets generated
     *          from this <code>Statement</code> object
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getFetchDirection() throws  SQLException {
       return ResultSet.FETCH_FORWARD;
    }


    /**
     * Gives the JDBC driver a hint as to the number of rows that should
     * be fetched from the database when more rows are needed.  The number
     * of rows specified affects only result sets created using this
     * statement. If the value specified is zero, then the hint is ignored.
     * The default value is zero.
     *
     * @param rows the number of rows to fetch
     * @exception SQLException if a database access error occurs, or the
     * condition 0 <= rows <= this.getMaxRows() is not satisfied.
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void setFetchSize(int rows) throws  SQLException {
        if (rows < 0)
            throw new FBSQLException("Can't set negative fetch size",
                    FBSQLException.SQL_STATE_INVALID_ARG_VALUE);
        else if (maxRows > 0 && rows > maxRows)
            throw new FBSQLException("Can't set fetch size > maxRows",
                    FBSQLException.SQL_STATE_INVALID_ARG_VALUE);
        else
            fetchSize = rows;
    }


    /**
     * Retrieves the number of result set rows that is the default
     * fetch size for result sets
     * generated from this <code>Statement</code> object.
     * If this <code>Statement</code> object has not set
     * a fetch size by calling the method <code>setFetchSize</code>,
     * the return value is implementation-specific.
     * @return the default fetch size for result sets generated
     *          from this <code>Statement</code> object
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getFetchSize() throws  SQLException {
        return fetchSize;
    }


    /**
     * Retrieves the result set concurrency for <code>ResultSet</code> objects
     * generated by this <code>Statement</code> object.
     *
     * @return either <code>ResultSet.CONCUR_READ_ONLY</code> or
     * <code>ResultSet.CONCUR_UPDATABLE</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getResultSetConcurrency() throws  SQLException {
        return rsConcurrency;
    }


    /**
     * Retrieves the result set type for <code>ResultSet</code> objects
     * generated by this <code>Statement</code> object.
     *
     * @return one of <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     * <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     * <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getResultSetType()  throws  SQLException {
        return rsType;
    }

    private LinkedList batchList = new LinkedList();

    /**
     * Adds an SQL command to the current batch of commmands for this
     * <code>Statement</code> object. This method is optional.
     *
     * @param sql typically this is a static SQL <code>INSERT</code> or
     * <code>UPDATE</code> statement
     * @exception SQLException if a database access error occurs, or the
     * driver does not support batch statements
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void addBatch( String sql ) throws  SQLException {
        batchList.add(sql);
    }


    /**
     * Makes the set of commands in the current batch empty.
     * This method is optional.
     *
     * @exception SQLException if a database access error occurs or the
     * driver does not support batch statements
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void clearBatch() throws  SQLException {
        batchList.clear();
    }


    /**
     * Submits a batch of commands to the database for execution and
     * if all commands execute successfully, returns an array of update counts.
     * The <code>int</code> elements of the array that is returned are ordered
     * to correspond to the commands in the batch, which are ordered
     * according to the order in which they were added to the batch.
     * The elements in the array returned by the method <code>executeBatch</code>
     * may be one of the following:
     * <OL>
     * <LI>A number greater than or equal to zero -- indicates that the
     * command was processed successfully and is an update count giving the
     * number of rows in the database that were affected by the command's
     * execution
     * <LI>A value of <code>-2</code> -- indicates that the command was
     * processed successfully but that the number of rows affected is
     * unknown
     * <P>
     * If one of the commands in a batch update fails to execute properly,
     * this method throws a <code>BatchUpdateException</code>, and a JDBC
     * driver may or may not continue to process the remaining commands in
     * the batch.  However, the driver's behavior must be consistent with a
     * particular DBMS, either always continuing to process commands or never
     * continuing to process commands.  If the driver continues processing
     * after a failure, the array returned by the method
     * <code>BatchUpdateException.getUpdateCounts</code>
     * will contain as many elements as there are commands in the batch, and
     * at least one of the elements will be the following:
     * <P>
     * <LI>A value of <code>-3</code> -- indicates that the command failed
     * to execute successfully and occurs only if a driver continues to
     * process commands after a command fails
     * </OL>
     * <P>
     * A driver is not required to implement this method.
     * The possible implementations and return values have been modified in
     * the Java 2 SDK, Standard Edition, version 1.3 to
     * accommodate the option of continuing to proccess commands in a batch
     * update after a <code>BatchUpdateException</code> obejct has been thrown.
     *
     * @return an array of update counts containing one element for each
     * command in the batch.  The elements of the array are ordered according
     * to the order in which commands were added to the batch.
     * @exception SQLException if a database access error occurs or the
     * driver does not support batch statements. Throws {@link java.sql.BatchUpdateException}
     * (a subclass of <code>SQLException</code>) if one of the commands sent to the
     * database fails to execute properly or attempts to return a result set.
     * @since 1.3
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int[] executeBatch() throws  SQLException {
        if (closed)
            throw new FBSQLException("Statement is closed");
        
        if (c.getAutoCommit())
            c.addWarning(new SQLWarning("Batch updates should be run " +
                    "with auto-commit disabled.", "1000"));
        
        Object syncObject = getSynchronizationObject();
        LinkedList responses = new LinkedList();
        
        boolean commit = false;
        synchronized(syncObject) {
            try {
                c.ensureInTransaction();
                
                Iterator iter = batchList.iterator();
                while(iter.hasNext()) {
                	String sql = (String)iter.next();
                    try {
                    	boolean hasResultSet = internalExecute(sql);
                        
                        if (hasResultSet)
                        	throw new BatchUpdateException(toArray(responses));
                        else
                            responses.add(new Integer(getUpdateCount()));
                    } catch (GDSException ge) {
                        
                        throw new BatchUpdateException(
                                ge.getMessage(), 
                                FBSQLException.SQL_STATE_GENERAL_ERROR,
                                ge.getFbErrorCode(),
                                toArray(responses));
                    }
                }
               
                commit = true;
                
                return toArray(responses);
                
            } finally {
                c.checkEndTransaction(commit);
            }
        }
    }
    
    /**
     * Convert collection of {@link Integer} elements into array of int.
     * 
     * @param list collection of integer elements.
     * 
     * @return array of int.
     */
    protected int[] toArray(Collection list) {
        int[] result = new int[list.size()];
        int counter = 0;
        Iterator iter = list.iterator();
        while(iter.hasNext()) {
        	result[counter++] = ((Integer)iter.next()).intValue();
        }
        return result;
    }


    /**
     * Returns the <code>Connection</code> object that produced this 
     * <code>Statement</code> object.
     * 
     * @return the connection that produced this statement
     */
    public Connection getConnection() {
        return c;
    }

    //package level

    void closeResultSet() throws SQLException {
        if (currentRs != null)
            currentRs.close();
    }
    
    void releaseResultSet() throws SQLException {
        currentCachedResultSet = null;
        if (currentRs != null) {
            try {
                c.closeStatement(fixedStmt, false);
            }
            catch (GDSException ge) {
                throw new FBSQLException(ge);
            }
            currentRs = null;
        }
    }

    public void forgetResultSet() { //yuck should be package
        currentRs = null;
        if (fixedStmt != null) {
            fixedStmt.clearRows();
        }
    }
    
    /**
     * This method checks if supplied statement is executing procedure or
     * it is generic statement. This check is needed to handle correctly 
     * parameters that are returned from non-selectable procedures.
     * 
     * @param sql SQL statement to check
     * 
     * @return <code>true</code> if supplied statement is EXECUTE PROCEDURE
     * type of statement.
     * 
     * @throws SQLException if translating statement into native code failed.
     */
    protected boolean isExecuteProcedureStatement(String sql) throws SQLException {
        
        String trimmedSql = c.nativeSQL(sql).trim();
        
        if (trimmedSql.startsWith("EXECUTE"))
            return true;
        else
            return false;
        
    }

    protected boolean internalExecute(String sql)
        throws GDSException, SQLException
    {
        if (closed)
            throw new FBSQLException("Statement is already closed.");

        closeResultSet();
        prepareFixedStatement(sql, false);
        c.executeStatement(fixedStmt, isExecuteProcedureStatement(sql));
        isResultSet = (fixedStmt.getOutSqlda().sqld > 0);
        hasMoreResults = true;
        return (fixedStmt.getOutSqlda().sqld > 0);
    }

    protected void prepareFixedStatement(String sql, boolean describeBind)
        throws GDSException, SQLException
    {
        if (fixedStmt == null) {
            fixedStmt = c.getAllocatedStatement();
        }
        c.prepareSQL(
            fixedStmt, 
            escapedProcessing ? c.nativeSQL(sql) : sql, 
            describeBind);
    }

    protected void addWarning(SQLWarning warning){
        if (firstWarning == null)
            firstWarning = warning;
        else{
            SQLWarning lastWarning = firstWarning;
            while (lastWarning.getNextWarning() != null){
                lastWarning = lastWarning.getNextWarning();
            }
            lastWarning.setNextWarning(warning);
        }
    }

}
