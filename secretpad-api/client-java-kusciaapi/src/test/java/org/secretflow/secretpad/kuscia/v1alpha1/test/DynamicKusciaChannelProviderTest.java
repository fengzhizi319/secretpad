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

package org.secretflow.secretpad.kuscia.v1alpha1.test;

import org.secretflow.secretpad.common.dto.UserContextDTO;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.kuscia.v1alpha1.DynamicKusciaChannelProvider;
import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaModeEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaProtocolEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.mock.MockKusciaGrpcServer;
import org.secretflow.secretpad.kuscia.v1alpha1.model.DynamicKusciaGrpcConfig;
import org.secretflow.secretpad.kuscia.v1alpha1.model.KusciaGrpcConfig;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.secretflow.v1alpha1.kusciaapi.DomainServiceGrpc;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * DynamicKusciaChannelProvider 单元测试类
 * 
 * 测试目标: 验证动态 Kuscia gRPC 通道提供者的核心功能
 * - 通道的注册和注销机制
 * - gRPC stub 的动态获取
 * - 节点配置的线程安全管理
 * - 异常情况的正确处理
 *
 * @author yutu
 * @date 2024/06/14
 */
public class DynamicKusciaChannelProviderTest {

    /**
     * Kuscia gRPC 基础配置对象
     * 使用静态初始化块创建共享的配置实例,避免重复创建
     * 配置包含:
     * - 主机地址: 使用 Mock 服务器的 HOST
     * - 端口号: 使用 Mock 服务器的 PORT
     * - 协议类型: NOTLS (不使用 TLS 加密)
     * - 运行模式: P2P (点对点模式)
     * - 域ID: alice (标识当前节点)
     */
    private static final KusciaGrpcConfig config = new KusciaGrpcConfig();

    static {
        // 配置 Mock gRPC 服务器的连接信息
        config.setHost(MockKusciaGrpcServer.HOST);
        config.setPort(MockKusciaGrpcServer.PORT);
        // 设置通信协议为无加密模式(适用于测试环境)
        config.setProtocol(KusciaProtocolEnum.NOTLS);
        // 设置为 P2P 模式,区别于中心化和边缘模式
        config.setMode(KusciaModeEnum.P2P);
        // 设置当前节点的域ID为 "alice"
        config.setDomainId("alice");
    }

    /**
     * 测试前置准备方法
     * 在每个测试用例执行前自动调用,确保测试环境的独立性
     * 
     * 主要作用:
     * 1. 设置用户上下文,模拟已登录的用户环境
     * 2. 配置 ownerId 和 platformNodeId 为 "alice",与上面的 config.domainId 保持一致
     * 3. 确保每个测试都在相同的用户上下文中执行,避免测试间相互影响
     */
    @BeforeEach
    void setUp() {
        // 构建并设置基础用户上下文
        UserContext.setBaseUser(UserContextDTO.builder()
                .ownerId("alice")              // 所有者ID,用于权限控制和资源归属
                .platformNodeId("alice")       // 平台节点ID,标识当前操作的节点
                .build());
    }

    /**
     * 测试当前 Stub 获取功能 - DomainService gRPC 服务
     * 
     * 测试场景分解:
     * 
     * 第一阶段: 正常流程测试
     * 1. 创建 DynamicKusciaChannelProvider 实例
     * 2. 初始化动态配置,设置空的节点集合(线程安全的 CopyOnWriteArraySet)
     * 3. 注册 Kuscia 配置两次(测试幂等性 - 重复注册不应产生副作用)
     * 4. 设置当前操作节点为 "alice"
     * 5. 获取 DomainServiceBlockingStub 实例
     * 6. 验证 stub 能够正常创建(虽然调用会失败,因为传入 null 参数)
     * 
     * 第二阶段: 异常处理测试
     * 7. 验证各个 gRPC 方法在传入 null 参数时抛出 StatusRuntimeException
     *    - queryDomain: 查询域信息
     *    - createDomain: 创建新域
     *    - updateDomain: 更新域信息
     *    - deleteDomain: 删除域
     *    - batchQueryDomain: 批量查询域
     * 
     * 第三阶段: 注销后行为测试
     * 8. 注销 Kuscia 配置
     * 9. 验证注销后再次获取 stub 会抛出 IllegalArgumentException
     *    (因为没有可用的节点配置)
     */
    @Test
    void testCurrentStubDomainServiceGrpc() {
        // ===== 第一阶段: 初始化和注册 =====
        
        // 创建被测试的动态通道提供者实例
        DynamicKusciaChannelProvider service = new DynamicKusciaChannelProvider();
        
        // 创建动态 gRPC 配置对象
        DynamicKusciaGrpcConfig dynamicKusciaGrpcConfig = new DynamicKusciaGrpcConfig();
        // 初始化空的节点集合,使用线程安全的 CopyOnWriteArraySet
        // CopyOnWriteArraySet 适合读多写少的并发场景,保证线程安全
        dynamicKusciaGrpcConfig.setNodes(new CopyOnWriteArraySet<>());
        // 将动态配置注入到服务中
        service.setDynamicKusciaGrpcConfig(dynamicKusciaGrpcConfig);
        
        // 注册 Kuscia 配置 - 第一次注册
        // 这会将 config 中的节点信息添加到 nodes 集合中
        service.registerKuscia(config);
        
        // 注册 Kuscia 配置 - 第二次注册(重复注册)
        // 测试目的: 验证注册的幂等性,重复注册同一配置不应导致错误或重复数据
        service.registerKuscia(config);
        
        // 设置当前要连接的节点ID
        // 这会告诉 provider 从 nodes 集合中选择哪个节点的配置来创建通道
        service.setNodeId("alice");
        
        // ===== 第二阶段: 获取 Stub 并验证基本功能 =====
        
        // 获取当前节点的 DomainService gRPC 阻塞式存根(stub)
        // currentStub 方法会:
        // 1. 根据 nodeId 查找对应的节点配置
        // 2. 创建或复用 gRPC Channel
        // 3. 基于 Channel 创建指定类型的 Stub 实例
        DomainServiceGrpc.DomainServiceBlockingStub domainServiceBlockingStub = 
            service.currentStub(DomainServiceGrpc.DomainServiceBlockingStub.class);
        
        // 验证 stub 的各个方法在传入 null 参数时的异常处理
        // 这些调用会连接到 Mock gRPC 服务器,但由于参数为 null,
        // gRPC 框架会抛出 StatusRuntimeException
        
        // 测试查询域接口 - 应抛出状态运行时异常
        Assertions.assertThrows(StatusRuntimeException.class, 
            () -> domainServiceBlockingStub.queryDomain(null));
        
        // 测试创建域接口 - 应抛出状态运行时异常
        Assertions.assertThrows(StatusRuntimeException.class, 
            () -> domainServiceBlockingStub.createDomain(null));
        
        // 测试更新域接口 - 应抛出状态运行时异常
        Assertions.assertThrows(StatusRuntimeException.class, 
            () -> domainServiceBlockingStub.updateDomain(null));
        
        // 测试删除域接口 - 应抛出状态运行时异常
        Assertions.assertThrows(StatusRuntimeException.class, 
            () -> domainServiceBlockingStub.deleteDomain(null));
        
        // 测试批量查询域接口 - 应抛出状态运行时异常
        Assertions.assertThrows(StatusRuntimeException.class, 
            () -> domainServiceBlockingStub.batchQueryDomain(null));
        
        // ===== 第三阶段: 注销配置并验证清理逻辑 =====
        
        // 注销 Kuscia 配置
        // 这会从 nodes 集合中移除对应的节点配置
        service.unRegisterKuscia(config);
        
        // 验证注销后的行为: 尝试获取 stub 应该失败
        // 因为节点配置已被移除,currentStub 方法找不到对应的配置
        // 预期抛出 IllegalArgumentException,提示配置不存在
        Assertions.assertThrows(IllegalArgumentException.class, 
            () -> service.currentStub(DomainServiceGrpc.DomainServiceBlockingStub.class));
    }
}