/*
 * Copyright 2023 Ant Group Co., Ltd.
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

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import org.hibernate.validator.constraints.Length;

/**
 * User update pwd request
 *
 * @author lihaixin
 * @date 2023/12/11
 */
@Data
public class UserUpdatePwdRequest {

    /**
     * User name
     */
    @Schema(description = "user name")
    private String name;

    /**
     * User  old password
     */
    // 安全整改（二次评审，见 docs/secretpad_auth.md §8，日志卫生）：@Data 会给这三个字段生成默认
    // toString()，而 LoggingAspect 对每个 controller 方法都会把参数 toString() 后打进 INFO 日志——
    // 本系统登录/改密走的是前端 SM3 预哈希、服务端直接比对哈希（P1-1 的既有设计），哈希本身即等价于
    // 凭据，泄露哈希等同于泄露密码，必须排除。
    @ToString.Exclude
    @NotBlank
    @Length(min = 8, message = "password length is greater than 8 ")
    @Schema(description = "user old password")
    private String oldPasswordHash;

    /**
     * User new password
     */
    @ToString.Exclude
    @NotBlank
    @Length(min = 8, message = "password length is greater than 8 ")
    @Schema(description = "user new password")
    private String newPasswordHash;

    /**
     * User confirm password
     */
    @ToString.Exclude
    @NotBlank
    @Length(min = 8, message = "password length is greater than 8 ")
    @Schema(description = "user confirm password")
    private String confirmPasswordHash;

}
