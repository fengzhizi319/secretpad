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
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @author beiwei
 * @date 2023/9/13
 */
@Setter
@Getter
@ToString
public class ResetNodeUserPwdRequest {


    /**
     * nodeId
     */
    @NotBlank
    private String nodeId;
    /**
     * User name
     */
    @Schema(description = "user name")
    @NotBlank
    private String name;

    /**
     * passwordHash
     */
    // 安全整改（二次评审，见 docs/secretpad_auth.md §8，日志卫生）：同 UserUpdatePwdRequest，
    // 本类带 @ToString，两个哈希字段是可直接用于登录/改密比对的凭据等价物，排除避免被
    // LoggingAspect 打进日志。
    @ToString.Exclude
    @NotBlank
    @Schema(description = "passwordHash")
    private String passwordHash;

    /**
     * User password
     */
    @ToString.Exclude
    @NotBlank
    @Schema(description = "user password")
    private String newPasswordHash;


}
