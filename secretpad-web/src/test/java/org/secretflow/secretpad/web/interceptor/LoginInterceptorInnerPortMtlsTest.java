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
package org.secretflow.secretpad.web.interceptor;

import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.SysResourcesBizService;
import org.secretflow.secretpad.persistence.repository.UserTokensRepository;
import org.secretflow.secretpad.web.configuration.InnerPortMtlsConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 安全整改守卫（docs/secretpad_auth.md P0-3）：{@code LoginInterceptor.processByNodeRpcRequest}
 * 里的纵深防御检查——mTLS 启用时，没有验证过的客户端证书就必须拒绝，即使
 * {@code kuscia-origin-source} 头写得完全正确。这条检查独立于 Tomcat 连接器层的强制，
 * 用一个不依赖真实 TLS 握手的单测直接验证分支逻辑本身没有写错（例如条件判反、
 * 数组判空写错这类改动 {@link InnerPortSslConnectorFactoryTest} 覆盖不到的错误）。
 *
 * @author claude
 * @date 2026/09/24
 */
class LoginInterceptorInnerPortMtlsTest {

    private static final int INNER_PORT = 9001;

    private LoginInterceptor interceptor;
    private InnerPortMtlsConfig mtlsConfig;

    @BeforeEach
    void setUp() {
        interceptor = new LoginInterceptor(
                Mockito.mock(UserTokensRepository.class),
                Mockito.mock(EnvService.class),
                Mockito.mock(SysResourcesBizService.class),
                Mockito.mock(ProjectNodeRepository.class));
        mtlsConfig = new InnerPortMtlsConfig();
        ReflectionTestUtils.setField(interceptor, "innerPortMtlsConfig", mtlsConfig);
        ReflectionTestUtils.setField(interceptor, "innerHttpPort", INNER_PORT);
        ReflectionTestUtils.setField(interceptor, "enable", true);
        ReflectionTestUtils.setField(interceptor, "deployMode", "ALL-IN-ONE");
    }

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private HttpServletRequest innerRequest(X509Certificate[] cert) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getLocalPort()).thenReturn(INNER_PORT);
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getHeader("kuscia-origin-source")).thenReturn("alice");
        Mockito.when(request.getAttribute("jakarta.servlet.request.X509Certificate")).thenReturn(cert);
        return request;
    }

    @Test
    void mtlsDisabled_headerAloneIsAccepted() {
        ReflectionTestUtils.setField(mtlsConfig, "enabled", false);
        assertDoesNotThrow(() -> interceptor.preHandle(innerRequest(null), Mockito.mock(HttpServletResponse.class), new Object()),
                "with mTLS disabled the historical header-trust behaviour must be unchanged");
    }

    @Test
    void mtlsEnabled_missingClientCertificateIsRejected() {
        ReflectionTestUtils.setField(mtlsConfig, "enabled", true);
        SecretpadException ex = assertThrows(SecretpadException.class,
                () -> interceptor.preHandle(innerRequest(null), Mockito.mock(HttpServletResponse.class), new Object()),
                "the correct header alone must not be enough once mTLS is enabled");
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("no verified client certificate"), ex.getMessage());
    }

    @Test
    void mtlsEnabled_emptyClientCertificateArrayIsRejected() {
        ReflectionTestUtils.setField(mtlsConfig, "enabled", true);
        assertThrows(SecretpadException.class,
                () -> interceptor.preHandle(innerRequest(new X509Certificate[0]), Mockito.mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void mtlsEnabled_presentClientCertificateIsAccepted() {
        ReflectionTestUtils.setField(mtlsConfig, "enabled", true);
        X509Certificate cert = Mockito.mock(X509Certificate.class);
        assertDoesNotThrow(() -> interceptor.preHandle(innerRequest(new X509Certificate[]{cert}), Mockito.mock(HttpServletResponse.class), new Object()));
    }
}
