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

import org.secretflow.secretpad.manager.integration.datasource.odps.OdpsConfig;
import org.secretflow.secretpad.service.constant.Constants;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * @author yutu
 * @date 2024/07/23
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class OdpsDatasourceInfo extends DataSourceInfo {

    @NotBlank
    @Pattern(regexp = Constants.IP_PREFIX_REG, message = "The endpoint is invalid, it must be a standard top-level domain or IP address + port, such as 'https://127.0.0.1:8888")
    private String endpoint;

    @NotBlank(message = "odps accessId cannot be null or empty")
    private String accessId;

    // 安全整改（二次评审，见 docs/secretpad_auth.md §8，日志卫生）：同 MysqlDatasourceInfo.password，
    // accessKey 是 ODPS 的明文密钥，本类 @ToString 默认会把它打进日志，防御性排除。
    @ToString.Exclude
    @NotBlank(message = "odps accessKey cannot be null or empty")
    private String accessKey;

    @NotBlank(message = "odps project cannot be null or empty")
    private String project;

    public OdpsConfig toOdpsConfig() {
        return OdpsConfig.builder()
                .endpoint(endpoint)
                .accessId(accessId)
                .accessKey(accessKey)
                .project(project)
                .build();
    }
}
