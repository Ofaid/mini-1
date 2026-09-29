/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

import android.util.Base64;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import javax.net.ssl.HttpsURLConnection;

import se.lublin.mumla.R;

/** One-shot authenticated APRS-IS HTTPS send-only transport. */
public final class AprsIsHttpTransport implements AprsTransport {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private final SSLSocketFactory sslSocketFactory;

    public AprsIsHttpTransport() {
        this(null);
    }

    AprsIsHttpTransport(android.content.Context context) {
        sslSocketFactory = context == null ? null : createPinnedSocketFactory(context);
    }

    @Override
    public SendResult send(AprsTrackingConfig config, String packet) {
        if (config == null || !config.isAprsEnabled()) {
            return SendResult.retryable("tracking-not-configured");
        }
        if (!isValidPacketLine(packet)) {
            return SendResult.permanent("aprs-invalid-packet");
        }
        HttpsURLConnection connection = null;
        boolean bodyWriteStarted = false;
        try {
            connection = (HttpsURLConnection) new URL("https", config.getHost(),
                    config.getPort(), "/").openConnection();
            byte[] body = packet.getBytes(StandardCharsets.US_ASCII);
            byte[] credential = (config.getSourceCallsign() + ":" + config.getPasscode())
                    .getBytes(StandardCharsets.US_ASCII);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            if (sslSocketFactory != null) {
                connection.setSSLSocketFactory(sslSocketFactory);
            }
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Accept", "text/plain");
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("Authorization", "Basic "
                    + Base64.encodeToString(credential, Base64.NO_WRAP));
            try (OutputStream output = connection.getOutputStream()) {
                // Set this immediately before the first body byte so connect failures remain retryable.
                bodyWriteStarted = true;
                output.write(body);
                output.flush();
            }
            int statusCode = connection.getResponseCode();
            return classifyResponse(statusCode, connection.getHeaderField("X-Packetsrcvd"));
        } catch (IOException exception) {
            android.util.Log.w("MinimumAprs", "APRS HTTPS transport failed before="
                    + bodyWriteStarted + " type=" + exception.getClass().getSimpleName()
                    + " cause=" + (exception.getCause() == null
                    ? "none" : exception.getCause().getClass().getSimpleName())
                    + " message=" + String.valueOf(exception.getMessage()));
            return bodyWriteStarted
                    ? SendResult.uncertain("aprs-is-https-after-write")
                    : SendResult.retryable("aprs-is-https-before-write");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static SendResult classifyResponse(int statusCode, String packetsReceived) {
        if (statusCode == HttpURLConnection.HTTP_NO_CONTENT) {
            try {
                if (Integer.parseInt(packetsReceived) > 0) {
                    return SendResult.success();
                }
            } catch (NumberFormatException ignored) {
                // A 204 without the documented count is not sufficient confirmation.
            }
            return SendResult.uncertain("aprs-is-https-missing-receipt");
        }
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
                || statusCode == HttpURLConnection.HTTP_FORBIDDEN
                || statusCode == HttpURLConnection.HTTP_BAD_REQUEST
                || statusCode == HttpURLConnection.HTTP_LENGTH_REQUIRED
                || statusCode == 413
                || statusCode == HttpURLConnection.HTTP_UNSUPPORTED_TYPE
                || statusCode == 417) {
            return SendResult.permanent("aprs-is-https-rejected-" + statusCode);
        }
        return SendResult.retryable("aprs-is-https-status-" + statusCode);
    }

    static boolean isValidPacketLine(String packet) {
        return AprsIsTcpTransport.isValidPacketLine(packet);
    }

    private static SSLSocketFactory createPinnedSocketFactory(android.content.Context context) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate root;
            try (InputStream input = context.getResources().openRawResource(R.raw.isrg_root_x1)) {
                root = certificateFactory.generateCertificate(input);
            }
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("isrg-root-x1", root);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(new KeyManager[0], trustManagerFactory.getTrustManagers(),
                    new SecureRandom());
            return new Tls12SocketFactory(sslContext.getSocketFactory());
        } catch (Exception exception) {
            android.util.Log.w("MinimumAprs", "APRS CA setup failed: "
                    + exception.getClass().getSimpleName());
            return null;
        }
    }

    /** Enables TLS 1.2 on API-22 sockets while preserving normal hostname verification. */
    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException {
            return enable(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return enable(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(String host, int port, InetAddress localHost,
                                             int localPort) throws IOException {
            return enable(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return enable(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(InetAddress address, int port,
                                             InetAddress localAddress, int localPort)
                throws IOException {
            return enable(delegate.createSocket(address, port, localAddress, localPort));
        }

        private static Socket enable(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket sslSocket = (SSLSocket) socket;
                String[] supported = sslSocket.getSupportedProtocols();
                for (String protocol : supported) {
                    if ("TLSv1.2".equals(protocol)) {
                        sslSocket.setEnabledProtocols(new String[] {"TLSv1.2"});
                        break;
                    }
                }
            }
            return socket;
        }
    }
}
