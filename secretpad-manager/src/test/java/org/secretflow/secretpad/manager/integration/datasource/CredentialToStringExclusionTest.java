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
package org.secretflow.secretpad.manager.integration.datasource;

import org.secretflow.secretpad.manager.integration.datasource.mysql.MysqlConfig;
import org.secretflow.secretpad.manager.integration.datasource.odps.OdpsConfig;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 安全整改回归（docs/secretpad_auth.md §8，日志卫生）：manager 层真正用来建连接的 MysqlConfig/
 * OdpsConfig 带 {@code @ToString}，锁定明文密码/密钥不会出现在 toString() 里，防止未来任何调试日志
 * （如 log.debug("connecting with {}", mysqlConfig)）意外泄露。
 */
class CredentialToStringExclusionTest {

    private static final String SECRET_MYSQL_PASSWORD = "S3cretMysqlPwd!";
    private static final String SECRET_ODPS_ACCESS_KEY = "S3cretOdpsAccessKey!";

    @Test
    void mysqlConfigToStringExcludesPassword() {
        MysqlConfig config = MysqlConfig.builder()
                .endpoint("127.0.0.1:3306")
                .user("root")
                .password(SECRET_MYSQL_PASSWORD)
                .database("db")
                .build();
        String s = config.toString();
        Assertions.assertFalse(s.contains(SECRET_MYSQL_PASSWORD), "toString() must not leak plaintext mysql password: " + s);
    }

    @Test
    void odpsConfigToStringExcludesAccessKey() {
        OdpsConfig config = OdpsConfig.builder()
                .accessId("ak")
                .accessKey(SECRET_ODPS_ACCESS_KEY)
                .project("proj")
                .endpoint("https://127.0.0.1:8888")
                .build();
        String s = config.toString();
        Assertions.assertFalse(s.contains(SECRET_ODPS_ACCESS_KEY), "toString() must not leak plaintext odps accessKey: " + s);
    }
}
