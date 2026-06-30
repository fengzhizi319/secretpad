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

package org.secretflow.secretpad.service.sync.edge;

import org.secretflow.secretpad.common.constant.SystemConstants;
import org.secretflow.secretpad.common.dto.SyncDataDTO;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.persistence.model.DataSyncConfig;
import org.secretflow.secretpad.service.sync.JpaSyncDataService;

import com.fasterxml.jackson.databind.JavaType;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.secretflow.secretpad.service.sync.center.SseSession.SSE_PING_MSG;

/**
 * @author yutu
 * @date 2023/10/23
 */
@Slf4j
@Service
@Profile(value = {SystemConstants.EDGE, SystemConstants.TEST})
@RequiredArgsConstructor
@Configuration
@Data
public class EdgeDataSyncServiceImpl implements EdgeDataSyncService {

    private final static String HTTP_PREFIX = "http://";
    public static AtomicInteger sseSate = new AtomicInteger(-1);
    private final JpaSyncDataService jpaSyncDataService;
    private final DataSyncConfig dataSyncConfig;
    @Value("${secretpad.gateway}")
    private String kusciaLiteGateway;
    @Value("${secretpad.center-platform-service}")
    private String routeHeader;
    @Value("${secretpad.node-id}")
    private String nodeId;

    @SuppressWarnings(value = {"rawtypes"})
    @Override
    public void start() {
        List<SyncDataDTO> params = log();
        if (!kusciaLiteGateway.startsWith(HTTP_PREFIX)) {
            kusciaLiteGateway = HTTP_PREFIX + kusciaLiteGateway;
        }
        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(kusciaLiteGateway + "/sync")).newBuilder();
        String s = JsonUtils.toJSONString(params);
        urlBuilder.addQueryParameter("p", s);
        String url = urlBuilder.build().toString();
        useWebClientSse(url);
        EdgeDataSyncServiceImpl.sseSate.set(1);
    }

    /**
     * 使用WebClient建立SSE（Server-Sent Events）连接
     * <p>
     * SSE是一种服务器推送技术，允许服务器向客户端单向推送数据。
     * 在这个方法中，边缘节点通过SSE连接到中心节点，接收数据同步指令。
     * </p>
     * <p>
     * Header添加逻辑说明：
     * 1. "host" header: 设置路由头信息，用于负载均衡或反向代理识别目标服务
     *    - 从配置项 secretpad.center-platform-service 获取
     *    - 帮助网关或负载均衡器正确路由请求到中心平台服务
     * 
     * 2. "kuscia-origin-source" header: 标识请求来源节点ID
     *    - 从配置项 secretpad.node-id 获取当前节点的ID
     *    - 中心节点通过这个header知道是哪个边缘节点在请求同步
     *    - 用于权限验证、数据隔离和审计日志
     * </p>
     * <p>
     * 处理流程：
     * 1. 创建WebClient实例，指定SSE端点URL
     * 2. 发起GET请求，添加必要的header信息
     * 3. 订阅SSE事件流，处理三种事件：
     *    - 正常事件：接收并处理同步数据
     *    - 错误事件：记录错误并更新SSE状态为断开
     *    - 完成事件：记录完成日志并更新SSE状态为断开
     * 4. 配置重试机制：失败时自动重试3次，每次间隔2秒
     * </p>
     *
     * @param url SSE连接的完整URL地址
     */
    private void useWebClientSse(String url) {
        // 创建WebClient实例，用于发起HTTP请求
        // WebClient是Spring WebFlux提供的响应式HTTP客户端
        WebClient webClient = WebClient.create(url);
        
        // 发起GET请求并建立SSE连接
        // 返回的是一个事件流（Flux<ServerSentEvent<String>>）
        Flux<ServerSentEvent<String>> eventStream = webClient.get()
                // 添加host header，用于路由和负载均衡
                // 这个header告诉网关或代理服务器将请求转发到哪个后端服务
                .header("host", routeHeader)
                // 添加来源节点ID header，用于身份标识和数据隔离
                // 中心节点通过这个header识别是哪个边缘节点在请求同步
                .header("kuscia-origin-source", nodeId)
                // 执行请求并获取响应
                .retrieve()
                // 将响应体转换为SSE事件流
                // ServerSentEvent 包含 id（事件类型）和 data（事件数据）
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                // 配置重试策略：失败时自动重试3次，每次间隔2秒
                // 这提高了连接的可靠性，应对临时网络故障
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2)));
        
        // 订阅事件流，处理接收到的SSE事件
        eventStream.subscribe(
                // 正常事件处理器：处理接收到的每个SSE事件
                event -> {
                    log.info("id :{} ,data: {}", event.id(), event.data());
                    String id = event.id();      // 事件ID，实际上是数据类型的类名
                    String data = event.data();  // 事件数据，JSON格式的同步数据
                    
                    // 过滤心跳消息
                    // SSE_PING_MSG 是心跳消息的ID，用于保持连接活跃，不需要处理
                    if (!SSE_PING_MSG.equals(id)) {
                        log.info("sync data DO - {}  Data - {}", id, data);
                        try {
                            // 根据事件ID（类名）加载对应的数据类
                            Class<?> cls = Class.forName(id);
                            
                            // 构建泛型类型：SyncDataDTO<具体数据类型>
                            JavaType javaType = JsonUtils.makeJavaType(SyncDataDTO.class, cls);
                            
                            // 将JSON数据反序列化为SyncDataDTO对象
                            @SuppressWarnings(value = {"rawtypes"})
                            SyncDataDTO o = JsonUtils.toJavaObject(data, javaType);
                            
                            // 执行数据同步操作
                            // 根据SyncDataDTO中的信息，将数据同步到本地数据库
                            jpaSyncDataService.syncData(o);
                        } catch (Exception e) {
                            // 记录同步过程中的异常
                            // 注意：单个事件处理失败不会影响整个SSE连接
                            log.error("sse onEvent sync error {} ", id, e);
                        }
                    }
                },
                // 错误处理器：处理SSE连接或处理过程中的错误
                error -> {
                    log.error("Error receiving SSE: {}", error.getMessage(), error.getCause());
                    // 更新SSE状态为断开（-1）
                    // 外部可以通过 sseSate 变量监控连接状态
                    EdgeDataSyncServiceImpl.sseSate.set(-1);
                },
                // 完成处理器：SSE连接正常关闭时的回调
                () -> {
                    log.info("Completed!!!");
                    // 更新SSE状态为断开（-1）
                    EdgeDataSyncServiceImpl.sseSate.set(-1);
                }
        );
    }

    @PreDestroy
    @Override
    public void close() {
        EdgeDataSyncServiceImpl.sseSate.set(-1);
    }

    @SuppressWarnings(value = {"rawtypes"})
    @Override
    public List<SyncDataDTO> log() {
        List<String> sync = dataSyncConfig.getSync();
        List<SyncDataDTO> syncDataDTOList = new ArrayList<>();
        if (!CollectionUtils.isEmpty(sync)) {
            sync.forEach(t -> {
                Object s = jpaSyncDataService.logTableLastUpdateTime(t);
                syncDataDTOList.add(SyncDataDTO.builder().tableName(t).lastUpdateTime(s.toString()).build());
            });
        }
        return syncDataDTOList;
    }
}