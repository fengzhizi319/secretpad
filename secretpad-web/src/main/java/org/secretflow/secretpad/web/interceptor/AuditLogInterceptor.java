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
package org.secretflow.secretpad.web.interceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Audit log interceptor for write operations.
 * <p>
 * Records all write operations (POST/PUT/DELETE) to /api/v1alpha1/ endpoints
 * with structured audit information: userId, action, resource, timestamp, duration.
 * </p>
 * <p>
 * 审计日志拦截器：记录所有对 /api/v1alpha1/ 端点的写操作，
 * 包含结构化审计信息：userId、action、resource、timestamp、duration。
 * </p>
 *
 * @author secretpad-team
 * @since 0.0.1
 */
@Component
@Slf4j
public class AuditLogInterceptor implements HandlerInterceptor {

    private static final String AUDIT_START_TIME = "audit.startTime";
    private static final String API_PREFIX = "/api/v1alpha1/";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final MeterRegistry meterRegistry;

    public AuditLogInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isAuditableRequest(request)) {
            request.setAttribute(AUDIT_START_TIME, System.nanoTime());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!isAuditableRequest(request)) {
            return;
        }

        Long startTime = (Long) request.getAttribute(AUDIT_START_TIME);
        long durationMs = startTime != null
                ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
                : -1;

        String userId = extractUserId(request);
        String method = request.getMethod();
        String path = request.getRequestURI();
        String resource = extractResource(path);
        int status = response.getStatus();
        String traceId = MDC.get("Trace-Id");

        // Structured audit log / 结构化审计日志
        log.info("AUDIT | userId={} | method={} | resource={} | path={} | status={} | durationMs={} | traceId={}",
                userId, method, resource, path, status, durationMs, traceId);

        // Record metrics / 记录指标
        recordMetrics(method, resource, status, durationMs);
    }

    /**
     * Check if the request should be audited.
     * 判断请求是否需要审计。
     */
    private boolean isAuditableRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return path.startsWith(API_PREFIX) && WRITE_METHODS.contains(method);
    }

    /**
     * Extract user ID from request (session or header).
     * 从请求中提取用户 ID（会话或请求头）。
     */
    private String extractUserId(HttpServletRequest request) {
        // Try session attribute first / 优先从会话属性获取
        Object userToken = request.getSession(false) != null
                ? request.getSession().getAttribute("userToken")
                : null;
        if (userToken != null) {
            return userToken.toString();
        }
        // Fallback to header / 回退到请求头
        String userId = request.getHeader("X-User-Id");
        return userId != null ? userId : "anonymous";
    }

    /**
     * Extract resource name from API path.
     * 从 API 路径中提取资源名称。
     */
    private String extractResource(String path) {
        // /api/v1alpha1/project/create -> project
        String relative = path.substring(API_PREFIX.length());
        int slashIdx = relative.indexOf('/');
        return slashIdx > 0 ? relative.substring(0, slashIdx) : relative;
    }

    /**
     * Record audit metrics via Micrometer.
     * 通过 Micrometer 记录审计指标。
     */
    private void recordMetrics(String method, String resource, int status, long durationMs) {
        // Counter: write operation count / 写操作计数
        Counter.builder("secretpad.audit.operations")
                .description("Audit write operations count")
                .tag("method", method)
                .tag("resource", resource)
                .tag("status", String.valueOf(status / 100) + "xx")
                .register(meterRegistry)
                .increment();

        // Timer: write operation duration / 写操作延迟
        Timer.builder("secretpad.audit.duration")
                .description("Audit write operation duration")
                .tag("method", method)
                .tag("resource", resource)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
