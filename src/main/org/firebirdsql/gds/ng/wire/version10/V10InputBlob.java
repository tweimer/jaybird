// SPDX-FileCopyrightText: Copyright 2013-2025 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.ng.wire.version10;

import org.firebirdsql.gds.BlobParameterBuffer;
import org.firebirdsql.gds.JaybirdErrorCodes;
import org.firebirdsql.gds.VaxEncoding;
import org.firebirdsql.gds.impl.wire.XdrInputStream;
import org.firebirdsql.gds.impl.wire.XdrOutputStream;
import org.firebirdsql.gds.ng.FbExceptionBuilder;
import org.firebirdsql.gds.ng.listeners.DatabaseListener;
import org.firebirdsql.gds.ng.wire.*;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLWarning;

import static org.firebirdsql.gds.JaybirdErrorCodes.jb_blobGetSegmentNegative;
import static org.firebirdsql.gds.VaxEncoding.iscVaxInteger2;
import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.*;

/**
 * Input {@link org.firebirdsql.gds.ng.wire.FbWireBlob} implementation for the version 10 wire protocol.
 *
 * @author Mark Rotteveel
 * @since 3
 */
public class V10InputBlob extends AbstractFbWireInputBlob implements FbWireBlob, DatabaseListener {
    
    private static final int STATE_END_OF_BLOB = 2;

    // TODO V10OutputBlob and V10InputBlob share some common behavior and information (eg in open()), find a way to unify this

    public V10InputBlob(FbWireDatabase database, FbWireTransaction transaction, BlobParameterBuffer blobParameterBuffer,
            long blobId) throws SQLException {
        super(database, transaction, blobParameterBuffer, blobId);
    }

    // TODO Need blob specific warning callback?

    @Override
    public void open() throws SQLException {
        try (var ignored = withLock()) {
            checkDatabaseAttached();
            checkTransactionActive();
            checkBlobClosed();
            clearDeferredException();

            sendOpen(BlobOpenOperation.INPUT_BLOB, true);
            receiveOpenResponse();
            resetEof();
            throwAndClearDeferredException();
            // TODO Request information on the blob?
        } catch (SQLException e) {
            errorOccurred(e);
            throw e;
        }
    }

    @Override
    public byte[] getSegment(final int sizeRequested) throws SQLException {
        try {
            if (sizeRequested <= 0) {
                throw FbExceptionBuilder.forException(jb_blobGetSegmentNegative)
                        .messageParameter(sizeRequested)
                        .toSQLException();
            }
            final GenericResponse response;
            try (var ignored = withLock()) {
                checkDatabaseAttached();
                checkTransactionActive();
                checkBlobOpen();

                requestGetSegment(sizeRequested);
                response = receiveGetSegmentResponse();
                throwAndClearDeferredException();
            }
            return extractGetSegmentResponse(response.data());
        } catch (SQLException e) {
            errorOccurred(e);
            throw e;
        }
    }

    private void requestGetSegment(int sizeRequested) throws SQLException {
        try {
            sendGetSegment(segmentRequestSize(sizeRequested));
            getXdrOut().flush();
        } catch (IOException e) {
            throw FbExceptionBuilder.ioWriteError(e);
        }
    }

    private GenericResponse receiveGetSegmentResponse() throws SQLException {
        try {
            GenericResponse response = getDatabase().readGenericResponse(null);
            if (response.objectHandle() == STATE_END_OF_BLOB) {
                // TODO what if I seek on a stream blob?
                setEof();
            }
            return response;
        } catch (IOException e) {
            throw FbExceptionBuilder.ioReadError(e);
        }
    }

    private static byte[] extractGetSegmentResponse(byte[] responseBuffer) {
        if (responseBuffer.length == 0) return responseBuffer;

        final byte[] data = new byte[getTotalSegmentSize(responseBuffer)];
        int responsePos = 0;
        int dataPos = 0;
        while (responsePos < responseBuffer.length) {
            int segmentLength = iscVaxInteger2(responseBuffer, responsePos);
            responsePos += 2;
            System.arraycopy(responseBuffer, responsePos, data, dataPos, segmentLength);
            responsePos += segmentLength;
            dataPos += segmentLength;
        }
        return data;
    }

    /**
     * Calculates the total size of all segments in {@code segmentBuffer}.
     *
     * @param segmentBuffer
     *         segment buffer (contains 1 or more segments of [2-byte length][length bytes]...).
     * @return total length of segments
     */
    private static int getTotalSegmentSize(byte[] segmentBuffer) {
        int count = 0;
        int pos = 0;
        while (pos < segmentBuffer.length) {
            int segmentLength = VaxEncoding.iscVaxInteger2(segmentBuffer, pos);
            pos += 2 + segmentLength;
            count += segmentLength;
        }
        return count;
    }

    private int segmentRequestSize(int size) {
        // The request size is the total buffer, but segments are prefixed with 2 bytes for the size. It is possible
        // a single response contains multiple segments, but we don't take that into account for the size calculation.
        return Math.min(Math.max(size, size + 2), getMaximumSegmentSize());
    }

    /**
     * Sends the {@code op_get_segment} request for {@code len}, without flushing.
     *
     * @param len
     *         requested length (should not exceed {@link #getMaximumSegmentSize()}, but this is not enforced)
     * @throws SQLException
     *         for errors obtaining the XDR output stream
     * @throws IOException
     *         for errors writing data to the output stream
     */
    protected void sendGetSegment(int len) throws SQLException, IOException {
        XdrOutputStream xdrOut = getXdrOut();
        xdrOut.writeInt(op_get_segment); // p_operation
        xdrOut.writeInt(getHandle()); // p_sgmt_blob
        xdrOut.writeInt(len); // p_sgmt_length
        // length of segment send buffer (always 0 in get)
        xdrOut.writeInt(0); // p_sgmt_segment
    }

    @Override
    protected int get(final byte[] b, final int off, final int len, final int minLen) throws SQLException {
        try (var ignored = withLock()) {
            validateBufferLength(b, off, len);
            if (len == 0) return 0;
            if (minLen <= 0 || minLen > len ) {
                throw FbExceptionBuilder.forNonTransientException(JaybirdErrorCodes.jb_invalidStringLength)
                        .messageParameter("minLen", len, minLen)
                        .toSQLException();
            }
            checkDatabaseAttached();
            checkTransactionActive();
            checkBlobOpen();

            int count = 0;
            while (count < minLen && !isEof()) {
                int sizeRequested = len - count;
                requestGetSegment(sizeRequested);
                count += extractGetSegmentResponse(b, off + count, sizeRequested);
            }
            throwAndClearDeferredException();
            return count;
        } catch (SQLException e) {
            errorOccurred(e);
            throw e;
        }
    }

    /**
     * Extracts the get segment response to byte array {@code b}.
     *
     * @param b
     *         destination byte array
     * @param off
     *         offset to start
     * @param len
     *         maximum number of bytes (actual number depends on the response)
     * @return actual number of bytes read
     * @throws SQLException
     *         for database access errors, or wrong segment lengths, or more bytes are returned than {@code len}
     */
    private int extractGetSegmentResponse(byte[] b, int off, int len) throws SQLException {
        try {
            final FbWireOperations wireOps = getDatabase().getWireOperations();
            requireOpResponse(wireOps);
            final XdrInputStream xdrIn = getXdrIn();
            final int objHandle = xdrIn.readInt();
            xdrIn.skipNBytes(8); // blob-id (unused)

            final int bufferLength = xdrIn.readInt();
            int count = 0;
            if (bufferLength > 0) {
                int bufferRemaining = bufferLength;
                while (bufferRemaining > 2) {
                    int segmentLength = VaxEncoding.decodeVaxInteger2WithoutLength(xdrIn);
                    bufferRemaining -= 2;
                    if (segmentLength > bufferRemaining) {
                        throw new IOException("Inconsistent segment buffer: segment length %d, remaining buffer was %d"
                                .formatted(segmentLength, bufferRemaining));
                    } else if (segmentLength > len - count) {
                        throw new IOException("Returned segment length %d exceeded remaining size %d"
                                .formatted(segmentLength, len - count));
                    }
                    xdrIn.readFully(b, off + count, segmentLength);
                    bufferRemaining -= segmentLength;
                    count += segmentLength;
                }

                // Safety measure: read remaining (shouldn't happen in practice)
                xdrIn.skipNBytes(bufferRemaining);
                // Skip buffer padding
                xdrIn.skipPadding(bufferLength);
            }

            SQLException exception = wireOps.readStatusVector();
            if (exception != null && !(exception instanceof SQLWarning)) {
                // NOTE: SQLWarning is unlikely for this operation, so we don't do anything to report it
                throw exception;
            }

            if (objHandle == STATE_END_OF_BLOB) {
                setEof();
            }

            return count;
        } catch (IOException e) {
            throw FbExceptionBuilder.ioReadError(e);
        }
    }

    private static void requireOpResponse(FbWireOperations wireOps) throws SQLException, IOException {
        final int opCode = wireOps.readNextOperation();
        if (opCode != op_response) {
            wireOps.readOperationResponse(opCode, null);
            throw new SQLException("Unexpected response to op_get_segment: " + opCode);
        }
    }

    @Override
    public void seek(int offset, SeekMode seekMode) throws SQLException {
        try (var ignored = withLock()) {
            checkDatabaseAttached();
            checkTransactionActive();
            checkBlobOpen();

            sendSeek(offset, seekMode);
            receiveSeekResponse();
            throwAndClearDeferredException();
        } catch (SQLException e) {
            errorOccurred(e);
            throw e;
        }
    }

    private void sendSeek(int offset, SeekMode seekMode) throws SQLException {
        try {
            XdrOutputStream xdrOut = getXdrOut();
            xdrOut.writeInt(op_seek_blob); // p_operation
            xdrOut.writeInt(getHandle()); // p_seek_blob
            xdrOut.writeInt(seekMode.getSeekModeId()); // p_seek_mode
            xdrOut.writeInt(offset); // p_seek_offset
            xdrOut.flush();
        } catch (IOException e) {
            throw FbExceptionBuilder.ioWriteError(e);
        }
    }

    private void receiveSeekResponse() throws SQLException {
        try {
            getDatabase().readResponse(null);
            // object handle in response is the current position in the blob (see .NET provider source)
        } catch (IOException e) {
            throw FbExceptionBuilder.ioReadError(e);
        }
    }
}
