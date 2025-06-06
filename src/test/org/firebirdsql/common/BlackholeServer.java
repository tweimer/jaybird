// SPDX-FileCopyrightText: Copyright 2013-2024 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import static java.lang.System.Logger.Level.ERROR;

/**
 * Simple socket server that consumes everything sent to it, but never responds; for testing (connect) timeouts.
 * <p>
 * Assumption of this implementation is that there will only be one
 * client at a time!
 * </p>
 */
public final class BlackholeServer implements Runnable {
    
    private volatile boolean active = true;
    private ServerSocket server;
    public static final int LIVENESS_TIMEOUT = 500;

    /**
     * Constructs the ServerSocket with an ephemeral port, this port can be retrieved using {@link #getPort()}.
     *
     * @throws IOException If an I/O error occurs when opening the socket.
     */
    public BlackholeServer() throws IOException {
        this(0);
    }

    /**
     * Constructs the ServerSocket with the specified port.
     *
     * @throws IOException If an I/O error occurs when opening the socket.
     */
    public BlackholeServer(int port) throws IOException {
        server = new ServerSocket(port, 1);
        server.setSoTimeout(LIVENESS_TIMEOUT);
    }

    /**
     * Stops the server after (at max) 2x the LIVENESS_TIMEOUT
     */
    public void stop() {
        active = false;
    }

    /**
     * Port number
     * @return The portnumber if connected, or -1 if not connected
     */
    public int getPort() {
        return server != null ? server.getLocalPort() : -1;
    }

    @Override
    public void run() {
        try (var ignored = server) {
            while (active) {
                try (Socket socket = server.accept()) {
                    readFromSocket(socket);
                } catch (SocketTimeoutException e) {
                    // Expected timeout for liveness of checking active
                }
            }
        } catch (Exception e) {
            System.getLogger(getClass().getName()).log(ERROR, "BlackHoleServer terminated with exception", e);
        } finally {
            server = null;
        }
    }

    private void readFromSocket(Socket socket) {
        try (var ignored = socket) {
            socket.setSoTimeout(LIVENESS_TIMEOUT);
            InputStream in = socket.getInputStream();
            while (active) {
                try {
                    if (in.read() == -1) return;
                } catch (SocketTimeoutException e) {
                    // Expected timeout for liveness of checking active
                } catch (IOException ioe) {
                    // Other errors: end read
                    return;
                }
            }
        } catch (IOException e) {
            System.getLogger(getClass().getName()).log(ERROR, "readFromSocket terminated with exception", e);
        }
    }
}