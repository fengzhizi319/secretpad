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

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 安全整改守卫（docs/secretpad_auth.md P0-3）：{@code enabled=true} 时五项材料缺一不可——
 * 「配了就必须生效，否则报错」，不允许半配置状态悄悄退回明文。
 *
 * @author claude
 * @date 2026/09/24
 */
class InnerPortMtlsConfigTest {

    @Test
    void disabledSkipsValidation() {
        InnerPortMtlsConfig config = new InnerPortMtlsConfig();
        ReflectionTestUtils.setField(config, "enabled", false);
        assertDoesNotThrow(config::validate);
    }

    @Test
    void enabledWithAllMaterialPasses() {
        InnerPortMtlsConfig config = fullyConfigured();
        assertDoesNotThrow(config::validate);
    }

    @Test
    void enabledMissingTrustStoreFailsClosed() {
        InnerPortMtlsConfig config = fullyConfigured();
        ReflectionTestUtils.setField(config, "trustStore", "");
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void enabledMissingKeyStorePasswordFailsClosed() {
        InnerPortMtlsConfig config = fullyConfigured();
        ReflectionTestUtils.setField(config, "keyStorePassword", "");
        assertThrows(IllegalStateException.class, config::validate);
    }

    private InnerPortMtlsConfig fullyConfigured() {
        InnerPortMtlsConfig config = new InnerPortMtlsConfig();
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "keyStore", "ks.p12");
        ReflectionTestUtils.setField(config, "keyStorePassword", "pw");
        ReflectionTestUtils.setField(config, "keyAlias", "alias");
        ReflectionTestUtils.setField(config, "trustStore", "ts.p12");
        ReflectionTestUtils.setField(config, "trustStorePassword", "pw");
        return config;
    }
}
