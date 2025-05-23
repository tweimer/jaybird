// SPDX-FileCopyrightText: Copyright 2009 Thomas Steinmaurer
// SPDX-FileCopyrightText: Copyright 2011-2022 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later OR BSD-3-Clause
package org.firebirdsql.management;

import java.sql.SQLException;

/**
 * Implements the incremental backup and restore functionality of NBackup
 * via the Firebird Services API.
 *
 * @author Thomas Steinmaurer
 * @author Mark Rotteveel
 */
public interface NBackupManager extends ServiceManager {

    /**
     * Sets the location of the backup file.
     * <p>
     * Warning: this method behaves identical to {@link #addBackupFile(String)}.
     * </p>
     *
     * @param backupFile
     *         the location of the backup file.
     */
    void setBackupFile(String backupFile);

    /**
     * Add additional backup files.
     * <p>
     * Specifying multiple backup files is only valid for restore, for backup only the first file is used.
     * </p>
     * <p>
     * Use {@link #clearBackupFiles()} to clear earlier backup files.
     * </p>
     *
     * @param backupFile
     *         the location of the backup file.
     */
    void addBackupFile(String backupFile);

    /**
     * Clear the information about backup files. This method undoes all
     * parameters set in the {@link #addBackupFile(String)} method.
     */
    void clearBackupFiles();

    /**
     * Set the path to the database. This method is used both for backup and
     * restore operation.
     *
     * @param path
     *         path to the database file.
     *         <p>
     *         In case of backup, value specifies the path of the existing database on the server that will be
     *         backed up.
     *         </p>
     *         <p>
     *         In case of restore, value specifies the path of the database where the backup will be restored to.
     *         </p>
     */
    void setDatabase(String path);

    /**
     * Perform the backup operation.
     *
     * @throws SQLException
     *         if a database error occurs during the backup
     */
    void backupDatabase() throws SQLException;

    /**
     * Perform the restore operation.
     * <p>
     * Set {@link #setPreserveSequence(boolean)} to preserve the original database GUID and replication sequence.
     * </p>
     *
     * @throws SQLException
     *         if a database error occurs during the restore
     */
    void restoreDatabase() throws SQLException;

    /**
     * Perform the nbackup fixup operation.
     * <p>
     * A fixup will switch a locked database to 'normal' state without merging the delta, so this is a potentially
     * destructive action. The normal use-case of this option is to unlock a copy of a database file where the source
     * database file was locked with {@code nbackup -L} or {@code ALTER DATABASE BEGIN BACKUP}.
     * </p>
     * <p>
     * Set {@link #setPreserveSequence(boolean)} to preserve the original database GUID and replication sequence.
     * </p>
     *
     * @throws SQLException
     *         if a database error occurs during the fixup
     * @since 5
     */
    void fixupDatabase() throws SQLException;

    /**
     * Sets the backup level (0 = full, 1..n = incremental)
     *
     * @param level
     *         backup level (e.g. 0 = full backup, 1 = level 1 incremental backup based on level 0 backup
     */
    void setBackupLevel(int level);

    /**
     * Sets the backup GUID (Firebird 4 and higher only).
     * <p>
     * The backup GUID is the GUID of a previous backup of the (source) database. This is used by Firebird to backup
     * the pages modified since that backup.
     * </p>
     * <p>
     * This setting is mutually exclusive with {@link #setBackupLevel(int)}, but this is only checked server-side.
     * </p>
     *
     * @param guid
     *         A GUID string of a previous backup, enclosed in braces.
     * @since 4.0.4
     */
    void setBackupGuid(String guid);

    /**
     * Sets the option no database triggers when connecting at backup or in-place restore.
     *
     * @param noDBTriggers
     *         {@code true} disable db triggers during backup or in-place restore.
     */
    void setNoDBTriggers(boolean noDBTriggers);

    /**
     * Enables in-place restore.
     *
     * @param inPlaceRestore
     *         {@code true} to enable in-place restore
     * @since 4.0.4
     */
    void setInPlaceRestore(boolean inPlaceRestore);

    /**
     * Enables preserve sequence (for fixup or restore).
     * <p>
     * This preserves the existing GUID and replication sequence of the original database (they are reset otherwise).
     * </p>
     *
     * @param preserveSequence
     *         {@code true} to enable preserve sequence
     * @since 5
     */
    void setPreserveSequence(boolean preserveSequence);

    /**
     * Enables clean history on backup.
     * <p>
     * The backup will fail if {@link #setKeepDays(int)} or {@link #setKeepRows(int)} has not been called.
     * </p>
     *
     * @param cleanHistory
     *         {@code true} to enable clean history
     * @since 4.0.7
     */
    void setCleanHistory(boolean cleanHistory);

    /**
     * Sets the number of days of backup history to keep.
     * <p>
     * Server-side, this option is mutually exclusive with {@link #setKeepRows(int)}, this is not enforced by the Java
     * code.
     * </p>
     * <p>
     * This option only has effect when {@code setCleanHistory(true)} has been called.
     * </p>
     *
     * @param days
     *         number of days to keep history when cleaning, or {@code -1} to clear current value
     * @see #setCleanHistory(boolean)
     * @see #setKeepRows(int)
     * @since 4.0.7
     */
    void setKeepDays(int days);

    /**
     * Sets the number of rows of backup history to keep (this includes the row created by the backup).
     * <p>
     * Server-side, this option is mutually exclusive with {@link #setKeepDays(int)}, this is not enforced by the Java
     * code.
     * </p>
     * <p>
     * This option only has effect when {@code setCleanHistory(true)} has been called.
     * </p>
     *
     * @param rows
     *         number of rows to keep history when cleaning, or {@code -1} to clear current value
     * @see #setCleanHistory(boolean)
     * @see #setKeepDays(int)
     * @since 4.0.7
     */
    void setKeepRows(int rows);
    
}
