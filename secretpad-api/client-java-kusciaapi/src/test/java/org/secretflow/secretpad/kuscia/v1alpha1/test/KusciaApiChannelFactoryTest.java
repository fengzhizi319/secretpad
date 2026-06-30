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

import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaModeEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaProtocolEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.factory.KusciaApiChannelFactory;
import org.secretflow.secretpad.kuscia.v1alpha1.factory.impl.GrpcKusciaApiChannelFactory;
import org.secretflow.secretpad.kuscia.v1alpha1.mock.MockKusciaGrpcServer;
import org.secretflow.secretpad.kuscia.v1alpha1.model.KusciaGrpcConfig;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kuscia API 通道工厂单元测试类
 * 
 * 测试目标: 验证 gRPC Kuscia API 通道工厂的基本功能
 * - Mock gRPC 服务器的启动和关闭
 * - 通道的创建和获取
 * - 资源的正确释放(shutdown)
 * 
 * 测试场景:
 * 1. 启动一个 Mock gRPC 服务器模拟真实的 Kuscia API 服务
 * 2. 使用工厂模式创建 gRPC 通道
 * 3. 验证通道能够成功创建
 * 4. 确保资源能够正确清理
 *
 * @author yutu
 * @date 2024/06/13
 */
@Slf4j
public class KusciaApiChannelFactoryTest {

    /**
     * Mock Kuscia gRPC 服务器实例
     * 用于模拟真实的 Kuscia API gRPC 服务端,避免依赖外部环境
     * 在测试期间提供可控的 gRPC 服务响应
     */
    private MockKusciaGrpcServer mockKusciaGrpcServer;

    /**
     * 测试前置准备方法
     * 在每个测试用例执行前自动调用
     * 
     * 主要作用:
     * 1. 创建 Mock gRPC 服务器实例
     * 2. 启动服务器,使其监听指定的端口,准备接收 gRPC 请求
     * 
     * 这样每个测试都有一个独立的 Mock 服务器环境,
     * 确保测试之间的隔离性和可重复性
     * 
     * @throws Exception 如果服务器启动失败则抛出异常
     */
    @BeforeEach
    void setUp() throws Exception {
        // 创建 Mock gRPC 服务器,模拟真实的 Kuscia API 服务
        mockKusciaGrpcServer = new MockKusciaGrpcServer();
        // 启动服务器,开始监听配置的端口
        // 服务器会在后台运行,等待客户端连接
        mockKusciaGrpcServer.start();
    }

    /**
     * 测试后置清理方法
     * 在每个测试用例执行后自动调用
     * 
     * 主要作用:
     * 1. 关闭 Mock gRPC 服务器
     * 2. 释放服务器占用的端口和网络资源
     * 3. 确保不会因为端口占用影响后续测试或其他应用
     * 
     * 这是重要的资源清理步骤,防止资源泄漏
     */
    @AfterEach
    void tearDown() {
        // 优雅地关闭 Mock gRPC 服务器
        // 这会停止接受新连接,并等待现有请求处理完成
        mockKusciaGrpcServer.shutdown();
    }

    /**
     * 测试 gRPC Kuscia API 通道工厂的基本功能
     * 
     * 测试流程分解:
     * 
     * 第一步: 配置准备
     * - 创建 KusciaGrpcConfig 配置对象
     * - 设置连接到 Mock 服务器的地址和端口
     * - 配置通信协议、运行模式和域ID
     * 
     * 第二步: 工厂创建
     * - 使用配置对象创建 GrpcKusciaApiChannelFactory 实例
     * - 工厂内部会根据配置初始化 gRPC Channel
     * 
     * 第三步: 通道获取
     * - 调用 getChannel() 获取 gRPC 通道
     * - 验证通道能够成功创建(无异常即表示成功)
     * 
     * 第四步: 资源清理
     * - 调用 shutdown() 关闭通道,释放资源
     * - 验证清理过程不会抛出异常
     * 
     * 测试要点:
     * - 验证工厂模式能够正确创建和管理 gRPC 通道
     * - 验证与 Mock 服务器的连接建立正常
     * - 验证资源生命周期管理的正确性(创建->使用->销毁)
     */
    @Test
    void test() {
        // ===== 第一步: 创建和配置 Kuscia gRPC 连接参数 =====
        
        // 创建 gRPC 配置对象,包含连接所需的所有参数
        KusciaGrpcConfig config = new KusciaGrpcConfig();
        
        // 设置 Mock gRPC 服务器的主机地址
        // 通常为 localhost 或 127.0.0.1
        config.setHost(MockKusciaGrpcServer.HOST);
        
        // 设置 Mock gRPC 服务器的监听端口
        // 端口号在 MockKusciaGrpcServer 中定义,确保客户端连接到正确的端口
        config.setPort(MockKusciaGrpcServer.PORT);
        
        // 设置通信协议为 NOTLS (No TLS)
        // 不使用 TLS 加密,适用于本地测试环境,简化配置
        // 生产环境通常会使用 TLS 或 MTLS 保证通信安全
        config.setProtocol(KusciaProtocolEnum.NOTLS);
        
        // 设置运行模式为 P2P (Peer-to-Peer)
        // P2P 模式表示节点之间直接通信,区别于中心化(Center)或边缘(Edge)模式
        config.setMode(KusciaModeEnum.P2P);
        
        // 设置当前节点的域ID为 "alice"
        // 域ID用于标识节点身份,在多方协作场景中区分不同的参与方
        config.setDomainId("alice");
        
        // ===== 第二步: 创建通道工厂实例 =====
        
        // 使用配置对象创建 gRPC Kuscia API 通道工厂
        // GrpcKusciaApiChannelFactory 实现了 KusciaApiChannelFactory 接口
        // 工厂内部会:
        // 1. 根据配置创建 ManagedChannel
        // 2. 配置通道的各种参数(超时、重试等)
        // 3. 管理通道的生命周期
        KusciaApiChannelFactory factory = new GrpcKusciaApiChannelFactory(config);
        
        // ===== 第三步: 获取 gRPC 通道 =====
        
        // 调用 getChannel() 获取 gRPC 通道
        // 这个方法会:
        // 1. 检查通道是否已经创建,如果已存在则直接返回(单例模式)
        // 2. 如果不存在,则根据配置创建新的 ManagedChannel
        // 3. 返回可用的 gRPC Channel 对象
        // 
        // 测试验证点: 如果配置正确且 Mock 服务器正常运行,
        // 此方法应该成功返回 Channel 而不抛出异常
        factory.getChannel();
        
        // ===== 第四步: 清理资源 =====
        
        // 调用 shutdown() 关闭通道,释放相关资源
        // 这个方法会:
        // 1. 优雅地关闭 gRPC Channel
        // 2. 等待正在进行的 RPC 调用完成
        // 3. 释放网络连接和内存资源
        // 
        // 测试验证点: 确保资源能够正确清理,不会造成资源泄漏
        // 如果 shutdown 过程中出现异常,测试将失败
        factory.shutdown();
    }
}