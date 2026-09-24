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
package org.secretflow.secretpad.service.impl;

import org.secretflow.secretpad.common.constant.CacheConstants;
import org.secretflow.secretpad.common.dto.UserContextDTO;
import org.secretflow.secretpad.common.enums.ResourceTypeEnum;
import org.secretflow.secretpad.common.enums.UserOwnerTypeEnum;
import org.secretflow.secretpad.common.errorcode.AuthErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.UUIDUtils;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.*;
import org.secretflow.secretpad.persistence.repository.*;
import org.secretflow.secretpad.service.AuthService;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.SysResourcesBizService;
import org.secretflow.secretpad.service.UserService;

import jakarta.annotation.Resource;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * User auth service implementation class
 *
 * @author : xiaonan.fhn
 * @date 2023/5/25
 */
@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private UserService userService;

    @Autowired
    private UserAccountsRepository userAccountsRepository;

    @Autowired
    private UserTokensRepository userTokensRepository;

    @Autowired
    private ProjectNodeRepository projectNodeRepository;

    @Autowired
    private EnvService envService;

    @Autowired
    private SysResourcesBizService resourcesBizService;

    @Value("${secretpad.deploy-mode}")
    private String deployMode;


    @Value("${secretpad.account-error-max-attempts:5}")
    private Integer maxAttempts;

    @Value("${secretpad.account-error-lock-time-minutes:30}")
    private Integer lockTimeMinutes;

    @Resource
    private CacheManager cacheManager;
    @Resource
    private InstRepository instRepository;
    @Resource
    private NodeRepository nodeRepository;

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = SecretpadException.class)
    public UserContextDTO login(String name, String passwordHash) {
        //check password and lock
        AccountsDO user = accountLockedCheck(name, passwordHash);
        String token = UUIDUtils.newUUID();
        UserContextDTO userContextDTO = new UserContextDTO();
        userContextDTO.setName(user.getName());
        userContextDTO.setOwnerId(user.getOwnerId());
        userContextDTO.setOwnerType(user.getOwnerType());
        userContextDTO.setToken(token);
        userContextDTO.setPlatformType(envService.getPlatformType());
        userContextDTO.setPlatformNodeId(envService.getPlatformNodeId());

        // fill project id and resource codes
        if (UserOwnerTypeEnum.EDGE.equals(user.getOwnerType())) {
            List<ProjectNodeDO> byNodeId = projectNodeRepository.findByNodeId(user.getOwnerId());
            Set<String> projectIds = byNodeId.stream().map(t -> t.getUpk().getProjectId()).collect(Collectors.toSet());
            userContextDTO.setProjectIds(projectIds);

            Set<String> resourceCodeSet = resourcesBizService.queryResourceCodeByUsername(user.getOwnerType().toPermissionUserType(), ResourceTypeEnum.API, user.getName());
            userContextDTO.setApiResources(resourceCodeSet);
        }

        userContextDTO.setDeployMode(deployMode);
        InstDO instDO = instRepository.findByInstId(user.getOwnerId());
        if (Objects.nonNull(instDO)) {
            userContextDTO.setOwnerName(instDO.getName());
        } else {
            NodeDO nodeDO = nodeRepository.findByNodeId(user.getOwnerId());
            if (Objects.nonNull(nodeDO)) {
                userContextDTO.setOwnerName(nodeDO.getName());
            }
        }
        TokensDO tokensDO = TokensDO.builder().name(user.getName()).token(token).gmtToken(LocalDateTime.now()).sessionData(userContextDTO.toJsonStr()).build();
        userTokensRepository.saveAndFlush(tokensDO);
        UserContext.setBaseUser(userContextDTO);
        return userContextDTO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void logout(String name, String token) {
        userTokensRepository.deleteByNameAndToken(name, token);
    }


    /**
     * account lock check
     *
     * @param userName
     */

    private AccountsDO accountLockedCheck(String userName, String passwordHash) {

        LocalDateTime currentTime = LocalDateTime.now();
        //current user is need lock
        AccountsDO user = userService.queryUserByName(userName);
        if (ObjectUtils.isEmpty(user)) {
            Cache cache = cacheManager.getCache(CacheConstants.USER_LOCK_CACHE);
            HashMap<String, Integer> lockInfo = cache.get(userName, HashMap.class);
            int failedAttempts = 0;
            if (lockInfo != null) {
                failedAttempts = lockInfo.get("failedAttempts");
                if (failedAttempts >= maxAttempts) {
                    throw SecretpadException.of(AuthErrorCode.USER_IS_LOCKED, String.valueOf(lockTimeMinutes));
                }
            } else {
                lockInfo = new HashMap<>();
            }
            lockInfo.put("failedAttempts", ++failedAttempts);
            cache.put(userName, lockInfo);
            throw SecretpadException.of(AuthErrorCode.USER_PASSWORD_ERROR, String.valueOf(maxAttempts - --failedAttempts));
        }

        // 安全整改（docs/secretpad_auth.md P1-2，对应现状清单 H1）：锁定状态必须先于口令比对判断，
        // 且判断依据是"当前时间是否已经过了 lockedInvalidTime"，而不是完全跳过这一步。
        // 历史实现从未读取 lockedInvalidTime——账号"锁定"期间只要口令正确依然登录成功，锁定形同虚设
        // （对撞库没有实质拦截意义：撞对了永远放行）；而错够 maxAttempts 次之后，因为没有任何地方
        // 检查锁是否已过期并复位，账号会**永久锁死**，与提示文案"N 分钟后重试"不符。
        if (Objects.nonNull(user.getLockedInvalidTime())) {
            if (currentTime.isBefore(user.getLockedInvalidTime())) {
                long remainingMinutes = Duration.between(currentTime, user.getLockedInvalidTime()).toMinutes() + 1;
                throw SecretpadException.of(AuthErrorCode.USER_IS_LOCKED, String.valueOf(remainingMinutes));
            }
            // 锁定窗口已过期：先复位计数与锁定时间，再按正常流程比对口令——复位本身不代表口令正确，
            // 只是让这个账号重新回到"可以再次尝试"的状态，错误口令仍然会重新计数。
            userService.userUnlock(user);
        }

        //checkPassword success
        if (user.getPasswordHash().equals(passwordHash)) {
            //lock invalid
            user.setLockedInvalidTime(null);
            user.setFailedAttempts(null);
            userAccountsRepository.save(user);
            return user;
        }

        user.setFailedAttempts(Objects.isNull(user.getFailedAttempts()) ? 1 : user.getFailedAttempts() + 1);
        if (user.getFailedAttempts() >= maxAttempts) {
            user.setLockedInvalidTime(currentTime.plusMinutes(lockTimeMinutes));
            userService.userLock(user);
            throw SecretpadException.of(AuthErrorCode.USER_IS_LOCKED, String.valueOf(lockTimeMinutes));
        }
        userService.userLock(user);
        throw SecretpadException.of(AuthErrorCode.USER_PASSWORD_ERROR, String.valueOf(maxAttempts - user.getFailedAttempts()));
    }
}
