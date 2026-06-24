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
//mvn test -pl secretpad-manager -Dtest=DatasourceManagerTest -DfailIfNoTests=false
package org.secretflow.secretpad.manager.integration.node;

import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.kuscia.v1alpha1.service.impl.KusciaGrpcClientAdapter;
import org.secretflow.secretpad.manager.integration.datasource.DatasourceManager;
import org.secretflow.secretpad.manager.integration.model.DatasourceDTO;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.secretflow.v1alpha1.common.Common;
import org.secretflow.v1alpha1.kusciaapi.Domaindatasource;

/**
 * 数据源管理器单元测试类
 * <p>
 * 本测试类用于验证 DatasourceManager 与 Kuscia gRPC 客户端适配器的集成逻辑。
 * 通过 Mockito 框架模拟 KusciaGrpcClientAdapter 的行为，实现对数据源查询功能的隔离测试。
 * </p>
 *
 * @author lufeng
 * @date 2024/5/24
 */
@ExtendWith(MockitoExtension.class)
public class DatasourceManagerTest {

    /**
     * Kuscia gRPC 客户端适配器 Mock 对象
     * <p>
     * 使用 @Mock 注解创建模拟对象，用于替代真实的 gRPC 客户端调用。
     * 这样可以：
     * 1. 避免依赖外部 Kuscia 服务，实现单元测试的独立性
     * 2. 精确控制返回值，测试各种成功和失败场景
     * 3. 验证方法调用次数和参数传递的正确性
     * </p>
     */
    @Mock
    private KusciaGrpcClientAdapter kusciaGrpcClientAdapter;

    /**
     * 测试根据 ID 查询数据源的成功和失败场景
     * <p>
     * 本测试方法包含两个测试用例，按顺序执行：
     * 1. 成功场景：当 gRPC 返回状态码为 0（成功）时，验证查询正常执行
     * 2. 失败场景：当 gRPC 返回状态码为 -1（失败）时，验证抛出 SecretpadException 异常
     * </p>
     * 
     * <h3>执行流程说明：</h3>
     * <ol>
     *   <li><b>准备阶段（第 70-84 行）</b>：创建被测对象、构建测试数据和请求对象</li>
     *   <li><b>配置 Mock 成功响应（第 92-93 行）</b>：设置 gRPC 客户端返回成功状态</li>
     *   <li><b>执行成功场景测试（第 96 行）</b>：调用 findById 方法，验证正常流程</li>
     *   <li><b>重新配置 Mock 失败响应（第 101-102 行）</b>：覆盖之前的配置，返回失败状态</li>
     *   <li><b>执行失败场景测试（第 106 行）</b>：再次调用 findById 方法，验证异常处理</li>
     * </ol>
     * 
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>两个测试场景在同一个测试方法中顺序执行，后配置的 Mock 会覆盖先前的配置</li>
     *   <li>如果第一个场景失败（抛出异常），第二个场景将不会执行</li>
     *   <li>每个场景使用相同的请求参数，仅改变 Mock 返回值来模拟不同情况</li>
     * </ul>
     */
    @Test
    void findByIdSuccess() {

        // 创建被测试的数据源管理器实例，注入 Mock 的 gRPC 客户端适配器
        DatasourceManager datasourceManager = new DatasourceManager(kusciaGrpcClientAdapter);
        
        // 构建节点数据源 ID 对象，包含节点 ID 和数据源 ID
        DatasourceDTO.NodeDatasourceId nodeDatasourceId = DatasourceDTO.NodeDatasourceId.from("nodeId", "datasourceId");
        
        // 构建查询请求对象，设置目标域 ID 和数据源 ID
        Domaindatasource.QueryDomainDataSourceRequest queryDomainDataSourceRequest;
        queryDomainDataSourceRequest = Domaindatasource.QueryDomainDataSourceRequest.newBuilder()
                .setDomainId(nodeDatasourceId.getNodeId())
                .setDatasourceId(nodeDatasourceId.getDatasourceId())
                .build();
        
        // 设置平台类型为 CENTER 模式，影响后续的业务逻辑处理
        datasourceManager.setPlaformType("CENTER");
        
        // ========== 测试用例 1: 成功场景（步骤 2/5）==========
        // Mock 配置：当调用 queryDomainDataSource 方法并传入指定请求时，返回成功响应（状态码 0）
        // Mockito.when().thenReturn() 的作用：
        // - 拦截对 mock 对象的方法调用
        // - 匹配特定的方法参数（queryDomainDataSourceRequest）
        // - 返回预设的响应对象，而非真正调用 gRPC 服务
        Mockito.when(kusciaGrpcClientAdapter.queryDomainDataSource(queryDomainDataSourceRequest))
                .thenReturn(buildQueryDomainDatasourceResponse(0));
        
        // 执行查询操作（步骤 3/5）：由于返回状态码为 0，应该正常完成不抛异常
        datasourceManager.findById(nodeDatasourceId);

        // ========== 测试用例 2: 失败场景（步骤 4/5）==========
        // 重新配置 Mock：相同的请求参数，但返回失败响应（状态码 -1）
        // 注意：后配置的 when-thenReturn 会覆盖之前的配置
        Mockito.when(kusciaGrpcClientAdapter.queryDomainDataSource(queryDomainDataSourceRequest))
                .thenReturn(buildQueryDomainDatasourceResponse(-1));
        
        // 验证当 gRPC 返回错误状态码时，系统正确抛出 SecretpadException 异常（步骤 5/5）
        // Assertions.assertThrows 确保异常被正确抛出，符合预期的错误处理逻辑
        Assertions.assertThrows(SecretpadException.class, () -> datasourceManager.findById(nodeDatasourceId));
    }

    /**
     * 构建查询数据源的 gRPC 响应对象
     * <p>
     * 此方法用于创建模拟的 gRPC 响应，支持自定义状态码以测试不同的业务场景。
     * 响应中包含完整的数据源信息，包括：
     * - 域 ID (domainId): 标识数据所属的域
     * - 数据源 ID (datasourceId): 唯一标识数据源
     * - 类型 (type): 数据源类型，此处为 OSS（对象存储服务）
     * - 名称 (name): 数据源的显示名称
     * </p>
     *
     * @param code 响应状态码，0 表示成功，负数表示失败
     * @return 包含状态信息和数据源数据的查询响应对象
     */
    private Domaindatasource.QueryDomainDataSourceResponse buildQueryDomainDatasourceResponse(Integer code) {
        return Domaindatasource.QueryDomainDataSourceResponse.newBuilder()
                // 设置响应状态，包含状态码用于判断请求是否成功
                .setStatus(Common.Status.newBuilder().setCode(code).build())
                // 设置数据源详细信息
                .setData(
                        Domaindatasource.DomainDataSource.newBuilder()
                                .setDomainId("domainId")      // 域 ID
                                .setDatasourceId("datasourceId") // 数据源 ID
                                .setType("OSS")                 // 数据源类型：对象存储
                                .setName("name")                // 数据源名称
                                .setDatasourceId("datasourceId") // 再次设置数据源 ID（可能为冗余设置）
                                .build()
                )
                .build();
    }

}
