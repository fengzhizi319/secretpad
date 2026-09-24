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
package org.secretflow.secretpad.kuscia.v1alpha1.test;


import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaModeEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaProtocolEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.model.KusciaGrpcConfig;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author yutu
 * @date 2024/07/05
 */
public class KusciaGrpcConfigTest {

    private KusciaGrpcConfig config = KusciaGrpcConfig.builder()
            .domainId("alice")
            .host("localhost")
            .mode(KusciaModeEnum.P2P)
            .port(8080)
            .protocol(KusciaProtocolEnum.TLS)
            .token("token.txt")
            .certFile("cert.pem")
            .keyFile("key.pem")
            .caFile("ca.pem")
            .build();

    /**
     * Test scenario: verify and process normal configuration
     */
    @Test
    public void testValidateAndProcessNormalConfig() {
        config.validateAndProcess();
    }

    /**
     * Test scenario: Verify and handle the situation where the host is empty
     */
    @Test
    public void testValidateAndProcessHostEmpty() {
        config.setHost(null);
        assertThrows(IllegalArgumentException.class, config::validateAndProcess);
    }

    /**
     * Test scenario: Verify and handle the case where the port is a negative number
     */
    @Test
    public void testValidateAndProcessPortNegative() {
        config.setPort(-1);
        assertThrows(IllegalArgumentException.class, config::validateAndProcess);
    }

    /**
     * Test scenario: Verify and handle the situation where the protocol is empty
     */
    @Test
    public void testValidateAndProcessProtocolEmpty() {
        config.setProtocol(null);
        assertThrows(IllegalArgumentException.class, config::validateAndProcess);
    }

    /**
     * Test scenario: Verify and handle the situation where the token is a file path
     */
    @Test
    public void testValidateAndProcessTokenFilePath() {
        config.setToken("token.txt");
        config.validateAndProcess();
        assertFalse(new File(config.getToken()).exists());
    }

    /**
     * Test scenario: Verify and handle the situation where the token is a non-file path
     */
    @Test
    public void testValidateAndProcessTokenNotFilePath() {
        config.setToken("token");
        assertDoesNotThrow(config::validateAndProcess);
    }

    /**
     * Test scenario: Verify and handle the case where certFile is empty
     */
    @Test
    public void testValidateAndProcessCertFileEmpty() {
        config.setCertFile(null);
        config.setProtocol(KusciaProtocolEnum.TLS);
        assertThrows(IllegalArgumentException.class, config::validateAndProcess);
    }

    /**
     * Test scenario: Verify and handle the case where keyFile is empty
     */
    @Test
    public void testValidateAndProcessKeyFileEmpty() {
        config.setKeyFile(null);
        config.setProtocol(KusciaProtocolEnum.TLS);
        assertThrows(IllegalArgumentException.class, config::validateAndProcess);
    }

    /**
     * 安全整改守卫（docs/secretpad_auth.md P0-2）：TLS/MTLS 下 caFile 缺失必须拒绝启动。
     * 历史实现没有 caFile 这个概念，出站直接用 InsecureTrustManagerFactory 不验证服务端证书；
     * 这条用例钉住"没有 CA 就是配置错误，不是可以静默退化的选项"。
     */
    @Test
    public void testValidateAndProcessCaFileEmpty() {
        config.setCaFile(null);
        config.setProtocol(KusciaProtocolEnum.TLS);
        assertThrows(IllegalArgumentException.class, config::validateAndProcess);
    }

    @Test
    public void testValidateAndProcessCaFileEmptyMtls() {
        config.setCaFile("");
        config.setProtocol(KusciaProtocolEnum.MTLS);
        assertThrows(IllegalArgumentException.class, config::validateAndProcess);
    }
}
