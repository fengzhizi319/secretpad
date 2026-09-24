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
package org.secretflow.secretpad.service.model.auth;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 安全整改回归（docs/secretpad_auth.md §8，日志卫生）：UserUpdatePwdRequest 与 ResetNodeUserPwdRequest
 * 分别以 {@code @Data}/{@code @ToString} 生成 toString()，会被 LoggingAspect 对每个 controller
 * 方法参数打进 INFO 日志。本系统登录/改密走前端 SM3 预哈希、服务端直接比对哈希，哈希泄露等价于
 * 密码泄露，必须锁定这些哈希字段不出现在 toString() 里。
 */
class PasswordHashToStringExclusionTest {

    @Test
    void userUpdatePwdRequestToStringExcludesAllThreeHashes() {
        UserUpdatePwdRequest request = new UserUpdatePwdRequest();
        request.setName("alice");
        request.setOldPasswordHash("OLD_HASH_VALUE");
        request.setNewPasswordHash("NEW_HASH_VALUE");
        request.setConfirmPasswordHash("CONFIRM_HASH_VALUE");
        String s = request.toString();
        Assertions.assertFalse(s.contains("OLD_HASH_VALUE"), "toString() must not leak oldPasswordHash: " + s);
        Assertions.assertFalse(s.contains("NEW_HASH_VALUE"), "toString() must not leak newPasswordHash: " + s);
        Assertions.assertFalse(s.contains("CONFIRM_HASH_VALUE"), "toString() must not leak confirmPasswordHash: " + s);
        Assertions.assertTrue(s.contains("alice"), "toString() should still print non-sensitive fields: " + s);
    }

    @Test
    void resetNodeUserPwdRequestToStringExcludesBothHashes() {
        ResetNodeUserPwdRequest request = new ResetNodeUserPwdRequest();
        request.setNodeId("node-1");
        request.setName("alice");
        request.setPasswordHash("OLD_HASH_VALUE");
        request.setNewPasswordHash("NEW_HASH_VALUE");
        String s = request.toString();
        Assertions.assertFalse(s.contains("OLD_HASH_VALUE"), "toString() must not leak passwordHash: " + s);
        Assertions.assertFalse(s.contains("NEW_HASH_VALUE"), "toString() must not leak newPasswordHash: " + s);
        Assertions.assertTrue(s.contains("node-1"), "toString() should still print non-sensitive fields: " + s);
    }
}
