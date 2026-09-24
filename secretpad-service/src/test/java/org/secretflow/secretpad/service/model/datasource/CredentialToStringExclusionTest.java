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
package org.secretflow.secretpad.service.model.datasource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 安全整改回归（docs/secretpad_auth.md §8，日志卫生）：MysqlDatasourceInfo/OdpsDatasourceInfo 带
 * {@code @ToString}，其 toString() 会被 LoggingAspect 打进 INFO 日志（若被作为裸对象直接日志打印，
 * 或未来被某个带 @ToString 的外层请求体持有）。这里锁定明文密码/密钥不会出现在 toString() 里。
 */
class CredentialToStringExclusionTest {

    private static final String SECRET_MYSQL_PASSWORD = "S3cretMysqlPwd!";
    private static final String SECRET_ODPS_ACCESS_KEY = "S3cretOdpsAccessKey!";

    @Test
    void mysqlDatasourceInfoToStringExcludesPassword() {
        MysqlDatasourceInfo info = MysqlDatasourceInfo.builder()
                .endpoint("127.0.0.1:3306")
                .user("root")
                .password(SECRET_MYSQL_PASSWORD)
                .database("db")
                .build();
        String s = info.toString();
        Assertions.assertFalse(s.contains(SECRET_MYSQL_PASSWORD), "toString() must not leak plaintext mysql password: " + s);
    }

    @Test
    void odpsDatasourceInfoToStringExcludesAccessKey() {
        OdpsDatasourceInfo info = OdpsDatasourceInfo.builder()
                .endpoint("https://127.0.0.1:8888")
                .accessId("ak")
                .accessKey(SECRET_ODPS_ACCESS_KEY)
                .project("proj")
                .build();
        String s = info.toString();
        Assertions.assertFalse(s.contains(SECRET_ODPS_ACCESS_KEY), "toString() must not leak plaintext odps accessKey: " + s);
    }
}
