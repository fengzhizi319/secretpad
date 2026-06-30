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

import org.secretflow.secretpad.common.dto.UserContextDTO;
import org.secretflow.secretpad.common.enums.PermissionUserTypeEnum;
import org.secretflow.secretpad.common.enums.PlatformTypeEnum;
import org.secretflow.secretpad.common.enums.ResourceTypeEnum;
import org.secretflow.secretpad.common.enums.UserOwnerTypeEnum;
import org.secretflow.secretpad.common.errorcode.AuthErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.ProjectNodeDO;
import org.secretflow.secretpad.persistence.entity.TokensDO;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.UserTokensRepository;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.SysResourcesBizService;
import org.secretflow.secretpad.web.util.AuthUtils;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Login interceptor
 *
 * @author : xiaonan.fhn
 * @date 2023/05/25
 */
@Component
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * Expiration time
     * one hour
     */
    private static final long EXPIRE = 60 * 60 * 24;

    private final UserTokensRepository userTokensRepository;

    private final ProjectNodeRepository projectNodeRepository;
    private final EnvService envService;

    private final SysResourcesBizService sysResourcesBizService;

    @Value("${secretpad.auth.enabled:true}")
    private boolean enable;

    @Value("${server.http-port-inner}")
    private Integer innerHttpPort;

    @Value("${secretpad.deploy-mode}")
    private String deployMode;

    @Resource
    private InnerPortPathConfig innerPortPathConfig;

    @Autowired
    public LoginInterceptor(UserTokensRepository userTokensRepository, EnvService envService,
                            SysResourcesBizService sysResourcesBizService, ProjectNodeRepository projectNodeRepository) {
        this.userTokensRepository = userTokensRepository;
        this.envService = envService;
        this.sysResourcesBizService = sysResourcesBizService;
        this.projectNodeRepository = projectNodeRepository;
    }

    private UserContextDTO createTmpUserForPlatformType(PlatformTypeEnum platformType) {
        if (envService.getPlatformType().equals(PlatformTypeEnum.CENTER)) {
            UserContextDTO userContextDTO = new UserContextDTO();
            userContextDTO.setName("admin");
            userContextDTO.setOwnerId("kuscia-system");
            userContextDTO.setOwnerType(UserOwnerTypeEnum.CENTER);
            userContextDTO.setToken("token");
            userContextDTO.setPlatformType(platformType);
            userContextDTO.setPlatformNodeId(envService.getPlatformNodeId());
            userContextDTO.setDeployMode(deployMode);
            return userContextDTO;
        } else if (PlatformTypeEnum.TEST.equals(envService.getPlatformType())) {
            return UserContext.getUser();
        }
        UserContextDTO userContextDTO = new UserContextDTO();
        userContextDTO.setName("admin");
        userContextDTO.setOwnerId("nodeId");
        userContextDTO.setOwnerType(UserOwnerTypeEnum.EDGE);
        userContextDTO.setToken("token");
        userContextDTO.setPlatformType(platformType);
        userContextDTO.setPlatformNodeId(envService.getPlatformNodeId());
        userContextDTO.setDeployMode(deployMode);
        return userContextDTO;
    }

    /**
     * 预处理请求，在控制器方法执行前进行拦截处理
     * <p>
     * 这是登录拦截器的核心方法，负责验证用户身份并设置用户上下文。
     * 主要处理逻辑：
     * 1. 如果认证功能被禁用，创建临时用户上下文（用于测试环境）
     * 2. 如果是OPTIONS请求（CORS预检），直接放行
     * 3. 根据请求来源端口区分处理：
     *    - 内部端口：节点间RPC调用，从header中提取节点ID创建虚拟用户
     *    - 外部端口：用户请求，从header中提取token进行身份验证
     * </p>
     *
     * @param request  HTTP servlet请求对象，包含客户端的请求信息
     * @param response HTTP servlet响应对象，用于向客户端返回数据
     * @param handler  被调用的处理器对象（通常是Controller方法）
     * @return true 表示放行请求，继续执行后续处理；false 表示拦截请求
     */
    @Override
    public boolean preHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler) {
        // 检查认证功能是否启用
        // 如果禁用（通常在测试环境），创建一个临时的管理员用户上下文
        if (!enable) {
            UserContextDTO admin = createTmpUserForPlatformType(envService.getPlatformType());
            UserContext.setBaseUser(admin);
            return true;
        }
        
        // 处理CORS预检请求
        // OPTIONS请求是浏览器在发送跨域请求前发送的预检请求，不需要进行身份验证
        if (isOptionsVerb(request)) {
            return true;
        }
        
        // 记录调试日志，显示当前处理的端口号
        if (log.isDebugEnabled()) {
            log.debug("Process by port: {}", request.getLocalPort());
        }
        
        // 根据请求来源端口选择不同的处理策略
        // 内部端口：用于节点间的RPC通信
        // 外部端口：用于接收用户的HTTP请求
        if (innerHttpPort.equals(request.getLocalPort())) {
            // 处理节点间RPC请求，从header中提取节点身份信息
            processByNodeRpcRequest(request);
        } else {
            // 处理普通用户请求，进行token验证
            processByUserRequest(request, response);
        }
        return true;
    }


    /**
     * 处理节点间RPC请求
     * <p>
     * 当请求来自内部端口时，说明是其他节点的RPC调用。
     * 此时不从token验证，而是从HTTP header中提取节点身份信息，
     * 创建虚拟用户上下文。
     * </p>
     * <p>
     * Header提取逻辑：
     * 1. 从header中获取 "kuscia-origin-source" 字段，该字段标识请求来源节点ID
     * 2. 验证节点ID是否存在，不存在则抛出认证失败异常
     * 3. 基于节点ID创建虚拟用户对象，设置相关属性
     * 4. 查询该节点关联的项目ID列表，填充到用户上下文中
     * 5. 查询该节点拥有的API资源权限，填充到用户上下文中
     * 6. 将虚拟用户设置为当前请求的用户上下文
     * </p>
     *
     * @param request HTTP请求对象，从中提取节点身份信息
     */
    private void processByNodeRpcRequest(HttpServletRequest request) {
        // 从HTTP header中获取来源节点ID
        // kuscia-origin-source 是Kuscia框架定义的header，用于标识请求的来源节点
        String sourceNodeId = request.getHeader("kuscia-origin-source");
        
        // 验证节点ID是否存在
        // 如果header中没有节点ID，说明这不是合法的节点间RPC调用
        if (StringUtils.isBlank(sourceNodeId)) {
            throw SecretpadException.of(AuthErrorCode.AUTH_FAILED, "Cannot find node id in header for rpc.");
        }
        
        // 创建虚拟用户对象，代表发起RPC调用的节点
        // 虚拟用户不是真实的人类用户，而是代表一个节点的身份
        UserContextDTO virtualUser = new UserContextDTO();
        virtualUser.setVirtualUserForNode(true);  // 标记为节点虚拟用户
        virtualUser.setName(sourceNodeId);         // 使用节点ID作为用户名
        virtualUser.setOwnerId(sourceNodeId);      // 所有者ID也是节点ID
        virtualUser.setOwnerType(UserOwnerTypeEnum.EDGE);  // 所有者类型为边缘节点
        virtualUser.setToken("token");             // 设置默认token（节点间通信不需要真实token）
        virtualUser.setPlatformType(PlatformTypeEnum.EDGE);  // 平台类型为边缘
        virtualUser.setPlatformNodeId(envService.getPlatformNodeId());  // 当前平台节点ID
        virtualUser.setDeployMode(deployMode);     // 部署模式

        // TODO 考虑添加缓存机制，避免频繁查询数据库

        // 填充项目ID列表
        // 查询该节点参与的所有项目，将项目ID集合设置到用户上下文中
        // 这样后续的权限检查可以知道该节点可以访问哪些项目
        List<ProjectNodeDO> byNodeId = projectNodeRepository.findByNodeId(sourceNodeId);
        Set<String> projectIds = byNodeId.stream().map(t -> t.getUpk().getProjectId()).collect(Collectors.toSet());
        virtualUser.setProjectIds(projectIds);

        // 填充接口资源权限
        // 查询该节点拥有的API资源权限代码集合
        // 用于后续的接口访问权限控制，确保节点只能访问它被授权的资源
        Set<String> resourceCodeSet = sysResourcesBizService.queryResourceCodeByUsername(
            PermissionUserTypeEnum.NODE,  // 用户类型为节点
            ResourceTypeEnum.API,         // 资源类型为API
            sourceNodeId                  // 节点ID
        );
        virtualUser.setApiResources(resourceCodeSet);

        // 将构建好的虚拟用户设置为当前线程的用户上下文
        // 后续的業務逻辑可以通过 UserContext.getUser() 获取该用户信息
        UserContext.setBaseUser(virtualUser);
    }

    /**
     * 处理普通用户请求
     * <p>
     * 当请求来自外部端口时，说明是普通用户的HTTP请求。
     * 此时需要进行完整的身份验证流程：
     * 1. 检查是否通过外部端口访问内部接口（安全防护）
     * 2. 从HTTP header中提取用户token
     * 3. 查询token对应的用户会话信息
     * 4. 验证token是否有效且未过期
     * 5. 更新token的最后使用时间（滑动过期机制）
     * 6. 从会话数据中恢复用户上下文
     * 7. 将用户上下文设置到当前线程
     * </p>
     *
     * @param request  HTTP请求对象，从中提取token
     * @param response HTTP响应对象，用于在验证失败时返回错误信息
     */
    private void processByUserRequest(HttpServletRequest request, HttpServletResponse response) {
        // 安全检查：拒绝通过外部端口访问内部专用接口
        // 这是一种安全防护机制，防止外部用户直接调用内部接口
        refuseByOutPortInvokeInnerPort(request, response);
        
        // 从HTTP header中提取用户token
        // AuthUtils.findTokenInHeader() 会从header中查找 "User-Token" 字段
        String token = AuthUtils.findTokenInHeader(request);
        
        // 根据token查询数据库中的用户会话信息
        // TokensDO 包含了用户的登录会话数据，包括token、用户信息、登录时间等
        Optional<TokensDO> tokensDO = userTokensRepository.findByToken(token);
        
        // 验证token是否存在
        // 如果数据库中找不到对应的token，说明用户未登录或token无效
        if (tokensDO.isEmpty()) {
            throw SecretpadException.of(AuthErrorCode.AUTH_FAILED, "login is required");
        }
        
        // 计算token的有效期
        // 获取当前时间和token创建时间，计算时间差（秒）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime gmtToken = tokensDO.get().getGmtToken();
        long until = gmtToken.until(now, ChronoUnit.SECONDS);
        
        // 验证token是否过期
        // EXPIRE 定义为 60 * 60 * 24 = 86400 秒 = 24 小时
        // 如果token创建时间距离现在超过24小时，则认为已过期
        if (until > EXPIRE) {
            throw SecretpadException.of(AuthErrorCode.AUTH_FAILED, "login is expire, please login again.");
        }
        
        // 更新token的最后使用时间（滑动过期机制）
        // 每次请求都会更新token的时间戳，延长其有效期
        // 这样可以保证活跃用户不会因为token过期而被强制登出
        userTokensRepository.saveAndFlush(
                TokensDO.builder()
                        .name(tokensDO.get().getName())           // 用户名
                        .token(tokensDO.get().getToken())         // token值
                        .gmtToken(LocalDateTime.now())            // 更新时间为当前时间
                        .sessionData(tokensDO.get().getSessionData())  // 会话数据（包含用户详细信息）
                        .build()
        );

        // 获取会话数据
        // sessionData 是JSON格式的用户信息，包含用户名、角色、权限等
        String sessionData = tokensDO.get().getSessionData();
        
        // 验证会话数据是否存在
        if (StringUtils.isBlank(sessionData)) {
            throw SecretpadException.of(AuthErrorCode.AUTH_FAILED, "login is required");
        }
        
        // 从JSON字符串反序列化为UserContextDTO对象
        UserContextDTO userContextDTO = UserContextDTO.fromJson(sessionData);
        
        // 将用户上下文设置到当前线程
        // 后续的業務逻辑可以通过 UserContext.getUser() 获取当前用户信息
        UserContext.setBaseUser(userContextDTO);
    }

    @Override
    public void afterCompletion(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler,
                                Exception ex) {
        UserContext.remove();
    }

    private boolean isOptionsVerb(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }

    /**
     * uri only invoke by innerPort
     *
     * @param request request
     */
    private void refuseByOutPortInvokeInnerPort(HttpServletRequest request, HttpServletResponse response) {
        if (innerPortPathConfig.getPath().contains(request.getServletPath())) {
            returnJson(response);
        }
    }

    private void returnJson(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        try (PrintWriter writer = response.getWriter()) {
            writer.print("404");
            writer.flush();
        } catch (IOException e) {
            log.error("LoginInterceptor refuseByOutPortInvokeInnerPort returnJson error.", e);
        }
    }
}
