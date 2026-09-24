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
package org.secretflow.secretpad.service.util;

import org.secretflow.secretpad.common.exception.SecretpadException;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 安全整改守卫（docs/secretpad_auth.md P1-4，对应现状清单 M3）：{@link RateLimitUtil#verifyRate(String, double, double)}
 * 必须独立于 {@code UserContext}——登录端点在认证发生之前调用它，此时没有已认证身份可用。
 * 每个用例用随机 key，避免 {@link RateLimitUtil} 内部静态缓存在用例之间互相污染。
 *
 * @author claude
 * @date 2026/09/24
 */
class RateLimitUtilKeyedTest {

    @Test
    void firstCallWithinBudgetSucceeds() {
        String key = UUID.randomUUID().toString();
        assertDoesNotThrow(() -> RateLimitUtil.verifyRate(key, 20, 60));
    }

    @Test
    void exceedingBudgetIsRejected() {
        String key = UUID.randomUUID().toString();
        // 窗口内只放行 1 次的限速：第一次必须通过，紧接着的第二次必须被拒——
        // 证明限速真的在生效，而不是"调了这个方法但从不真正限制"。
        assertDoesNotThrow(() -> RateLimitUtil.verifyRate(key, 1, 60));
        assertThrows(SecretpadException.class, () -> RateLimitUtil.verifyRate(key, 1, 60));
    }

    @Test
    void differentKeysAreIndependentBudgets() {
        String keyA = UUID.randomUUID().toString();
        String keyB = UUID.randomUUID().toString();
        assertDoesNotThrow(() -> RateLimitUtil.verifyRate(keyA, 1, 60));
        // keyA 的预算耗尽不应该影响 keyB——按 IP 限速的前提就是不同来源互不影响。
        assertDoesNotThrow(() -> RateLimitUtil.verifyRate(keyB, 1, 60));
    }
}
