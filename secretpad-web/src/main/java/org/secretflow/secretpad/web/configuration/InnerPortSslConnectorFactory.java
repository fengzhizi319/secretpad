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

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

/**
 * 把一个 Tomcat {@link Connector} 升级为要求客户端证书的双向 TLS。
 * <p>
 * 从 {@code SecretPadApplication} 里抽出来单独成类（安全整改 P0-3），是为了让"这段 SSLHostConfig
 * 配置到底有没有真的强制客户端证书"这件事能脱离整个 Spring 上下文独立测试——启动
 * 完整应用做一次真实 TLS 握手测试很重，而这正是最需要被验证到"真的握手成功/失败"而不是
 * "对象构造没抛异常"的一段代码（历史上 {@code InsecureTrustManagerFactory} 那个缺陷，
 * 教训就是"能建立连接"和"验证了对方身份"是两件事）。
 *
 * @author claude
 * @date 2026/09/24
 */
public final class InnerPortSslConnectorFactory {

    private InnerPortSslConnectorFactory() {
    }

    /**
     * 把 {@code clientAuth=required} 的双向 TLS 配置应用到给定的 connector 上：
     * 只有出示了受 {@code trustStore} 信任的证书的客户端才能完成握手，未出示或证书不受信的连接
     * 在 TLS 层就被拒绝——请求根本到不了 servlet 容器，不依赖任何应用层判断。
     */
    public static void applyMtls(Connector connector, InnerPortMtlsConfig config) {
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");

        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setCertificateVerification("required");
        sslHostConfig.setTruststoreFile(config.getTrustStore());
        sslHostConfig.setTruststorePassword(config.getTrustStorePassword());

        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(sslHostConfig,
                SSLHostConfigCertificate.Type.RSA);
        certificate.setCertificateKeystoreFile(config.getKeyStore());
        certificate.setCertificateKeystorePassword(config.getKeyStorePassword());
        certificate.setCertificateKeyAlias(config.getKeyAlias());
        sslHostConfig.addCertificate(certificate);

        connector.addSslHostConfig(sslHostConfig);
    }
}
