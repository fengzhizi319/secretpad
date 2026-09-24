/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.secretflow.secretpad.web.configuration;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 安全整改守卫（docs/secretpad_auth.md P0-3）。
 * <p>
 * 内网口此前是纯 HTTP，节点身份完全依赖可伪造的 {@code kuscia-origin-source} 头。本用例不满足于
 * "connector 对象构造没抛异常"，而是启动一个真实的 Tomcat 连接器（脱离整个 Spring 上下文，
 * 只加载 {@link InnerPortSslConnectorFactory} 这一段配置逻辑）并真的发起 HTTPS 请求：
 * <ul>
 *   <li>客户端持有受信 CA（{@code gateway-ca.crt}）签发的证书 → 握手成功，请求正常完成；</li>
 *   <li>客户端不出示任何证书，或持有一张无关 CA 签发的证书（{@code attacker-*}）→ 握手必须失败。
 *       这条断言在"忘记设置 clientAuth=required"或"信任库配错"时会变红。</li>
 * </ul>
 *
 * @author claude
 * @date 2026/09/24
 */
class InnerPortSslConnectorFactoryTest {

    private static final String CERT_DIR = "src/test/resources/certs/inner-mtls/";

    private Tomcat tomcat;
    private int port;

    @AfterEach
    void tearDown() throws LifecycleException {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static int findAvailablePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void startServer() throws Exception {
        port = findAvailablePort();
        tomcat = new Tomcat();
        tomcat.setPort(0);
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);

        InnerPortMtlsConfig config = new InnerPortMtlsConfig();
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "keyStore", new File(CERT_DIR + "inner-server.p12").getAbsolutePath());
        ReflectionTestUtils.setField(config, "keyStorePassword", "changeit");
        ReflectionTestUtils.setField(config, "keyAlias", "inner-server");
        ReflectionTestUtils.setField(config, "trustStore", new File(CERT_DIR + "inner-truststore.p12").getAbsolutePath());
        ReflectionTestUtils.setField(config, "trustStorePassword", "changeit");
        config.validate();

        InnerPortSslConnectorFactory.applyMtls(connector, config);
        tomcat.getService().addConnector(connector);
        tomcat.setConnector(connector);

        tomcat.addContext("", new File(".").getAbsolutePath());
        tomcat.start();
    }

    @Test
    void trustedClientCertificateCompletesHandshake() throws Exception {
        startServer();
        HttpsURLConnection conn = openConnection(clientContext("gateway-client.p12"));
        assertEquals(404, conn.getResponseCode(),
                "handshake must succeed with a cert signed by the trusted CA; a 404 (no servlet mapped) "
                        + "still proves TLS + app layer were reached, which is what this test checks");
    }

    @Test
    void untrustedClientCertificateFailsHandshake() throws Exception {
        startServer();
        HttpsURLConnection conn = openConnection(clientContext("attacker-client.p12"));
        assertThrows(SSLHandshakeException.class, conn::getResponseCode,
                "a certificate signed by an unrelated CA must be rejected at the TLS layer");
    }

    @Test
    void noClientCertificateFailsHandshake() throws Exception {
        startServer();
        SSLContext ctx = SSLContext.getInstance("TLS");
        // 客户端要信任的是服务端证书（inner-server.crt，自签），不是服务端用来校验客户端证书的
        // gateway CA——两个方向的信任锚不是同一份材料，混用会让测试的失败原因不明确。
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(CERT_DIR + "client-truststore.p12")) {
            trustStore.load(fis, "changeit".toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        ctx.init(new KeyManager[0], tmf.getTrustManagers(), new SecureRandom());

        HttpsURLConnection conn = (HttpsURLConnection) URI.create("https://127.0.0.1:" + port + "/").toURL().openConnection();
        conn.setSSLSocketFactory(ctx.getSocketFactory());
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        assertThrows(Exception.class, conn::getResponseCode,
                "a client presenting no certificate at all must fail the required-clientAuth handshake");
    }

    private HttpsURLConnection openConnection(SSLContext ctx) throws Exception {
        HttpsURLConnection conn = (HttpsURLConnection) URI.create("https://127.0.0.1:" + port + "/").toURL().openConnection();
        conn.setSSLSocketFactory(ctx.getSocketFactory());
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        return conn;
    }

    private SSLContext clientContext(String clientP12) throws Exception {
        KeyStore clientKeyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(CERT_DIR + clientP12)) {
            clientKeyStore.load(fis, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientKeyStore, "changeit".toCharArray());

        // 客户端信任服务端的自签证书（与服务端信任客户端证书用的 gateway CA 是两份不同材料）。
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(CERT_DIR + "client-truststore.p12")) {
            trustStore.load(fis, "changeit".toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }
}
