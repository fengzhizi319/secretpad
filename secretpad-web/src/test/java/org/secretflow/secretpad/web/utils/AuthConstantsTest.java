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

package org.secretflow.secretpad.web.utils;

import org.secretflow.secretpad.web.constant.AuthConstants;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * 安全整改 P0-1 守卫：默认路径（{@code generateRandomPassword()} / {@code getRandomPassword()}）产生的
 * 初始口令必须是随机、足够长、四类字符齐全的，历史那种"两个方法直接 return 12345678"的写法不得回来。
 * <p>
 * 应维护者要求保留的显式 opt-in 例外 {@link AuthConstants#FIXED_TEST_PASSWORD} 允许存在且允许等于
 * {@code "12345678"}——它不是本类要守的对象；本类守的是"默认路径绝不产出固定值"与
 * "除了这一个明确命名、明确文档化的字段，没有第二个字段悄悄持有这个弱口令"。
 *
 * @author yutu
 * @date 2024/12/13
 */
public class AuthConstantsTest {

    private static final String FOUR_CLASSES = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{16,}$";

    @Test
    public void generatedPasswordIsStrongAndRandom() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String password = AuthConstants.generateRandomPassword();
            Assertions.assertEquals(AuthConstants.INITIAL_PASSWORD_LENGTH, password.length(), "length");
            Assertions.assertTrue(password.matches(FOUR_CLASSES), "must contain upper/lower/digit/special: " + password);
            Assertions.assertNotEquals("12345678", password, "the historical fixed password must never come back");
            seen.add(password);
        }
        // 50 次里出现重复即说明不是随机的（16 位 65 字符字母表，碰撞概率可忽略）
        Assertions.assertEquals(50, seen.size(), "generateRandomPassword must be random, got duplicates");
    }

    @Test
    public void processWidePasswordIsStableAcrossThreads() throws InterruptedException {
        String first = AuthConstants.getRandomPassword();
        Assertions.assertTrue(first.matches(FOUR_CLASSES));
        Thread[] threads = new Thread[10];
        String[] seen = new String[10];
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> seen[idx] = AuthConstants.getRandomPassword());
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        for (String s : seen) {
            Assertions.assertEquals(first, s, "getRandomPassword must return one value per process");
        }
    }

    @Test
    public void onlyTheDocumentedFixedTestPasswordFieldHoldsTheKnownWeakValue() throws Exception {
        // 反射扫描：允许 FIXED_TEST_PASSWORD 这一个明确命名的字段等于历史弱口令（这是维护者要求保留的
        // 显式 opt-in 测试便利），但不允许任何其它字段（例如历史上的 DEFAULT_PASSWORD，或未来不小心
        // 加回来的同类常量）也持有这个值——那样会让"哪条路径在用它"重新变得不可审计。
        for (var field : AuthConstants.class.getDeclaredFields()) {
            if (field.getType() == String.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                Object value = field.get(null);
                if ("12345678".equals(value)) {
                    Assertions.assertEquals("FIXED_TEST_PASSWORD", field.getName(),
                            "unexpected field also holds the known weak password: " + field.getName());
                }
            }
        }
    }

    @Test
    public void fixedTestPasswordNeverLeaksIntoTheRandomPath() {
        // 显式 opt-in 常量的存在本身不能污染默认路径：两个生成方法永远不能返回它。
        Assertions.assertNotEquals(AuthConstants.FIXED_TEST_PASSWORD, AuthConstants.generateRandomPassword());
        Assertions.assertNotEquals(AuthConstants.FIXED_TEST_PASSWORD, AuthConstants.getRandomPassword());
    }
}
