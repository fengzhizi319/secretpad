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
package org.secretflow.secretpad.web.aop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 安全整改回归（docs/secretpad_auth.md §8，日志卫生）：LoggingAspect 对每个 controller 方法入参
 * 都会 toString() 后打进 INFO 日志。裸 String 入参（如 InstController.registerNode 的 jsonData，
 * 内含 Kuscia 入网 token；DataSyncController.sync 的 p，是任意允许表的整行 JSON）无法靠 DTO 级
 * @ToString.Exclude 屏蔽——本用例锁定 redactRawString 对任意字符串只打印长度、不打印内容，
 * 且不改变非字符串参数的行为。
 */
class LoggingAspectTest {

    @Test
    void rawStringArgIsRedactedToLengthOnly() {
        String secretJson = "{\"token\":\"super-secret-kuscia-join-token\"}";
        Object result = ReflectionTestUtils.invokeMethod(LoggingAspect.class, "redactRawString", secretJson);
        Assertions.assertTrue(result instanceof String);
        String redacted = (String) result;
        Assertions.assertFalse(redacted.contains("super-secret-kuscia-join-token"), "redacted value must not contain the original content: " + redacted);
        Assertions.assertTrue(redacted.contains(String.valueOf(secretJson.length())), "redacted value should still convey the original length: " + redacted);
    }

    @Test
    void nonStringArgIsPassedThroughUnchanged() {
        Object original = new Object() {
            @Override
            public String toString() {
                return "non-sensitive-dto-toString";
            }
        };
        Object result = ReflectionTestUtils.invokeMethod(LoggingAspect.class, "redactRawString", original);
        Assertions.assertSame(original, result, "non-String args must pass through unchanged so DTOs' own toString()/@ToString.Exclude still apply");
    }

    @Test
    void nullArgIsPassedThroughUnchanged() {
        Object result = ReflectionTestUtils.invokeMethod(LoggingAspect.class, "redactRawString", (Object) null);
        Assertions.assertNull(result);
    }
}
