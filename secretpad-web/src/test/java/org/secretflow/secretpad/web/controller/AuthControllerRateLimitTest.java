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
package org.secretflow.secretpad.web.controller;

import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.service.AuthService;
import org.secretflow.secretpad.service.model.auth.LoginRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 安全整改守卫（docs/secretpad_auth.md P1-4，对应现状清单 M3）：{@code /api/login} 必须真的调用了
 * 按来源 IP 的限速，而不只是"该方法存在但没接线"。不依赖完整 Spring 上下文/MockMvc——
 * 直接构造 controller 并通过 {@link RequestContextHolder} 绑定一个模拟请求，
 * 用独立的远程地址避免与其它测试共享 {@code RateLimitUtil} 静态缓存产生干扰。
 *
 * @author claude
 * @date 2026/09/24
 */
class AuthControllerRateLimitTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void secondLoginFromSameIpWithinWindowIsRateLimited() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        ReflectionTestUtils.setField(controller, "loginRateLimitPerMinute", 1.0);
        // 每个测试用不重复的模拟来源地址，避免与同一 JVM fork 内其它调用 /api/login 的测试共享预算。
        String remoteAddr = "203.0.113." + (System.nanoTime() % 250);
        LoginRequest request = LoginRequest.builder().name("admin").passwordHash("x").build();

        bindRequestFrom(remoteAddr);
        assertDoesNotThrow(() -> controller.login(request), "first attempt within budget must reach AuthService");
        Mockito.verify(authService, Mockito.times(1)).login("admin", "x");

        bindRequestFrom(remoteAddr);
        assertThrows(SecretpadException.class, () -> controller.login(request),
                "second attempt from the same IP within the window must be rejected before reaching AuthService");
        // 仍然只被调用过一次——限速必须挡在 AuthService 之前，而不是"挡了但业务照常执行"。
        Mockito.verify(authService, Mockito.times(1)).login("admin", "x");
    }

    @Test
    void zeroOrNegativeLimitDisablesRateLimiting() {
        AuthService authService = Mockito.mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        ReflectionTestUtils.setField(controller, "loginRateLimitPerMinute", -1.0);
        String remoteAddr = "203.0.113." + (System.nanoTime() % 250);
        LoginRequest request = LoginRequest.builder().name("admin").passwordHash("x").build();

        // <= 0 显式关闭限速：多次调用都必须直达 AuthService（与 auth.enabled=false 等
        // 其它可选安全层的"显式关闭"口径一致，测试环境正是这样配置的）。
        for (int i = 0; i < 5; i++) {
            bindRequestFrom(remoteAddr);
            assertDoesNotThrow(() -> controller.login(request));
        }
        Mockito.verify(authService, Mockito.times(5)).login("admin", "x");
    }
}
