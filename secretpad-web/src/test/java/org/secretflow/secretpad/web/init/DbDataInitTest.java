/*
 * Copyright 2024 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.web.init;

import org.secretflow.secretpad.common.enums.UserOwnerTypeEnum;
import org.secretflow.secretpad.common.util.Sha256Utils;
import org.secretflow.secretpad.persistence.entity.AccountsDO;
import org.secretflow.secretpad.persistence.repository.UserAccountsRepository;
import org.secretflow.secretpad.web.constant.AuthConstants;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * 安全整改 P0-1 守卫（docs/secretpad_auth.md §3.1）：
 * <ul>
 *   <li>已存在的管理员账号，启动时口令**不得被覆盖**（历史实现每次启动都重置为环境变量值 / 固定值）；</li>
 *   <li>不存在时用随机口令建户，口令只写入 0600 文件，且写入的就是能登录的那个口令；</li>
 *   <li>注入了 pad_pwd 时用注入值建户，不写口令文件。</li>
 * </ul>
 */
public class DbDataInitTest {

    @TempDir
    Path tempDir;

    private UserAccountsRepository repository;
    private Environment environment;
    private DbDataInit dbDataInit;
    private Path passwordFile;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(UserAccountsRepository.class);
        environment = Mockito.mock(Environment.class);
        dbDataInit = new DbDataInit(repository);
        ReflectionTestUtils.setField(dbDataInit, "environment", environment);
        ReflectionTestUtils.setField(dbDataInit, "platformType", "CENTER");
        ReflectionTestUtils.setField(dbDataInit, "nodeId", "kuscia-system");
        passwordFile = tempDir.resolve("initial-admin-password");
        ReflectionTestUtils.setField(dbDataInit, "initialPasswordFile", passwordFile.toString());
    }

    @Test
    void existingAccountPasswordIsNeverOverwritten() {
        String userChosenHash = Sha256Utils.hash("user-chose-this-Passw0rd!");
        AccountsDO existing = AccountsDO.builder().name("admin").passwordHash(userChosenHash)
                .ownerType(UserOwnerTypeEnum.CENTER).ownerId("kuscia-system").instId("").build();
        Mockito.when(repository.findByName("admin")).thenReturn(Optional.of(existing));
        // 环境变量里明确给了另一个口令：历史实现会用它覆盖，现在必须无视
        Mockito.when(environment.getProperty("secretpad.auth.pad_pwd")).thenReturn("EnvSupplied-Pass1!");

        dbDataInit.initUserAndPwd();

        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
        Assertions.assertEquals(userChosenHash, existing.getPasswordHash(), "existing password must be left untouched on restart");
        Assertions.assertFalse(Files.exists(passwordFile), "no initial password file when the account already exists");
    }

    @Test
    void missingAccountGetsRandomPasswordWrittenOnlyToProtectedFile() throws Exception {
        Mockito.when(repository.findByName("admin")).thenReturn(Optional.empty());
        // 无默认值的占位符在 env 缺失时抛异常——历史实现在这里回落到 12345678
        Mockito.when(environment.getProperty("secretpad.auth.pad_pwd")).thenThrow(new IllegalArgumentException("unresolved placeholder"));

        dbDataInit.initUserAndPwd();

        ArgumentCaptor<AccountsDO> saved = ArgumentCaptor.forClass(AccountsDO.class);
        Mockito.verify(repository).save(saved.capture());
        Assertions.assertTrue(Files.exists(passwordFile), "initial password must be delivered through the file");
        String written = Files.readString(passwordFile).trim();
        Assertions.assertEquals(16, written.length());
        Assertions.assertNotEquals("12345678", written);
        Assertions.assertEquals(Sha256Utils.hash(written), saved.getValue().getPasswordHash(),
                "the password in the file must be the one that actually logs in");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(passwordFile);
        Assertions.assertEquals(PosixFilePermissions_rwOwnerOnly(), perms, "initial password file must be 0600");
    }

    @Test
    void suppliedPasswordIsUsedAndNotWrittenToFile() {
        Mockito.when(repository.findByName("admin")).thenReturn(Optional.empty());
        Mockito.when(environment.getProperty("secretpad.auth.pad_pwd")).thenReturn("EnvSupplied-Pass1!");

        dbDataInit.initUserAndPwd();

        ArgumentCaptor<AccountsDO> saved = ArgumentCaptor.forClass(AccountsDO.class);
        Mockito.verify(repository).save(saved.capture());
        Assertions.assertEquals(Sha256Utils.hash("EnvSupplied-Pass1!"), saved.getValue().getPasswordHash());
        Assertions.assertFalse(Files.exists(passwordFile), "supplied password must not be echoed into a file");
    }

    @Test
    void fixedTestPasswordFlagUsesKnownPasswordWhenEnabled() throws Exception {
        // 安全整改的显式 opt-in 例外（docs/secretpad_auth.md P0-1）：开发者要求保留固定测试口令
        // admin/12345678，但只能在明确打开 fixedTestPasswordEnabled 时才生效，且必须仍然经过
        // "写入 0600 文件" 这条通道，不因为口令是公开已知值就跳过。
        ReflectionTestUtils.setField(dbDataInit, "fixedTestPasswordEnabled", true);
        Mockito.when(repository.findByName("admin")).thenReturn(Optional.empty());
        Mockito.when(environment.getProperty("secretpad.auth.pad_pwd")).thenReturn(null);

        dbDataInit.initUserAndPwd();

        ArgumentCaptor<AccountsDO> saved = ArgumentCaptor.forClass(AccountsDO.class);
        Mockito.verify(repository).save(saved.capture());
        Assertions.assertEquals(Sha256Utils.hash(AuthConstants.FIXED_TEST_PASSWORD), saved.getValue().getPasswordHash());
        Assertions.assertTrue(Files.exists(passwordFile), "fixed test password must still go through the initial password file");
        Assertions.assertEquals(AuthConstants.FIXED_TEST_PASSWORD, Files.readString(passwordFile).trim());
    }

    @Test
    void explicitPadPwdStillWinsOverFixedTestPasswordFlag() {
        // 优先级不变：secretpad.auth.pad_pwd 显式注入始终优先于固定测试口令开关——
        // 开关只是"缺省值该是什么"的选择，不是凌驾于显式配置之上的开关。
        ReflectionTestUtils.setField(dbDataInit, "fixedTestPasswordEnabled", true);
        Mockito.when(repository.findByName("admin")).thenReturn(Optional.empty());
        Mockito.when(environment.getProperty("secretpad.auth.pad_pwd")).thenReturn("EnvSupplied-Pass1!");

        dbDataInit.initUserAndPwd();

        ArgumentCaptor<AccountsDO> saved = ArgumentCaptor.forClass(AccountsDO.class);
        Mockito.verify(repository).save(saved.capture());
        Assertions.assertEquals(Sha256Utils.hash("EnvSupplied-Pass1!"), saved.getValue().getPasswordHash());
        Assertions.assertFalse(Files.exists(passwordFile), "explicit pad_pwd must not be echoed into a file even with the flag on");
    }

    private static Set<PosixFilePermission> PosixFilePermissions_rwOwnerOnly() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }
}
