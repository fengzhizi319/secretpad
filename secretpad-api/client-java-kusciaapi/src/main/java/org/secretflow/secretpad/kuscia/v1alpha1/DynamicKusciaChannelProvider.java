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

package org.secretflow.secretpad.kuscia.v1alpha1;

import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaProtocolEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.event.RegisterKusciaEvent;
import org.secretflow.secretpad.kuscia.v1alpha1.event.UnRegisterKusciaEvent;
import org.secretflow.secretpad.kuscia.v1alpha1.factory.KusciaApiChannelFactory;
import org.secretflow.secretpad.kuscia.v1alpha1.factory.impl.GrpcKusciaApiChannelFactory;
import org.secretflow.secretpad.kuscia.v1alpha1.model.DynamicKusciaGrpcConfig;
import org.secretflow.secretpad.kuscia.v1alpha1.model.KusciaGrpcConfig;

import io.grpc.stub.AbstractStub;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.secretflow.v1alpha1.kusciaapi.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author yutu
 * @date 2024/06/12
 */
@Slf4j
@Service
@SuppressWarnings({"unchecked"})
public class DynamicKusciaChannelProvider {

    private static final Map<String, KusciaApiChannelFactory> CHANNEL_FACTORIES = new ConcurrentHashMap<>();
    private final static int BLOCKING_TIMEOUT_MILLISECOND = 5000;
    private final static int FUTURE_TIMEOUT_MILLISECOND = 5000;
    private final static int StubSCRIPTION_TIMEOUT_DAY = 365;
    private final Object lock = new Object();
    private volatile boolean isInitialized = false;
    @Resource
    @Setter
    private DynamicKusciaGrpcConfig dynamicKusciaGrpcConfig;

    @Resource
    private ApplicationEventPublisher publisher;

    @Value("${secretpad.node-id}")
    @Setter
    private String nodeId;

    @Value("${secretpad.kuscia-path:./config/kuscia/}")
    private String kusciaPath;

    private static <T> @NotNull String getServiceName(Class<T> clazz) {
        String serviceName;
        try {
            serviceName = String.valueOf(clazz.getDeclaredField("SERVICE_NAME").get(null));
            Assert.notNull(serviceName, "SERVICE_NAME must not be null");
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported class type: " + clazz.getName(), e);
        }
        return serviceName;
    }

    @PostConstruct
    public void init() {
        isInitialized = true;
        if (!CollectionUtils.isEmpty(dynamicKusciaGrpcConfig.getNodes())) {
            for (KusciaGrpcConfig config : dynamicKusciaGrpcConfig.getNodes()) {
                log.info("Init kuscia node, config={}", config);
                registerKuscia(config);
                log.info("Init kuscia node success, CHANNEL_FACTORIES={}", CHANNEL_FACTORIES);
            }
        }
        isInitialized = false;
        try {
            serializableKusciaConfigFileInit();
        } catch (Exception e) {
            log.error("Load kuscia config by config file error", e);
        }
    }

    @PreDestroy
    public void destroy() {
        CHANNEL_FACTORIES.forEach((key, value) -> value.shutdown());
    }

    /**
     * 获取当前节点的 gRPC Stub（使用默认 nodeId）
     * <p>
     * 【功能说明】
     * 根据配置的默认 nodeId（从 secretpad.node-id 读取），创建对应服务的 gRPC Stub。
     * 这是最常用的方法，适用于单节点场景或操作当前节点的场景。
     * </p>
     * <p>
     * 【实现逻辑】
     * 1. 记录日志：输出当前使用的 nodeId
     * 2. 委托给 createStub(nodeId, clazz) 创建 Stub
     * </p>
     * <p>
     * 【使用示例】
     * <pre>{@code
     * // 创建 DomainService BlockingStub
     * DomainServiceBlockingStub stub = provider.currentStub(DomainServiceBlockingStub.class);
     * 
     * // 调用 createDomain 方法
     * CreateDomainResponse response = stub.createDomain(request);
     * }</pre>
     * </p>
     *
     * @param clazz Stub 类型（如 DomainServiceBlockingStub.class）
     * @return 配置好超时时间的 gRPC Stub
     * @throws IllegalArgumentException 如果 domainId 对应的 Channel 不存在
     * @see #createStub(String, Class) 指定 domainId 的方法
     */
    public <T extends AbstractStub<T>> T currentStub(Class<T> clazz) {
        // 记录日志：显示当前使用的 nodeId
        // 这有助于调试和追踪请求路由
        log.info("The nodeId received by kuscia is: {}", nodeId);
        
        // 委托给 createStub 方法，使用配置的 nodeId
        return createStub(nodeId, clazz);
    }

    public void registerKuscia(KusciaGrpcConfig config) {
        Assert.notNull(config, "KusciaGrpcConfig must not be null");
        config.validateAndProcess();
        if (dynamicKusciaGrpcConfig.getNodes().contains(config)) {
            log.info("KusciaGrpcConfig already exists,unRegisterKuscia config={}", config);
            unRegisterKuscia(config);
        }
        if (isInitialized || dynamicKusciaGrpcConfig.getNodes().add(config)) {
            log.info("Register kuscia node success, config={}", config);
            synchronized (lock) {
                registerChannelFactory(config.getDomainId(), new GrpcKusciaApiChannelFactory(config));
                if (!ObjectUtils.isEmpty(publisher)) {
                    publisher.publishEvent(new RegisterKusciaEvent(this, config));
                }
            }
        }
    }

    public void unRegisterKuscia(KusciaGrpcConfig config) {
        Assert.notNull(config, "KusciaGrpcConfig must not be null");
        config.validateAndProcess();
        dynamicKusciaGrpcConfig.getNodes().remove(config);
        if (isInitialized || CHANNEL_FACTORIES.containsKey(config.getDomainId())) {
            log.info("Unregister kuscia node success, config={}", config);
            synchronized (lock) {
                KusciaApiChannelFactory remove = CHANNEL_FACTORIES.remove(config.getDomainId());
                if (remove != null) {
                    remove.shutdown();
                }
                if (!ObjectUtils.isEmpty(publisher)) {
                    publisher.publishEvent(new UnRegisterKusciaEvent(this, config));
                }
            }
        }
    }

    /**
     * equals may be wrong
     **/
    public void unRegisterKuscia(String domainId) {
        List<KusciaGrpcConfig> configs = dynamicKusciaGrpcConfig.getNodes().stream()
                .filter(node -> StringUtils.equals(node.getDomainId(), domainId))
                .toList();
        if (!CollectionUtils.isEmpty(configs)) {
            configs.forEach(this::unRegisterKuscia);
        }

    }

    private void registerChannelFactory(String name, KusciaApiChannelFactory channelFactory) {
        KusciaApiChannelFactory factory = CHANNEL_FACTORIES.put(name, channelFactory);
        if (factory != null) {
            log.warn("The channel factory {} has been registered, shutdown and replace", name);
            factory.shutdown();
        }
    }

    /**
     * 创建 gRPC Stub（指定 domainId）
     * <p>
     * 【功能说明】
     * 根据指定的 domainId 和服务类型，创建对应的 gRPC Stub。
     * 这是整个模块的核心方法，负责：
     * 1. 验证 domainId 是否已注册
     * 2. 根据服务名称和 Stub 类型创建实例
     * 3. 配置超时时间
     * </p>
     * <p>
     * 【实现逻辑详解】
     * </p>
     * <pre>
     * 步骤 1: 验证 Channel 是否存在
     *   ├─ 调用 checkChannelFactoryExist(domainId)
     *   ├─ 检查 CHANNEL_FACTORIES.containsKey(domainId)
     *   └─ 如果不存在，抛出 IllegalArgumentException
     * 
     * 步骤 2: 获取服务名称
     *   ├─ 调用 getServiceName(clazz.getEnclosingClass())
     *   ├─ 通过反射获取 SERVICE_NAME 常量
     *   └─ 例如："kuscia.proto.api.v1alpha1.kusciaapi.DomainService"
     * 
     * 步骤 3: 根据服务和 Stub 类型创建实例（Switch-Case）
     *   ├─ 匹配服务名称（DomainService、JobService 等 9 种服务）
     *   ├─ 匹配 Stub 类型（BlockingStub、Stub、FutureStub）
     *   └─ 调用对应的 new*Stub() 工厂方法
     * 
     * 步骤 4: 配置超时时间
     *   ├─ BlockingStub: 5000ms（同步阻塞调用）
     *   ├─ FutureStub: 5000ms（异步 Future 调用）
     *   └─ StreamStub: 365天（流式调用，长时间连接）
     * 
     * 步骤 5: 返回配置好的 Stub
     *   └─ 调用方可以使用 Stub 发起 gRPC 调用
     * </pre>
     * <p>
     * 【Stub 类型说明】
     * 1. BlockingStub（同步阻塞）
     *    - 特点：调用线程会阻塞，直到收到响应或超时
     *    - 适用：简单的请求-响应场景
     *    - 超时：5000ms
     *    - 示例：stub.createDomain(request)
     * 
     * 2. Stub（异步流式）
     *    - 特点：支持单向/双向流式调用，非阻塞
     *    - 适用：实时数据流、事件监听
     *    - 超时：365天（长连接）
     *    - 示例：stub.watchJob(request, responseObserver)
     * 
     * 3. FutureStub（异步 Future）
     *    - 特点：返回 ListenableFuture，可以异步处理响应
     *    - 适用：需要并发处理多个请求
     *    - 超时：5000ms
     *    - 示例：ListenableFuture<Response> future = stub.createDomain(request)
     * </p>
     * <p>
     * 【超时配置策略】
     * - BLOCKING_TIMEOUT_MILLISECOND = 5000ms
     *   用于 Unary RPC（如 createDomain、queryJob）
     *   原因：这些操作通常很快完成，5秒足够
     * 
     * - FUTURE_TIMEOUT_MILLISECOND = 5000ms
     *   用于 Future RPC
     *   原因：与 BlockingStub 类似
     * 
     * - StubSCRIPTION_TIMEOUT_DAY = 365天
     *   用于 Streaming RPC（如 watchJob）
     *   原因：流式调用需要长时间保持连接
     * </p>
     * <p>
     * 【支持的服务列表】
     * 1. DomainService - 域管理服务
     * 2. DomainDataService - 域数据管理服务
     * 3. DomainDataSourceService - 数据源管理服务
     * 4. DomainDataGrantService - 数据授权管理服务
     * 5. DomainRouteService - 域路由管理服务
     * 6. JobService - 任务管理服务
     * 7. ServingService - 在线推理服务管理
     * 8. HealthService - 健康检查服务
     * 9. CertificateService - 证书管理服务
     * </p>
     * <p>
     * 【使用示例】
     * <pre>{@code
     * // 示例 1: 创建 BlockingStub（最常用）
     * DomainServiceBlockingStub blockingStub = provider.createStub(
     *     "alice", 
     *     DomainServiceGrpc.DomainServiceBlockingStub.class
     * );
     * CreateDomainResponse response = blockingStub.createDomain(request);
     * 
     * // 示例 2: 创建 Stream Stub（用于监听）
     * JobServiceStub streamStub = provider.createStub(
     *     "alice",
     *     JobServiceGrpc.JobServiceStub.class
     * );
     * streamStub.watchJob(request, new StreamObserver<WatchJobEventResponse>() {
     *     @Override
     *     public void onNext(WatchJobEventResponse value) {
     *         log.info("Received event: {}", value);
     *     }
     *     // ...
     * });
     * 
     * // 示例 3: 创建 Future Stub（异步调用）
     * DomainServiceFutureStub futureStub = provider.createStub(
     *     "alice",
     *     DomainServiceGrpc.DomainServiceFutureStub.class
     * );
     * ListenableFuture<CreateDomainResponse> future = futureStub.createDomain(request);
     * Futures.addCallback(future, new FutureCallback<CreateDomainResponse>() {
     *     @Override
     *     public void onSuccess(CreateDomainResponse result) {
     *         log.info("Success: {}", result);
     *     }
     *     @Override
     *     public void onFailure(Throwable t) {
     *         log.error("Failed", t);
     *     }
     * }, executor);
     * }</pre>
     * </p>
     * <p>
     * 【错误处理】
     * - 如果 domainId 未注册：抛出 IllegalArgumentException
     *   消息："No such kuscia instance domain id: {domainId}"
     *   解决：先调用 registerKuscia() 注册节点
     * 
     * - 如果 clazz 不支持：抛出 IllegalArgumentException
     *   消息："Unsupported class type: {className}"
     *   解决：使用支持的 Stub 类型（BlockingStub/Stub/FutureStub）
     * </p>
     *
     * @param domainId 目标域 ID（必须已通过 registerKuscia 注册）
     * @param clazz    Stub 类型（如 DomainServiceBlockingStub.class）
     * @return 配置好超时时间的 gRPC Stub
     * @throws IllegalArgumentException 如果 domainId 不存在或 clazz 不支持
     * @see #currentStub(Class) 使用默认 nodeId 的方法
     * @see #registerKuscia(KusciaGrpcConfig) 注册节点的方法
     */
    public <T extends AbstractStub<T>> T createStub(String domainId, Class<T> clazz) {
        // 第 1 步：验证 domainId 对应的 Channel 是否已注册
        // 如果 CHANNEL_FACTORIES 中找不到 domainId，抛出异常
        checkChannelFactoryExist(domainId);
        
        // 第 2 步：通过反射获取服务名称（SERVICE_NAME 常量）
        // 例如："kuscia.proto.api.v1alpha1.kusciaapi.DomainService"
        String serviceName = getServiceName(clazz.getEnclosingClass());
        
        // 声明 Stub 变量（稍后在 switch 中初始化）
        AbstractStub<?> t = null;

        // 第 3 步：根据服务名称和 Stub 类型创建对应的实例
        // 使用 Java 17 的 Switch Expression 语法
        switch (serviceName) {
            // ====================================================================
            // DomainService - 域管理服务
            // ====================================================================
            case DomainServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(DomainServiceGrpc.DomainServiceBlockingStub.class)) {
                    // 创建 BlockingStub（同步阻塞）
                    // 1. 从 CHANNEL_FACTORIES 获取 ManagedChannel
                    // 2. 创建 BlockingStub
                    // 3. 设置超时 5000ms
                    t = DomainServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(DomainServiceGrpc.DomainServiceStub.class)) {
                    // 创建 Stream Stub（异步流式）
                    // 超时设置为 365 天，因为流式调用需要长时间保持连接
                    t = DomainServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(StubSCRIPTION_TIMEOUT_DAY, TimeUnit.DAYS);
                }
                if (clazz.equals(DomainServiceGrpc.DomainServiceFutureStub.class)) {
                    // 创建 Future Stub（异步 Future）
                    // 超时 5000ms
                    t = DomainServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }

            // ====================================================================
            // DomainDataService - 域数据管理服务
            // ====================================================================
            case DomainDataServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(DomainDataServiceGrpc.DomainDataServiceBlockingStub.class)) {
                    t = DomainDataServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(DomainDataServiceGrpc.DomainDataServiceStub.class)) {
                    t = DomainDataServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(StubSCRIPTION_TIMEOUT_DAY, TimeUnit.DAYS);
                }
                if (clazz.equals(DomainDataServiceGrpc.DomainDataServiceFutureStub.class)) {
                    t = DomainDataServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }

            // ====================================================================
            // DomainRouteService - 域路由管理服务
            // ====================================================================
            case DomainRouteServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(DomainRouteServiceGrpc.DomainRouteServiceBlockingStub.class)) {
                    t = DomainRouteServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(DomainRouteServiceGrpc.DomainRouteServiceStub.class)) {
                    t = DomainRouteServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(StubSCRIPTION_TIMEOUT_DAY, TimeUnit.DAYS);
                }
                if (clazz.equals(DomainRouteServiceGrpc.DomainRouteServiceFutureStub.class)) {
                    t = DomainRouteServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }
            
            // ====================================================================
            // DomainDataSourceService - 数据源管理服务
            // ====================================================================
            case DomainDataSourceServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(DomainDataSourceServiceGrpc.DomainDataSourceServiceBlockingStub.class)) {
                    t = DomainDataSourceServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(DomainDataSourceServiceGrpc.DomainDataSourceServiceStub.class)) {
                    t = DomainDataSourceServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(StubSCRIPTION_TIMEOUT_DAY, TimeUnit.DAYS);
                }
                if (clazz.equals(DomainDataSourceServiceGrpc.DomainDataSourceServiceFutureStub.class)) {
                    t = DomainDataSourceServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }
            
            // ====================================================================
            // DomainDataGrantService - 数据授权管理服务
            // ====================================================================
            case DomainDataGrantServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(DomainDataGrantServiceGrpc.DomainDataGrantServiceBlockingStub.class)) {
                    t = DomainDataGrantServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(DomainDataGrantServiceGrpc.DomainDataGrantServiceStub.class)) {
                    t = DomainDataGrantServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(StubSCRIPTION_TIMEOUT_DAY, TimeUnit.DAYS);
                }
                if (clazz.equals(DomainDataGrantServiceGrpc.DomainDataGrantServiceFutureStub.class)) {
                    t = DomainDataGrantServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }

            // ====================================================================
            // JobService - 任务管理服务
            // ====================================================================
            case JobServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(JobServiceGrpc.JobServiceBlockingStub.class)) {
                    t = JobServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(JobServiceGrpc.JobServiceStub.class)) {
                    // 注意：JobService 的 Stream Stub 没有设置超时
                    // 因为 watchJob 是长时间运行的流式调用
                    t = JobServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel());
                }
                if (clazz.equals(JobServiceGrpc.JobServiceFutureStub.class)) {
                    t = JobServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }

            // ====================================================================
            // ServingService - 在线推理服务管理
            // ====================================================================
            case ServingServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(ServingServiceGrpc.ServingServiceBlockingStub.class)) {
                    t = ServingServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(ServingServiceGrpc.ServingServiceStub.class)) {
                    t = ServingServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(StubSCRIPTION_TIMEOUT_DAY, TimeUnit.DAYS);
                }
                if (clazz.equals(ServingServiceGrpc.ServingServiceFutureStub.class)) {
                    t = ServingServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }

            // ====================================================================
            // HealthService - 健康检查服务
            // ====================================================================
            case HealthServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(HealthServiceGrpc.HealthServiceBlockingStub.class)) {
                    t = HealthServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(HealthServiceGrpc.HealthServiceStub.class)) {
                    t = HealthServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(StubSCRIPTION_TIMEOUT_DAY, TimeUnit.DAYS);
                }
                if (clazz.equals(HealthServiceGrpc.HealthServiceFutureStub.class)) {
                    t = HealthServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }

            // ====================================================================
            // CertificateService - 证书管理服务
            // ====================================================================
            case CertificateServiceGrpc.SERVICE_NAME -> {
                if (clazz.equals(CertificateServiceGrpc.CertificateServiceBlockingStub.class)) {
                    t = CertificateServiceGrpc.newBlockingStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(BLOCKING_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
                if (clazz.equals(CertificateServiceGrpc.CertificateServiceStub.class)) {
                    t = CertificateServiceGrpc.newStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(StubSCRIPTION_TIMEOUT_DAY, TimeUnit.DAYS);
                }
                if (clazz.equals(CertificateServiceGrpc.CertificateServiceFutureStub.class)) {
                    t = CertificateServiceGrpc.newFutureStub(CHANNEL_FACTORIES.get(domainId).getChannel())
                            .withDeadlineAfter(FUTURE_TIMEOUT_MILLISECOND, TimeUnit.MILLISECONDS);
                }
            }

            // 不支持的服务类型，抛出异常
            default -> throw new IllegalArgumentException("Unsupported class type: " + clazz.getName());
        }
        
        // 第 4 步：返回创建好的 Stub
        // 调用方可以使用这个 Stub 发起 gRPC 调用
        return (T) t;
    }

    /**
     * check before invoke or exception appear
     */
    public boolean isChannelExist(String domainId) {
        return CHANNEL_FACTORIES.containsKey(domainId);
    }


    private void checkChannelFactoryExist(String domainId) {
        if (!CHANNEL_FACTORIES.containsKey(domainId)) {
            throw new IllegalArgumentException("No such kuscia instance domain id: " + domainId);
        }
    }

    public String getProtocolByDomainId(String domainId) {
        String protocol = KusciaProtocolEnum.TLS.name().toLowerCase(Locale.ROOT);
        if (CollectionUtils.isEmpty(dynamicKusciaGrpcConfig.getNodes())) {
            return protocol;
        }
        for (KusciaGrpcConfig node : dynamicKusciaGrpcConfig.getNodes()) {
            if (node.getDomainId().equals(domainId)) {
                protocol = node.getProtocol().name().toLowerCase(Locale.ROOT);
            }
        }
        return protocol;
    }

    public void serializableKusciaConfigFileInit() throws IOException, ClassNotFoundException {
        ObjectInputStream in = null;
        KusciaGrpcConfig config;
        File file = ResourceUtils.getFile(kusciaPath);
        if (Files.exists(file.toPath())) {
            for (File f : Objects.requireNonNull(file.listFiles())) {
                in = new ObjectInputStream(new FileInputStream(f));
                config = (KusciaGrpcConfig) in.readObject();
                log.info("Load kuscia config by config file, config={}", config);
                registerKuscia(config);
            }
        }
        IOUtils.closeQuietly(in);
    }

}