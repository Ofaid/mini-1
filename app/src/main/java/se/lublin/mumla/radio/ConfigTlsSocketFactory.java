/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import se.lublin.mumla.R;

/** Adds the current ISRG root to old Android trust stores without weakening hostname checks. */
final class ConfigTlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;

    static SSLSocketFactory create(Context context) throws IOException {
        try {
            X509TrustManager systemTrust = trustManager(null);
            KeyStore bundledStore = KeyStore.getInstance(KeyStore.getDefaultType());
            bundledStore.load(null, null);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate root;
            try (InputStream input = context.getResources().openRawResource(R.raw.isrg_root_x1)) {
                root = certificateFactory.generateCertificate(input);
            }
            bundledStore.setCertificateEntry("isrg-root-x1", root);
            X509TrustManager bundledTrust = trustManager(bundledStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null,
                    new TrustManager[] {new CompositeTrustManager(systemTrust, bundledTrust)},
                    new SecureRandom());
            return new ConfigTlsSocketFactory(sslContext.getSocketFactory());
        } catch (GeneralSecurityException exception) {
            throw new IOException("cannot initialize config TLS trust", exception);
        }
    }

    private static X509TrustManager trustManager(KeyStore keyStore)
            throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new GeneralSecurityException("X509 trust manager is unavailable");
    }

    private ConfigTlsSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    @Override public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
            throws IOException {
        return enableTls12(delegate.createSocket(socket, host, port, autoClose));
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        return enableTls12(delegate.createSocket(host, port));
    }

    @Override public Socket createSocket(String host, int port, InetAddress localHost,
                                         int localPort) throws IOException {
        return enableTls12(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTls12(delegate.createSocket(host, port));
    }

    @Override public Socket createSocket(InetAddress address, int port,
                                         InetAddress localAddress, int localPort)
            throws IOException {
        return enableTls12(delegate.createSocket(address, port, localAddress, localPort));
    }

    private static Socket enableTls12(Socket socket) {
        if (!(socket instanceof SSLSocket)) {
            return socket;
        }
        SSLSocket sslSocket = (SSLSocket) socket;
        for (String protocol : sslSocket.getSupportedProtocols()) {
            if ("TLSv1.2".equals(protocol)) {
                sslSocket.setEnabledProtocols(new String[] {"TLSv1.2"});
                break;
            }
        }
        return sslSocket;
    }

    private static final class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager bundled;

        CompositeTrustManager(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException systemFailure) {
                try {
                    bundled.checkServerTrusted(chain, authType);
                } catch (CertificateException bundledFailure) {
                    bundledFailure.addSuppressed(systemFailure);
                    throw bundledFailure;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] systemIssuers = system.getAcceptedIssuers();
            X509Certificate[] bundledIssuers = bundled.getAcceptedIssuers();
            X509Certificate[] combined = new X509Certificate[
                    systemIssuers.length + bundledIssuers.length];
            System.arraycopy(systemIssuers, 0, combined, 0, systemIssuers.length);
            System.arraycopy(bundledIssuers, 0, combined, systemIssuers.length,
                    bundledIssuers.length);
            return combined;
        }
    }
}
