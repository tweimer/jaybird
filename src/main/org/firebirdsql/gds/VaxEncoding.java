// SPDX-FileCopyrightText: Copyright 2014-2023 Mark Rotteveel
// SPDX-License-Identifier: LGPL-2.1-or-later
package org.firebirdsql.gds;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Helper methods for decoding Vax style (little endian) integers as used by Firebird from byte arrays.
 *
 * @author Mark Rotteveel
 * @since 3.0
 */
public final class VaxEncoding {

    private VaxEncoding() {
        // No instances
    }

    /**
     * Reads Vax style integers from {@code buf}, starting at {@code off} and reading for {@code len} bytes.
     * <p>
     * This method is useful for lengths up to 4 bytes (i.e. normal Java integers ({@code int}). For larger lengths it
     * will return {@code 0}. Use {@link #iscVaxLong(byte[], int, int)} for reading values with length up to 8 bytes.
     * For decoding 2 byte integers, use {@link #iscVaxInteger2(byte[], int)} for optimal performance.
     * </p>
     *
     * @param buf
     *         byte array from which the integer is to be retrieved
     * @param off
     *         offset from which to start retrieving byte values
     * @param len
     *         number of bytes to read
     * @return integer value retrieved from the bytes
     * @see #iscVaxLong(byte[], int, int)
     * @see #iscVaxInteger2(byte[], int)
     */
    public static int iscVaxInteger(byte[] buf, int off, int len) {
        if (len > 4) return 0;
        int value = 0;
        int shift = 0;

        while (--len >= 0) {
            value += (buf[off++] & 0xFF) << shift;
            shift += 8;
        }
        return value;
    }

    /**
     * Encodes an integer using Vax encoding into an output stream, length prefix is included.
     *
     * @param out
     *         output stream to write
     * @param val
     *         value to encode
     */
    public static void encodeVaxInteger(OutputStream out, int val) throws IOException {
        byte[] buf = new byte[5];
        encodeVaxInteger(buf, 0, val);
        out.write(buf, 0, 5);
    }

    /**
     * Encodes an integer using Vax encoding into {@code buf}, length prefix is included.
     *
     * @param buf
     *         byte array of sufficient size
     * @param off
     *         offset to start writing
     * @param val
     *         value to encode
     */
    public static void encodeVaxInteger(byte[] buf, int off, int val) {
        buf[off++] = 4;
        encodeVaxIntegerWithoutLength(buf, off, val);
    }

    /**
     * Encodes an integer using Vax encoding into an output stream, without length prefix.
     *
     * @param out
     *         output stream to write
     * @param val
     *         value to encode
     */
    public static void encodeVaxIntegerWithoutLength(OutputStream out, int val) throws IOException {
        byte[] buf = new byte[4];
        encodeVaxIntegerWithoutLength(buf, 0, val);
        out.write(buf, 0, 4);
    }

    /**
     * Encodes an integer using Vax encoding into {@code buf}, without length prefix.
     *
     * @param buf
     *         byte array of sufficient size
     * @param off
     *         offset to start writing
     * @param val
     *         value to encode
     */
    public static void encodeVaxIntegerWithoutLength(byte[] buf, int off, int val) {
        buf[off++] = (byte) val;
        buf[off++] = (byte) (val >>> 8);
        buf[off++] = (byte) (val >>> 16);
        buf[off] = (byte) (val >>> 24);
    }

    /**
     * Reads Vax style integers (longs) from {@code buf}, starting at {@code off} and reading for {@code len} bytes.
     * <p>
     * This method is useful for lengths up to 8 bytes (i.e. normal Java longs ({@code long}). For larger lengths it
     * will return {@code 0}.
     * </p>
     *
     * @param buf
     *         byte array from which the long is to be retrieved
     * @param off
     *         offset from which to start retrieving byte values
     * @param len
     *         number of bytes to read
     * @return long value retrieved from the bytes
     * @see #iscVaxInteger(byte[], int, int)
     * @see #iscVaxInteger2(byte[], int)
     */
    public static long iscVaxLong(byte[] buf, int off, int len) {
        if (len > 8) return 0;
        long value = 0;
        int shift = 0;

        while (--len >= 0) {
            value += (buf[off++] & 0xFFL) << shift;
            shift += 8;
        }
        return value;
    }

    /**
     * Encodes a long using Vax encoding into an output stream, length prefix is included.
     *
     * @param out
     *         output stream to write
     * @param val
     *         value to encode
     */
    public static void encodeVaxLong(OutputStream out, long val) throws IOException {
        byte[] buf = new byte[9];
        encodeVaxLong(buf, 0, val);
        out.write(buf, 0, 9);
    }

    /**
     * Encodes a long using Vax encoding into {@code buf}, length prefix is included.
     *
     * @param buf
     *         byte array of sufficient size
     * @param off
     *         offset to start writing
     * @param val
     *         value to encode
     */
    public static void encodeVaxLong(byte[] buf, int off, long val) {
        buf[off++] = 8;
        encodeVaxLongWithoutLength(buf, off, val);
    }

    /**
     * Encodes a long using Vax encoding into an output stream, without length prefix.
     *
     * @param out
     *         output stream to write
     * @param val
     *         value to encode
     */
    public static void encodeVaxLongWithoutLength(OutputStream out, long val) throws IOException {
        byte[] buf = new byte[8];
        encodeVaxLongWithoutLength(buf, 0, val);
        out.write(buf, 0, 8);
    }

    /**
     * Encodes a long using Vax encoding into {@code buf}, without length prefix.
     *
     * @param buf
     *         byte array of sufficient size
     * @param off
     *         offset to start writing
     * @param val
     *         value to encode
     */
    public static void encodeVaxLongWithoutLength(byte[] buf, int off, long val) {
        buf[off++] = (byte) val;
        buf[off++] = (byte) (val >>> 8);
        buf[off++] = (byte) (val >>> 16);
        buf[off++] = (byte) (val >>> 24);
        buf[off++] = (byte) (val >>> 32);
        buf[off++] = (byte) (val >>> 40);
        buf[off++] = (byte) (val >>> 48);
        buf[off] = (byte) (val >>> 56);
    }

    /**
     * Variant of {@link #iscVaxInteger(byte[], int, int)} specifically for two-byte integers.
     *
     * @param buf
     *         byte array from which the integer is to be retrieved
     * @param off
     *         offset from which to start retrieving byte values
     * @return integer value retrieved from the bytes
     * @see #iscVaxInteger(byte[], int, int)
     * @see #iscVaxLong(byte[], int, int)
     */
    public static int iscVaxInteger2(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    }

    /**
     * Encodes an integer using two byte Vax encoding into an output stream, without length prefix.
     *
     * @param out
     *         output stream to write
     * @param val
     *         value to encode
     */
    public static void encodeVaxInteger2WithoutLength(OutputStream out, int val) throws IOException {
        out.write(val);
        out.write(val >> 8);
    }

    /**
     * Decodes an integer using two byte Vax encoding from an input stream, without length prefix.
     *
     * @param in
     *         input stream to read
     * @return decoded value
     * @throws IOException
     *         for errors reading from the stream, or if end-of-stream was reached before the full integer
     * @since 6
     */
    public static int decodeVaxInteger2WithoutLength(InputStream in) throws IOException {
        int ch1 = in.read();
        int ch2 = in.read();
        if ((ch1 | ch2) < 0)
            throw new EOFException();
        return ch1 | (ch2 << 8);
    }

}
