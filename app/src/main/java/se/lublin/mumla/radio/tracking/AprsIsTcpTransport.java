/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Short-lived APRS-IS TCP submitter; it never holds a tracking connection open. */
public final class AprsIsTcpTransport implements AprsTransport {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_GREETING_LINES = 32;

    @Override
    public SendResult send(AprsTrackingConfig config, String packet) {
        if (config == null || !config.isAprsEnabled() || packet == null || packet.isEmpty()) {
            return SendResult.retryable("tracking-not-configured");
        }
        if (!isValidPacketLine(packet)) {
            return SendResult.permanent("aprs-invalid-packet");
        }
        boolean packetWriteStarted = false;
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(config.getHost(), config.getPort()),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.US_ASCII));

            String greeting = reader.readLine();
            if (greeting == null || !greeting.startsWith("#")) {
                return SendResult.retryable("aprs-is-greeting-invalid");
            }

            writer.write("user ");
            writer.write(config.getSourceCallsign());
            writer.write(" pass ");
            writer.write(config.getPasscode());
            writer.write(" vers Minimum 1.0\r\n");
            writer.flush();
            LoginResponse loginResponse = readLoginResponse(reader, config.getSourceCallsign());
            if (loginResponse == LoginResponse.REJECTED) {
                return SendResult.permanent("aprs-is-login-rejected");
            }
            if (loginResponse != LoginResponse.VERIFIED) {
                return SendResult.retryable("aprs-is-login-response-invalid");
            }

            packetWriteStarted = true;
            writer.write(packet);
            writer.write("\r\n");
            writer.flush();
            return SendResult.success();
        } catch (java.net.SocketTimeoutException timeout) {
            return packetWriteStarted
                    ? SendResult.uncertain("aprs-is-timeout-after-write")
                    : SendResult.retryable("aprs-is-timeout-before-write");
        } catch (IOException exception) {
            return packetWriteStarted
                    ? SendResult.uncertain("aprs-is-io-after-write")
                    : SendResult.retryable("aprs-is-io-before-write");
        }
    }

    static boolean isValidPacketLine(String packet) {
        if (packet == null || packet.isEmpty() || packet.length() > 510
                || packet.indexOf('\r') >= 0 || packet.indexOf('\n') >= 0) {
            return false;
        }
        byte[] ascii = packet.getBytes(StandardCharsets.US_ASCII);
        return ascii.length <= 510 && packet.equals(new String(ascii, StandardCharsets.US_ASCII));
    }

    enum LoginResponse {
        VERIFIED,
        REJECTED,
        INVALID
    }

    static LoginResponse parseLoginResponse(String line, String expectedCallsign) {
        if (line == null || expectedCallsign == null || !line.startsWith("# logresp ")) {
            return LoginResponse.INVALID;
        }
        String[] fields = line.substring("# logresp ".length()).trim().split("\\s+", 4);
        if (fields.length >= 2) {
            String status = fields[1].replace(",", "");
            if ("unverified".equalsIgnoreCase(status) || "bad".equalsIgnoreCase(status)) {
                return LoginResponse.REJECTED;
            }
        }
        if (fields.length < 2 || !expectedCallsign.equalsIgnoreCase(fields[0])) {
            return LoginResponse.INVALID;
        }
        return fields.length >= 4 && "verified,".equalsIgnoreCase(fields[1])
                && "server".equalsIgnoreCase(fields[2]) && !fields[3].isEmpty()
                ? LoginResponse.VERIFIED : LoginResponse.INVALID;
    }

    private static LoginResponse readLoginResponse(BufferedReader reader, String expectedCallsign)
            throws IOException {
        for (int lineCount = 0; lineCount < MAX_GREETING_LINES; lineCount++) {
            String line = reader.readLine();
            if (line == null) {
                return LoginResponse.INVALID;
            }
            if (line.startsWith("# logresp ")) {
                return parseLoginResponse(line, expectedCallsign);
            }
        }
        return LoginResponse.INVALID;
    }
}
