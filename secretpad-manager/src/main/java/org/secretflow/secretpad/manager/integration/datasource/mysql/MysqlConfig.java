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
package org.secretflow.secretpad.manager.integration.datasource.mysql;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * @author lufeng
 * @date 2024/8/20
 */
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MysqlConfig implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "mysql endpoint cannot be null or empty")
    private String endpoint;

    @NotNull(message = "mysql user cannot be null or empty")
    private String user;

    // 安全整改（二次评审，见 docs/secretpad_auth.md §8，日志卫生）：同上层 MysqlDatasourceInfo，
    // 这里是 manager 层真正拿去建连接的配置对象，同样防御性排除，避免未来任何调试日志泄露明文密码。
    @ToString.Exclude
    @NotNull(message = "mysql password cannot be null or empty")
    private String password;

    @NotNull(message = "mysql database cannot be null or empty")
    private String database;

    public void validate() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<MysqlConfig>> violations = validator.validate(this);
        for (ConstraintViolation<MysqlConfig> violation : violations) {
            throw new IllegalArgumentException("Invalid MySQLConfig: " + violation.getMessage());
        }
    }
}
