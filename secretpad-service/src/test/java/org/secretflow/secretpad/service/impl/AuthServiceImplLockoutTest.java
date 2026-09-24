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
package org.secretflow.secretpad.service.impl;

import org.secretflow.secretpad.common.enums.PlatformTypeEnum;
import org.secretflow.secretpad.common.enums.UserOwnerTypeEnum;
import org.secretflow.secretpad.common.errorcode.AuthErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.AccountsDO;
import org.secretflow.secretpad.persistence.repository.InstRepository;
import org.secretflow.secretpad.persistence.repository.NodeRepository;
import org.secretflow.secretpad.persistence.repository.ProjectNodeRepository;
import org.secretflow.secretpad.persistence.repository.UserAccountsRepository;
import org.secretflow.secretpad.persistence.repository.UserTokensRepository;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.SysResourcesBizService;
import org.secretflow.secretpad.service.UserService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 安全整改守卫（docs/secretpad_auth.md P1-2，对应现状清单 H1）。
 * <p>
 * 历史实现从不检查 {@code lockedInvalidTime}，账号"锁定"期间只要口令正确依然登录成功——
 * 锁定对撞库没有实质拦截意义；且错够 {@code maxAttempts} 次后没有任何地方检查锁是否已过期并复位，
 * 账号永久锁死。本用例分别钉住这两个方向。
 *
 * @author claude
 * @date 2026/09/24
 */
class AuthServiceImplLockoutTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 30;

    private AuthServiceImpl authService;
    private UserService userService;
    private UserAccountsRepository userAccountsRepository;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl();
        userService = Mockito.mock(UserService.class);
        userAccountsRepository = Mockito.mock(UserAccountsRepository.class);
        UserTokensRepository userTokensRepository = Mockito.mock(UserTokensRepository.class);
        ProjectNodeRepository projectNodeRepository = Mockito.mock(ProjectNodeRepository.class);
        EnvService envService = Mockito.mock(EnvService.class);
        Mockito.when(envService.getPlatformType()).thenReturn(PlatformTypeEnum.CENTER);
        Mockito.when(envService.getPlatformNodeId()).thenReturn("kuscia-system");
        SysResourcesBizService sysResourcesBizService = Mockito.mock(SysResourcesBizService.class);
        CacheManager cacheManager = Mockito.mock(CacheManager.class);
        Mockito.when(cacheManager.getCache(Mockito.anyString())).thenReturn(Mockito.mock(Cache.class));
        InstRepository instRepository = Mockito.mock(InstRepository.class);
        NodeRepository nodeRepository = Mockito.mock(NodeRepository.class);

        ReflectionTestUtils.setField(authService, "userService", userService);
        ReflectionTestUtils.setField(authService, "userAccountsRepository", userAccountsRepository);
        ReflectionTestUtils.setField(authService, "userTokensRepository", userTokensRepository);
        ReflectionTestUtils.setField(authService, "projectNodeRepository", projectNodeRepository);
        ReflectionTestUtils.setField(authService, "envService", envService);
        ReflectionTestUtils.setField(authService, "resourcesBizService", sysResourcesBizService);
        ReflectionTestUtils.setField(authService, "cacheManager", cacheManager);
        ReflectionTestUtils.setField(authService, "instRepository", instRepository);
        ReflectionTestUtils.setField(authService, "nodeRepository", nodeRepository);
        ReflectionTestUtils.setField(authService, "deployMode", "ALL-IN-ONE");
        ReflectionTestUtils.setField(authService, "maxAttempts", MAX_ATTEMPTS);
        ReflectionTestUtils.setField(authService, "lockTimeMinutes", LOCK_MINUTES);
    }

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private AccountsDO lockedAccount(LocalDateTime lockedInvalidTime) {
        return AccountsDO.builder()
                .name("admin")
                .passwordHash("correct-hash")
                .ownerType(UserOwnerTypeEnum.CENTER)
                .ownerId("kuscia-system")
                .lockedInvalidTime(lockedInvalidTime)
                .failedAttempts(MAX_ATTEMPTS)
                .build();
    }

    @Test
    void correctPasswordDuringActiveLockIsStillRejected() {
        // 核心回归：账号处于锁定窗口内，即使口令完全正确，也必须继续拒绝——
        // 这是整改前"锁定对撞库无意义"的具体体现：正确口令永远畅通。
        AccountsDO account = lockedAccount(LocalDateTime.now().plusMinutes(20));
        Mockito.when(userService.queryUserByName("admin")).thenReturn(account);

        SecretpadException ex = assertThrows(SecretpadException.class,
                () -> authService.login("admin", "correct-hash"));
        assertEquals(AuthErrorCode.USER_IS_LOCKED.getCode(), ex.getErrorCode().getCode());
        // save 不应被调用——账号仍处于锁定期，不应该有任何状态变化（既不解锁也不清零）。
        Mockito.verify(userAccountsRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void expiredLockWithCorrectPasswordUnlocksAndLogsIn() {
        // 锁定窗口已过期：应当自动复位（userUnlock），随后口令正确即可正常登录——
        // 这是整改前"错够次数后永久锁死"的对立面：锁定必须真的会到期。
        AccountsDO account = lockedAccount(LocalDateTime.now().minusMinutes(1));
        Mockito.when(userService.queryUserByName("admin")).thenReturn(account);

        authService.login("admin", "correct-hash");

        Mockito.verify(userService).userUnlock(account);
        assertNull(account.getLockedInvalidTime(), "userUnlock must clear the lock");
        assertNull(account.getFailedAttempts(), "userUnlock must clear the failure counter");
    }

    @Test
    void expiredLockWithWrongPasswordCountsAsFirstFailureNotAccumulated() {
        // 锁定过期后错误口令：计数应从"解锁后的 0"重新累加为 1，而不是延续解锁前的旧计数——
        // 否则一次解锁后的错误尝试会立刻把用户打回锁定状态，等于锁定窗口从未真正结束。
        AccountsDO account = lockedAccount(LocalDateTime.now().minusMinutes(1));
        Mockito.when(userService.queryUserByName("admin")).thenReturn(account);
        Mockito.doAnswer(invocation -> {
            AccountsDO arg = invocation.getArgument(0);
            arg.setLockedInvalidTime(null);
            arg.setFailedAttempts(null);
            return null;
        }).when(userService).userUnlock(Mockito.any());

        SecretpadException ex = assertThrows(SecretpadException.class,
                () -> authService.login("admin", "wrong-hash"));
        assertEquals(AuthErrorCode.USER_PASSWORD_ERROR.getCode(), ex.getErrorCode().getCode());
        assertEquals(1, account.getFailedAttempts(), "failure count must restart from zero after an expired lock clears it");
    }

    @Test
    void noLockRecordedBehavesLikeBeforeThisChange() {
        // 从未被锁定过（lockedInvalidTime 为 null）：走原有路径，口令正确直接登录成功，不触碰锁相关字段。
        AccountsDO account = AccountsDO.builder()
                .name("admin").passwordHash("correct-hash")
                .ownerType(UserOwnerTypeEnum.CENTER).ownerId("kuscia-system")
                .build();
        Mockito.when(userService.queryUserByName("admin")).thenReturn(account);

        authService.login("admin", "correct-hash");

        Mockito.verify(userService, Mockito.never()).userUnlock(Mockito.any());
    }
}
