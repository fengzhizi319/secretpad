/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.web;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 安全整改 P0-1 守卫：启动横幅里不得出现口令。历史横幅是 {@code userName:admin password:12345678}。
 */
public class StartupBannerTest {

    @Test
    void bannerNeverContainsCredentials() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.port", "8080")
                .withProperty("server.http-port-inner", "9001")
                .withProperty("secretpad.auth.pad_name", "admin")
                .withProperty("secretpad.auth.pad_pwd", "Sup3r-Secret!Pass");

        String banner = SecretPadApplication.startupBanner(env, "127.0.0.1");

        Assertions.assertFalse(banner.contains("Sup3r-Secret!Pass"), "banner leaked the password: " + banner);
        Assertions.assertFalse(banner.toLowerCase().contains("password:"), "banner must not print a password field: " + banner);
        Assertions.assertTrue(banner.contains("8080") && banner.contains("9001"), banner);
    }
}
