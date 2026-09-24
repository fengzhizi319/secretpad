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
package org.secretflow.secretpad.web.controller.p2p;

import org.secretflow.secretpad.common.dto.SecretPadResponse;
import org.secretflow.secretpad.common.dto.SyncDataDTO;
import org.secretflow.secretpad.common.errorcode.SystemErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.persistence.model.DataSyncConfig;
import org.secretflow.secretpad.service.sync.p2p.DataSyncConsumerTemplate;

import com.fasterxml.jackson.databind.JavaType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

/**
 * @author yutu
 * @date 2023/12/10
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1alpha1/data")
public class DataSyncController {

    private final DataSyncConsumerTemplate consumerTemplate;

    private final DataSyncConfig dataSyncConfig;

    /**
     * 安全整改（二次评审，见 docs/secretpad_auth.md §8）：{@code tableName} 此前直接喂给
     * {@link Class#forName}，作为对端节点/客户端完全可控的字段——没有任何允许列表。反射装载的类
     * 又立刻被当作 Jackson 反序列化的目标类型，等于给了对端"加载并反序列化任意一个类路径上存在
     * 的类"的能力，这是任意类反序列化链路的经典第一步。{@link #dataSyncConfig} 的
     * {@code data.sync}（{@code config/application.yaml}）本就是"哪些实体参与同步"的权威清单，
     * 这里把它当允许列表用，而不是另建一份可能与之走偏的副本。
     *
     * @param nodeId 请求来源节点（由 Kuscia 网关注入的 header，见 docs/secretpad_auth.md 现状清单 S6）
     * @param p      请求体原文，先按 {@link SyncDataDTO} 的裸类型解析出 {@code tableName}，
     *               确认其在允许列表内后，再按 {@code tableName} 对应的真实类型重新解析一次泛型字段
     * @return 同步处理结果
     */
    @PostMapping("sync")
    public SecretPadResponse<SyncDataDTO> sync(@RequestHeader("kuscia-origin-source") String nodeId, @RequestBody String p) throws ClassNotFoundException {
        SyncDataDTO syncDataDTO = JsonUtils.toJavaObject(p, SyncDataDTO.class);
        String id = syncDataDTO.getTableName();
        if (CollectionUtils.isEmpty(dataSyncConfig.getSync()) || !dataSyncConfig.getSync().contains(id)) {
            log.error("data sync tableName {} is not in the configured allow list, deny.", id);
            throw SecretpadException.of(SystemErrorCode.SYNC_ERROR, "tableName is not an allowed sync entity: " + id);
        }
        Class<?> cls = Class.forName(id);
        JavaType javaType = JsonUtils.makeJavaType(SyncDataDTO.class, cls);
        syncDataDTO = JsonUtils.toJavaObject(p, javaType);
        return SecretPadResponse.success(consumerTemplate.consumer(nodeId, syncDataDTO));
    }
}