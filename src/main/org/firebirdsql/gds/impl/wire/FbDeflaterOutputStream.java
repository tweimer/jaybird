// SPDX-FileCopyrightText: Copyright 2019-2023 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds.impl.wire;

import org.firebirdsql.gds.JaybirdSystemProperties;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static java.lang.System.Logger.Level.TRACE;

/**
 * {@code DeflaterOutputStream} with some modifications to simplify usage for Jaybird.
 *
 * @author Mark Rotteveel
 * @since 4.0
 */
class FbDeflaterOutputStream extends DeflaterOutputStream implements EncryptedStreamSupport {

    private static final System.Logger log = System.getLogger(FbDeflaterOutputStream.class.getName());
    private static final int BUF_SIZE = Math.max(512, JaybirdSystemProperties.getWireDeflateBufferSize(8192));

    private boolean encrypted;

    /**
     * Creates a {@code DeflaterOutputStream} with {@code syncFlush = true}.
     *
     * @param out
     *         Output stream
     */
    public FbDeflaterOutputStream(OutputStream out) {
        super(out, new Deflater(), BUF_SIZE, true);
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            buf = new byte[1];
            if (log.isLoggable(TRACE)) {
                log.log(TRACE, "FbDeflaterOutputStream: Uncompressed bytes: {0} to compressed bytes: {1}",
                        def.getBytesRead(), def.getBytesWritten());
            }
            def.end();
        }
    }

    @Override
    public void setCipher(Cipher cipher) throws IOException {
        if (encrypted) {
            throw new IOException("Output stream already encrypted");
        }
        out = new CipherOutputStream(out, cipher);
        encrypted = true;
    }
}
