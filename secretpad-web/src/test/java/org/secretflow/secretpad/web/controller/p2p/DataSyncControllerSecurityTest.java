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
package org.secretflow.secretpad.web.controller.p2p;

import org.secretflow.secretpad.common.dto.SyncDataDTO;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.persistence.model.DataSyncConfig;
import org.secretflow.secretpad.service.sync.p2p.DataSyncConsumerTemplate;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 安全整改守卫（docs/secretpad_auth.md §8，二次评审）：{@code DataSyncController.sync()} 此前把
 * 请求体里对端完全可控的 {@code tableName} 直接喂给 {@link Class#forName}，没有任何允许列表——
 * 反射装载的类立刻被当作 Jackson 反序列化目标类型，等价于给了对端"加载并反序列化任意类"的能力。
 * 本用例钉住修复后的允许列表：只有出现在 {@code data.sync} 配置里的类名才能通过。
 *
 * @author claude
 * @date 2026/09/24
 */
class DataSyncControllerSecurityTest {

    @Test
    void classOutsideAllowListIsRejected() {
        DataSyncConsumerTemplate consumerTemplate = Mockito.mock(DataSyncConsumerTemplate.class);
        DataSyncConfig config = new DataSyncConfig();
        config.setSync(List.of("org.secretflow.secretpad.persistence.entity.ProjectDO"));
        DataSyncController controller = new DataSyncController(consumerTemplate, config);

        // 攻击者把 tableName 指向一个允许列表之外、但类路径上确实存在的类——修复前这里会
        // 被无条件 Class.forName 装载并当作反序列化目标类型。
        String body = "{\"tableName\":\"java.lang.ProcessBuilder\",\"lastUpdateTime\":\"1\",\"action\":\"UPDATE\",\"data\":{}}";

        assertThrows(SecretpadException.class, () -> controller.sync("alice", body));
        Mockito.verify(consumerTemplate, Mockito.never()).consumer(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void classInAllowListIsAccepted() {
        DataSyncConsumerTemplate consumerTemplate = Mockito.mock(DataSyncConsumerTemplate.class);
        Mockito.when(consumerTemplate.consumer(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(SyncDataDTO.builder().tableName("java.lang.String").build());
        DataSyncConfig config = new DataSyncConfig();
        config.setSync(List.of("java.lang.String"));
        DataSyncController controller = new DataSyncController(consumerTemplate, config);

        String body = "{\"tableName\":\"java.lang.String\",\"lastUpdateTime\":\"1\",\"action\":\"UPDATE\",\"data\":\"hello\"}";

        assertDoesNotThrow(() -> controller.sync("alice", body));
        Mockito.verify(consumerTemplate, Mockito.times(1)).consumer(ArgumentMatchers.eq("alice"), ArgumentMatchers.any());
    }

    @Test
    void emptyAllowListRejectsEverything() {
        DataSyncConsumerTemplate consumerTemplate = Mockito.mock(DataSyncConsumerTemplate.class);
        DataSyncConfig config = new DataSyncConfig();
        config.setSync(List.of());
        DataSyncController controller = new DataSyncController(consumerTemplate, config);

        String body = "{\"tableName\":\"java.lang.String\",\"lastUpdateTime\":\"1\",\"action\":\"UPDATE\",\"data\":\"hello\"}";

        assertThrows(SecretpadException.class, () -> controller.sync("alice", body));
    }
}
