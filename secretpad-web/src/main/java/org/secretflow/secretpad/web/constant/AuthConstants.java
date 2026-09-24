/*
 * Copyright 2023 Ant Group Co., Ltd.
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

package org.secretflow.secretpad.web.constant;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authorization Constants
 * <p>
 * 安全整改（docs/secretpad_auth.md P0-1）：
 * 本类此前存在固定弱口令常量 {@code DEFAULT_PASSWORD = "12345678"}，且 {@link #getRandomPassword()} /
 * {@link #generateRandomPassword()} 直接返回该固定值——任何拿到镜像的人都知道管理员口令。
 * 现改为：默认路径不存在任何固定口令，初始口令由 {@link SecureRandom} 生成、长度 16、四类字符各至少一个，
 * 只在 {@code DbDataInit} 首次建户时写入 0600 文件一次，不进日志。
 * <p>
 * 应维护者要求（本地开发/自动化测试的登录便利性），保留了 {@link #FIXED_TEST_PASSWORD} 这一条**显式 opt-in**
 * 的例外：默认关闭，只有 {@code secretpad.auth.fixed-test-password-enabled=true} 时才会被
 * {@code DbDataInit} 使用，且只在 {@code application-dev.yaml} / {@code application-test.yaml} 里打开——
 * 生产配置 {@code application.yaml} 及 edge/p2p 部署 profile 都没有这一行，缺省即关闭。
 * 启用时会打印醒目的 WARN 日志，不会静默生效。
 *
 * @author : xiaonan.fhn
 * @date 2023/05/26
 */
public class AuthConstants {
    public static final String TOKEN_NAME = "User-Token";
    public static final String USER_NAME = "admin";

    /**
     * 【仅本地开发 / 自动化测试】固定测试口令，与整改前的默认口令数值相同，方便沿用现有的联调习惯与
     * 文档（如 {@code docs/development/test-guides/cipher12345678.md}）。
     * <p>
     * <b>绝不能作为默认路径生效</b>：是否使用由 {@code DbDataInit} 读取
     * {@code secretpad.auth.fixed-test-password-enabled}（默认 {@code false}）决定；
     * 生产配置文件里没有这一项，保持默认关闭。
     */
    public static final String FIXED_TEST_PASSWORD = "12345678";

    /**
     * 初始口令长度。等保三级对管理员口令的下限是 8，这里取 16：初始口令只用一次（首登即改），长一点没有代价。
     */
    public static final int INITIAL_PASSWORD_LENGTH = 16;

    private static final String UPPER = "ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SPECIAL = "@$!%*?&";
    private static final String ALL = UPPER + LOWER + DIGITS + SPECIAL;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private volatile static String RANDOM_PASSWORD;

    /**
     * get tokenName
     *
     * @param platformType   platform type
     * @param platformNodeId platform node id
     * @return {@link String }
     */
    @Deprecated
    public static String getTokenName(String platformType, String platformNodeId) {
        return TOKEN_NAME + "_" + platformType + "_" + platformNodeId;
    }

    /**
     * 进程内只生成一次的随机初始口令（双检锁），同一进程多处读取拿到同一个值。
     */
    public static String getRandomPassword() {
        if (RANDOM_PASSWORD == null) {
            synchronized (AuthConstants.class) {
                if (RANDOM_PASSWORD == null) {
                    RANDOM_PASSWORD = generateRandomPassword();
                }
            }
        }
        return RANDOM_PASSWORD;
    }

    /**
     * 生成一个 16 位、四类字符各至少一个的随机口令。
     * <p>
     * 用"先每类取一个、再随机补齐、最后洗牌"的构造法保证四类都在，而不是"随机生成后用正则检查、不合格就递归重来"——
     * 后者是历史实现，平均要重试多次，且递归深度不可控。
     */
    public static String generateRandomPassword() {
        List<Character> chars = new ArrayList<>(INITIAL_PASSWORD_LENGTH);
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGITS));
        chars.add(pick(SPECIAL));
        while (chars.size() < INITIAL_PASSWORD_LENGTH) {
            chars.add(pick(ALL));
        }
        Collections.shuffle(chars, SECURE_RANDOM);
        StringBuilder password = new StringBuilder(INITIAL_PASSWORD_LENGTH);
        for (Character c : chars) {
            password.append(c);
        }
        return password.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length()));
    }
}
