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

package org.secretflow.secretpad.web.filter;

import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 响应头添加过滤器
 * <p>
 * 该过滤器的主要功能是为所有HTTP响应自动添加配置好的自定义响应头。
 * 通过配置文件中的 secretpad.response.extra-headers 配置项，可以灵活地
 * 为所有响应添加额外的header，常用于：
 * 1. 添加安全相关的响应头（如CORS、CSP等）
 * 2. 添加自定义的业务标识头
 * 3. 添加版本信息或环境标识
 * </p>
 *
 * @author yansi
 * @date 2023/7/1
 */
@Component
public class AddResponseHeaderFilter extends OncePerRequestFilter {
    /**
     * 注入响应头配置对象
     * 该对象从配置文件中读取需要添加的额外响应头信息
     */
    @Resource
    private SecretPadResponse secretPadResponse;

    /**
     * 执行过滤器的核心逻辑
     * <p>
     * 处理流程：
     * 1. 从配置中获取需要添加的额外响应头映射（Map<String, String>）
     * 2. 检查配置是否为空，避免不必要的处理
     * 3. 遍历配置映射，将每个键值对作为header添加到响应中
     * 4. 继续执行过滤器链，让请求到达后续的处理器
     * </p>
     *
     * @param request     HTTP servlet请求对象，包含客户端的请求信息
     * @param response    HTTP servlet响应对象，用于向客户端返回数据
     * @param filterChain 过滤器链，用于将请求传递给下一个过滤器或目标资源
     * @throws ServletException Servlet处理异常
     * @throws IOException      IO操作异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // 从配置对象中获取需要添加的额外响应头映射
        // 配置示例：secretpad.response.extra-headers.X-Custom-Header=value
        Map<String, String> extraResponseHeaders = secretPadResponse.getExtraHeaders();
        
        // 检查配置是否为空，只有在配置了额外响应头时才进行处理
        // CollectionUtils.isEmpty() 可以同时检查null和空集合
        if (!CollectionUtils.isEmpty(extraResponseHeaders)) {
            // 遍历配置映射，将每个header添加到HTTP响应中
            // key为header名称，value为header值
            extraResponseHeaders.forEach((key, value) -> {
                // 使用addHeader而不是setHeader，允许同一个header有多个值
                // 如果希望覆盖已存在的header，应使用setHeader()
                response.addHeader(key, value);
            });
        }
        
        // 继续执行过滤器链，将请求传递给下一个过滤器或最终的目标处理器
        // 这一步必须在添加完header后执行，确保header在响应发送前被添加
        filterChain.doFilter(request, response);
    }

    @Data
    @Component
    @ConfigurationProperties(prefix = "secretpad.response")
    public static class SecretPadResponse {
        private Map<String, String> extraHeaders;
    }
}
