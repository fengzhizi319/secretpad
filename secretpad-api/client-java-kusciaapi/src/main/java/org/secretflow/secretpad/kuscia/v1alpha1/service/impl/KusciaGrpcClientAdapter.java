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

package org.secretflow.secretpad.kuscia.v1alpha1.service.impl;

import org.secretflow.secretpad.kuscia.v1alpha1.DynamicKusciaChannelProvider;
import org.secretflow.secretpad.kuscia.v1alpha1.service.*;

import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.secretflow.v1alpha1.kusciaapi.*;
import org.springframework.stereotype.Service;

import java.util.Iterator;

/**
 * kuscia grpc api service
 *
 * @author yutu
 * @date 2024/06/17
 */
@Setter
@Getter
@Slf4j
@Service
public class KusciaGrpcClientAdapter implements
        DomainService, DomainRouteService, DomainDataService, DomainDataSourceService, DomainDataGrantService
        , HealthService, KusciaJobService, ServingService, CertificateService {

    @Resource
    private DynamicKusciaChannelProvider dynamicKusciaChannelProvider;

    /**
     *
     */
    public boolean isDomainRegistered(String domainId) {
        if (StringUtils.isEmpty(domainId)) {
            return false;
        }
        return dynamicKusciaChannelProvider.isChannelExist(domainId);
    }

    /**
     *
     */
    public void unregisterDomain(String domainId) {
        dynamicKusciaChannelProvider.unRegisterKuscia(domainId);
    }


    @Override
    public Certificate.GenerateKeyCertsResponse generateKeyCerts(Certificate.GenerateKeyCertsRequest request) {
        return dynamicKusciaChannelProvider.currentStub(CertificateServiceGrpc.CertificateServiceBlockingStub.class).generateKeyCerts(request);
    }

    @Override
    public Certificate.GenerateKeyCertsResponse generateKeyCerts(Certificate.GenerateKeyCertsRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, CertificateServiceGrpc.CertificateServiceBlockingStub.class).generateKeyCerts(request);
    }

    @Override
    public Domaindatagrant.CreateDomainDataGrantResponse createDomainDataGrant(Domaindatagrant.CreateDomainDataGrantRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).createDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.UpdateDomainDataGrantResponse updateDomainDataGrant(Domaindatagrant.UpdateDomainDataGrantRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).updateDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.DeleteDomainDataGrantResponse deleteDomainDataGrant(Domaindatagrant.DeleteDomainDataGrantRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).deleteDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.QueryDomainDataGrantResponse queryDomainDataGrant(Domaindatagrant.QueryDomainDataGrantRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).queryDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.BatchQueryDomainDataGrantResponse batchQueryDomainDataGrant(Domaindatagrant.BatchQueryDomainDataGrantRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).batchQueryDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.CreateDomainDataGrantResponse createDomainDataGrant(Domaindatagrant.CreateDomainDataGrantRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).createDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.UpdateDomainDataGrantResponse updateDomainDataGrant(Domaindatagrant.UpdateDomainDataGrantRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).updateDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.DeleteDomainDataGrantResponse deleteDomainDataGrant(Domaindatagrant.DeleteDomainDataGrantRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).deleteDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.QueryDomainDataGrantResponse queryDomainDataGrant(Domaindatagrant.QueryDomainDataGrantRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).queryDomainDataGrant(request);
    }

    @Override
    public Domaindatagrant.BatchQueryDomainDataGrantResponse batchQueryDomainDataGrant(Domaindatagrant.BatchQueryDomainDataGrantRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class).batchQueryDomainDataGrant(request);
    }

    @Override
    public Domaindata.CreateDomainDataResponse createDomainData(Domaindata.CreateDomainDataRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).createDomainData(request);
    }

    @Override
    public Domaindata.UpdateDomainDataResponse updateDomainData(Domaindata.UpdateDomainDataRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).updateDomainData(request);
    }

    @Override
    public Domaindata.DeleteDomainDataResponse deleteDomainData(Domaindata.DeleteDomainDataRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).deleteDomainData(request);
    }

    @Override
    public Domaindata.QueryDomainDataResponse queryDomainData(Domaindata.QueryDomainDataRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).queryDomainData(request);
    }

    @Override
    public Domaindata.BatchQueryDomainDataResponse batchQueryDomainData(Domaindata.BatchQueryDomainDataRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).batchQueryDomainData(request);
    }

    @Override
    public Domaindata.ListDomainDataResponse listDomainData(Domaindata.ListDomainDataRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).listDomainData(request);
    }

    @Override
    public Domaindata.CreateDomainDataResponse createDomainData(Domaindata.CreateDomainDataRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).createDomainData(request);
    }

    @Override
    public Domaindata.UpdateDomainDataResponse updateDomainData(Domaindata.UpdateDomainDataRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).updateDomainData(request);
    }

    @Override
    public Domaindata.DeleteDomainDataResponse deleteDomainData(Domaindata.DeleteDomainDataRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).deleteDomainData(request);
    }

    @Override
    public Domaindata.QueryDomainDataResponse queryDomainData(Domaindata.QueryDomainDataRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).queryDomainData(request);
    }

    @Override
    public Domaindata.BatchQueryDomainDataResponse batchQueryDomainData(Domaindata.BatchQueryDomainDataRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).batchQueryDomainData(request);
    }

    @Override
    public Domaindata.ListDomainDataResponse listDomainData(Domaindata.ListDomainDataRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataServiceGrpc.DomainDataServiceBlockingStub.class).listDomainData(request);
    }

    @Override
    public Domaindatasource.CreateDomainDataSourceResponse createDomainDataSource(Domaindatasource.CreateDomainDataSourceRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).createDomainDataSource(request);
    }

    @Override
    public Domaindatasource.UpdateDomainDataSourceResponse updateDomainDataSource(Domaindatasource.UpdateDomainDataSourceRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).updateDomainDataSource(request);
    }

    @Override
    public Domaindatasource.DeleteDomainDataSourceResponse deleteDomainDataSource(Domaindatasource.DeleteDomainDataSourceRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).deleteDomainDataSource(request);
    }

    @Override
    public Domaindatasource.QueryDomainDataSourceResponse queryDomainDataSource(Domaindatasource.QueryDomainDataSourceRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).queryDomainDataSource(request);
    }

    @Override
    public Domaindatasource.BatchQueryDomainDataSourceResponse batchQueryDomainDataSource(Domaindatasource.BatchQueryDomainDataSourceRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).batchQueryDomainDataSource(request);
    }

    @Override
    public Domaindatasource.ListDomainDataSourceResponse listDomainDataSource(Domaindatasource.ListDomainDataSourceRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).listDomainDataSource(request);
    }

    @Override
    public Domaindatasource.CreateDomainDataSourceResponse createDomainDataSource(Domaindatasource.CreateDomainDataSourceRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).createDomainDataSource(request);
    }

    @Override
    public Domaindatasource.UpdateDomainDataSourceResponse updateDomainDataSource(Domaindatasource.UpdateDomainDataSourceRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).updateDomainDataSource(request);
    }

    @Override
    public Domaindatasource.DeleteDomainDataSourceResponse deleteDomainDataSource(Domaindatasource.DeleteDomainDataSourceRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).deleteDomainDataSource(request);
    }

    @Override
    public Domaindatasource.QueryDomainDataSourceResponse queryDomainDataSource(Domaindatasource.QueryDomainDataSourceRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).queryDomainDataSource(request);
    }

    @Override
    public Domaindatasource.BatchQueryDomainDataSourceResponse batchQueryDomainDataSource(Domaindatasource.BatchQueryDomainDataSourceRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).batchQueryDomainDataSource(request);
    }

    @Override
    public Domaindatasource.ListDomainDataSourceResponse listDomainDataSource(Domaindatasource.ListDomainDataSourceRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class).listDomainDataSource(request);
    }

    @Override
    public DomainRoute.CreateDomainRouteResponse createDomainRoute(DomainRoute.CreateDomainRouteRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class).createDomainRoute(request);
    }

    @Override
    public DomainRoute.DeleteDomainRouteResponse deleteDomainRoute(DomainRoute.DeleteDomainRouteRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class).deleteDomainRoute(request);
    }

    @Override
    public DomainRoute.QueryDomainRouteResponse queryDomainRoute(DomainRoute.QueryDomainRouteRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class).queryDomainRoute(request);
    }

    @Override
    public DomainRoute.BatchQueryDomainRouteStatusResponse batchQueryDomainRouteStatus(DomainRoute.BatchQueryDomainRouteStatusRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class).batchQueryDomainRouteStatus(request);
    }

    @Override
    public DomainRoute.CreateDomainRouteResponse createDomainRoute(DomainRoute.CreateDomainRouteRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class).createDomainRoute(request);
    }

    @Override
    public DomainRoute.DeleteDomainRouteResponse deleteDomainRoute(DomainRoute.DeleteDomainRouteRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class).deleteDomainRoute(request);
    }

    @Override
    public DomainRoute.QueryDomainRouteResponse queryDomainRoute(DomainRoute.QueryDomainRouteRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class).queryDomainRoute(request);
    }

    @Override
    public DomainRoute.BatchQueryDomainRouteStatusResponse batchQueryDomainRouteStatus(DomainRoute.BatchQueryDomainRouteStatusRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class).batchQueryDomainRouteStatus(request);
    }

    // ========================================================================
    // DomainService 接口实现 - 域管理服务
    // ========================================================================
    
    /**
     * 创建域（使用默认 nodeId）
     * <p>
     * 【功能说明】
     * 在 Kuscia 系统中注册一个新的参与方（域），例如 Alice、Bob 等节点。
     * 这是隐私计算网络中节点加入的第一步，创建后才能进行数据管理和任务执行。
     * </p>
     * <p>
     * 【实现逻辑详解】
     * 该方法通过以下 4 个步骤完成域创建：
     * </p>
     * <pre>
     * 步骤 1: 获取当前节点的 gRPC Stub
     *   ├─ 调用 dynamicKusciaChannelProvider.currentStub()
     *   ├─ 内部使用配置的 nodeId（如 "alice"）作为目标域
     *   └─ 从 CHANNEL_FACTORIES 中获取对应的 ManagedChannel
     * 
     * 步骤 2: 创建 DomainServiceBlockingStub
     *   ├─ 调用 DomainServiceGrpc.newBlockingStub(channel)
     *   ├─ 设置超时时间：5000ms（BLOCKING_TIMEOUT_MILLISECOND）
     *   └─ 返回同步阻塞类型的 Stub
     * 
     * 步骤 3: 发起 gRPC Unary RPC 调用
     *   ├─ 序列化 CreateDomainRequest 为 Protobuf 二进制格式
     *   ├─ 通过 HTTP/2 发送请求到 Kuscia API 服务端
     *   ├─ 拦截器链处理：
     *   │   ├─ TokenAuthClientInterceptor: 添加认证 Token 到 Header
     *   │   └─ KusciaGrpcLoggingInterceptor: 记录请求日志
     *   └─ 等待服务端响应（最多等待 5 秒）
     * 
     * 步骤 4: 处理响应并返回
     *   ├─ 接收 CreateDomainResponse
     *   ├─ 反序列化 Protobuf 二进制数据为 Java 对象
     *   ├─ 检查 status.code：
     *   │   ├─ code=0：创建成功，返回响应
     *   │   └─ code!=0：创建失败，抛出 StatusRuntimeException
     *   └─ 返回给调用方
     * </pre>
     * <p>
     * 【调用链路】
     * KusciaGrpcClientAdapter.createDomain(request)
     *   ↓
     * DynamicKusciaChannelProvider.currentStub(DomainServiceBlockingStub.class)
     *   ↓
     * DynamicKusciaChannelProvider.createStub(nodeId, DomainServiceBlockingStub.class)
     *   ↓
     * 从 CHANNEL_FACTORIES.get(nodeId) 获取 ManagedChannel
     *   ↓
     * DomainServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5000, MILLISECONDS)
     *   ↓
     * stub.createDomain(request) → HTTP/2 Request → Kuscia API Server
     *   ↓
     * 返回 CreateDomainResponse
     * </p>
     * <p>
     * 【使用示例】
     * <pre>{@code
     * // 1. 构建创建域请求
     * CreateDomainRequest request = CreateDomainRequest.newBuilder()
     *     .setDomainId("alice")                    // 域 ID（唯一标识）
     *     .setRole("partner")                      // 角色：参与方
     *     .setCert(certPemContent)                 // TLS 证书（PEM 格式）
     *     .setMasterDomainId("kuscia-master")      // 主域 ID（中心化模式）
     *     .setAuthCenter(AuthCenter.newBuilder()
     *         .setAuthenticationType("Token")      // 认证类型
     *         .setTokenGenMethod("UID-RSA-GEN")    // 令牌生成方法
     *         .build())
     *     .build();
     * 
     * // 2. 调用创建域接口
     * CreateDomainResponse response = kusciaClient.createDomain(request);
     * 
     * // 3. 检查响应状态
     * if (response.getStatus().getCode() == 0) {
     *     log.info("Domain 'alice' created successfully");
     * } else {
     *     log.error("Failed to create domain: {}", response.getStatus().getMessage());
     * }
     * }</pre>
     * </p>
     * <p>
     * 【注意事项】
     * 1. 幂等性：如果 domain_id 已存在，服务端会返回错误（code != 0）
     * 2. 证书要求：如果使用 TLS/mTLS 协议，cert 字段必须提供有效的 PEM 证书
     * 3. 超时控制：默认超时 5 秒，超时后抛出 DeadlineExceededException
     * 4. 线程安全：BlockingStub 是线程安全的，可以并发调用
     * 5. 连接复用：多个请求共享同一个 ManagedChannel，无需每次创建连接
     * </p>
     * <p>
     * 【错误处理】
     * 常见错误码及原因：
     * - code=400: 参数验证失败（如 domain_id 为空、格式错误）
     * - code=409: 域已存在（domain_id 冲突）
     * - code=401: 认证失败（Token 无效或过期）
     * - code=14: 服务不可用（Kuscia API 未启动或网络不通）
     * - code=4: 超时（Deadline exceeded，超过 5 秒未响应）
     * </p>
     *
     * @param request 创建域请求，包含 domain_id、role、cert 等信息
     * @return CreateDomainResponse 创建结果，包含 status（操作状态）
     * @throws io.grpc.StatusRuntimeException gRPC 调用失败时抛出（如超时、认证失败）
     * @see DomainOuterClass.CreateDomainRequest 请求消息定义
     * @see DomainOuterClass.CreateDomainResponse 响应消息定义
     * @see DynamicKusciaChannelProvider#currentStub(Class) 获取 Stub 的方法
     */
    @Override
    public DomainOuterClass.CreateDomainResponse createDomain(DomainOuterClass.CreateDomainRequest request) {
        // 第 1 步：获取当前节点的 DomainServiceBlockingStub
        // - currentStub() 内部使用配置的 nodeId 作为目标域
        // - 从 CHANNEL_FACTORIES 中获取对应的 gRPC Channel
        // - 创建 BlockingStub 并设置 5000ms 超时
        return dynamicKusciaChannelProvider
                .currentStub(DomainServiceGrpc.DomainServiceBlockingStub.class)
                // 第 2 步：发起 gRPC Unary RPC 调用
                // - 序列化 request 为 Protobuf 二进制格式
                // - 通过 HTTP/2 发送到 Kuscia API 服务端
                // - 等待响应（最多 5 秒）
                // - 反序列化响应为 CreateDomainResponse
                .createDomain(request);
    }

    @Override
    public DomainOuterClass.UpdateDomainResponse updateDomain(DomainOuterClass.UpdateDomainRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainServiceGrpc.DomainServiceBlockingStub.class).updateDomain(request);
    }

    @Override
    public DomainOuterClass.DeleteDomainResponse deleteDomain(DomainOuterClass.DeleteDomainRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainServiceGrpc.DomainServiceBlockingStub.class).deleteDomain(request);
    }

    @Override
    public DomainOuterClass.QueryDomainResponse queryDomain(DomainOuterClass.QueryDomainRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainServiceGrpc.DomainServiceBlockingStub.class).queryDomain(request);
    }

    @Override
    public DomainOuterClass.BatchQueryDomainResponse batchQueryDomain(DomainOuterClass.BatchQueryDomainRequest request) {
        return dynamicKusciaChannelProvider.currentStub(DomainServiceGrpc.DomainServiceBlockingStub.class).batchQueryDomain(request);
    }

    /**
     * 创建域（指定 domainId）
     * <p>
     * 【功能说明】
     * 与 {@link #createDomain(CreateDomainRequest)} 功能相同，但可以显式指定目标域 ID。
     * 适用于多节点管理场景，可以在一个客户端中操作不同的 Kuscia 节点。
     * </p>
     * <p>
     * 【与重载方法的区别】
     * - createDomain(request): 使用配置的默认 nodeId（从 secretpad.node-id 读取）
     * - createDomain(request, domainId): 使用传入的 domainId 参数
     * </p>
     * <p>
     * 【使用场景】
     * 1. 中心化管理：SecretPad 作为管理中心，需要操作多个边缘节点
     * 2. 跨域操作：在一个请求中创建多个不同域的域信息
     * 3. 动态路由：根据业务逻辑动态选择目标节点
     * </p>
     * <p>
     * 【使用示例】
     * <pre>{@code
     * // 场景 1: 在 Alice 节点创建域
     * CreateDomainRequest aliceRequest = CreateDomainRequest.newBuilder()
     *     .setDomainId("alice")
     *     .setRole("partner")
     *     .build();
     * kusciaClient.createDomain(aliceRequest, "alice");
     * 
     * // 场景 2: 在 Bob 节点创建域
     * CreateDomainRequest bobRequest = CreateDomainRequest.newBuilder()
     *     .setDomainId("bob")
     *     .setRole("partner")
     *     .build();
     * kusciaClient.createDomain(bobRequest, "bob");
     * 
     * // 场景 3: 批量创建多个域
     * String[] nodes = {"alice", "bob", "tee"};
     * for (String nodeId : nodes) {
     *     CreateDomainRequest request = CreateDomainRequest.newBuilder()
     *         .setDomainId(nodeId)
     *         .setRole("partner")
     *         .build();
     *     try {
     *         kusciaClient.createDomain(request, nodeId);
     *         log.info("Created domain: {}", nodeId);
     *     } catch (StatusRuntimeException e) {
     *         log.error("Failed to create domain {}: {}", nodeId, e.getMessage());
     *     }
     * }
     * }</pre>
     * </p>
     * <p>
     * 【实现差异】
     * 与 createDomain(request) 的唯一区别在于：
     * - currentStub(nodeId): 使用配置的默认 nodeId
     * - createStub(domainId, clazz): 使用传入的 domainId 参数
     * 
     * 其他流程完全一致（Stub 创建、gRPC 调用、响应处理）
     * </p>
     *
     * @param request  创建域请求，包含 domain_id、role、cert 等信息
     * @param domainId 目标域 ID，指定在哪个 Kuscia 节点上执行创建操作
     * @return CreateDomainResponse 创建结果
     * @throws io.grpc.StatusRuntimeException gRPC 调用失败时抛出
     * @see #createDomain(CreateDomainRequest) 使用默认 nodeId 的重载方法
     */
    @Override
    public DomainOuterClass.CreateDomainResponse createDomain(DomainOuterClass.CreateDomainRequest request, String domainId) {
        // 显式指定目标域 ID，而不是使用配置的默认 nodeId
        // 这允许在同一个客户端中操作多个不同的 Kuscia 节点
        return dynamicKusciaChannelProvider
                .createStub(domainId, DomainServiceGrpc.DomainServiceBlockingStub.class)
                .createDomain(request);
    }

    @Override
    public DomainOuterClass.UpdateDomainResponse updateDomain(DomainOuterClass.UpdateDomainRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainServiceGrpc.DomainServiceBlockingStub.class).updateDomain(request);
    }

    @Override
    public DomainOuterClass.DeleteDomainResponse deleteDomain(DomainOuterClass.DeleteDomainRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainServiceGrpc.DomainServiceBlockingStub.class).deleteDomain(request);
    }

    @Override
    public DomainOuterClass.QueryDomainResponse queryDomain(DomainOuterClass.QueryDomainRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainServiceGrpc.DomainServiceBlockingStub.class).queryDomain(request);
    }

    @Override
    public DomainOuterClass.BatchQueryDomainResponse batchQueryDomain(DomainOuterClass.BatchQueryDomainRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, DomainServiceGrpc.DomainServiceBlockingStub.class).batchQueryDomain(request);
    }

    @Override
    public Health.HealthResponse healthZ(Health.HealthRequest request) {
        return dynamicKusciaChannelProvider.currentStub(HealthServiceGrpc.HealthServiceBlockingStub.class).healthZ(request);
    }

    @Override
    public Health.HealthResponse healthZ(Health.HealthRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, HealthServiceGrpc.HealthServiceBlockingStub.class).healthZ(request);
    }

    @Override
    public Job.CreateJobResponse createJob(Job.CreateJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).createJob(request);
    }

    @Override
    public Job.QueryJobResponse queryJob(Job.QueryJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).queryJob(request);
    }

    @Override
    public Job.BatchQueryJobStatusResponse batchQueryJobStatus(Job.BatchQueryJobStatusRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).batchQueryJobStatus(request);
    }

    @Override
    public Job.DeleteJobResponse deleteJob(Job.DeleteJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).deleteJob(request);
    }

    @Override
    public Job.StopJobResponse stopJob(Job.StopJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).stopJob(request);
    }

    @Override
    public Iterator<Job.WatchJobEventResponse> watchJob(Job.WatchJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).watchJob(request);
    }

    @Override
    public Job.ApproveJobResponse approveJob(Job.ApproveJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).approveJob(request);
    }

    @Override
    public Job.SuspendJobResponse suspendJob(Job.SuspendJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).suspendJob(request);
    }

    @Override
    public Job.RestartJobResponse restartJob(Job.RestartJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).restartJob(request);
    }

    @Override
    public Job.CancelJobResponse cancelJob(Job.CancelJobRequest request) {
        return dynamicKusciaChannelProvider.currentStub(JobServiceGrpc.JobServiceBlockingStub.class).cancelJob(request);
    }

    @Override
    public Job.CreateJobResponse createJob(Job.CreateJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).createJob(request);
    }

    @Override
    public Job.QueryJobResponse queryJob(Job.QueryJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).queryJob(request);
    }

    @Override
    public Job.BatchQueryJobStatusResponse batchQueryJobStatus(Job.BatchQueryJobStatusRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).batchQueryJobStatus(request);
    }

    @Override
    public Job.DeleteJobResponse deleteJob(Job.DeleteJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).deleteJob(request);
    }

    @Override
    public Job.StopJobResponse stopJob(Job.StopJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).stopJob(request);
    }

    @Override
    public Iterator<Job.WatchJobEventResponse> watchJob(Job.WatchJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).watchJob(request);
    }

    @Override
    public Job.ApproveJobResponse approveJob(Job.ApproveJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).approveJob(request);
    }

    @Override
    public Job.SuspendJobResponse suspendJob(Job.SuspendJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).suspendJob(request);
    }

    @Override
    public Job.RestartJobResponse restartJob(Job.RestartJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).restartJob(request);
    }

    @Override
    public Job.CancelJobResponse cancelJob(Job.CancelJobRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, JobServiceGrpc.JobServiceBlockingStub.class).cancelJob(request);
    }

    @Override
    public Serving.CreateServingResponse createServing(Serving.CreateServingRequest request) {
        return dynamicKusciaChannelProvider.currentStub(ServingServiceGrpc.ServingServiceBlockingStub.class).createServing(request);
    }

    @Override
    public Serving.UpdateServingResponse updateServing(Serving.UpdateServingRequest request) {
        return dynamicKusciaChannelProvider.currentStub(ServingServiceGrpc.ServingServiceBlockingStub.class).updateServing(request);
    }

    @Override
    public Serving.DeleteServingResponse deleteServing(Serving.DeleteServingRequest request) {
        return dynamicKusciaChannelProvider.currentStub(ServingServiceGrpc.ServingServiceBlockingStub.class).deleteServing(request);
    }

    @Override
    public Serving.QueryServingResponse queryServing(Serving.QueryServingRequest request) {
        return dynamicKusciaChannelProvider.currentStub(ServingServiceGrpc.ServingServiceBlockingStub.class).queryServing(request);
    }

    @Override
    public Serving.BatchQueryServingStatusResponse batchQueryServingStatus(Serving.BatchQueryServingStatusRequest request) {
        return dynamicKusciaChannelProvider.currentStub(ServingServiceGrpc.ServingServiceBlockingStub.class).batchQueryServingStatus(request);
    }

    @Override
    public Serving.CreateServingResponse createServing(Serving.CreateServingRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, ServingServiceGrpc.ServingServiceBlockingStub.class).createServing(request);
    }

    @Override
    public Serving.UpdateServingResponse updateServing(Serving.UpdateServingRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, ServingServiceGrpc.ServingServiceBlockingStub.class).updateServing(request);
    }

    @Override
    public Serving.DeleteServingResponse deleteServing(Serving.DeleteServingRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, ServingServiceGrpc.ServingServiceBlockingStub.class).deleteServing(request);
    }

    @Override
    public Serving.QueryServingResponse queryServing(Serving.QueryServingRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, ServingServiceGrpc.ServingServiceBlockingStub.class).queryServing(request);
    }

    @Override
    public Serving.BatchQueryServingStatusResponse batchQueryServingStatus(Serving.BatchQueryServingStatusRequest request, String domainId) {
        return dynamicKusciaChannelProvider.createStub(domainId, ServingServiceGrpc.ServingServiceBlockingStub.class).batchQueryServingStatus(request);
    }
}