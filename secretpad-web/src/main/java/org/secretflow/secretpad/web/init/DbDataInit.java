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
package org.secretflow.secretpad.web.init;

import org.secretflow.secretpad.common.enums.UserOwnerTypeEnum;
import org.secretflow.secretpad.common.util.FileUtils;
import org.secretflow.secretpad.common.util.Sha256Utils;
import org.secretflow.secretpad.persistence.entity.AccountsDO;
import org.secretflow.secretpad.persistence.repository.UserAccountsRepository;
import org.secretflow.secretpad.service.dataproxy.DataProxyService;
import org.secretflow.secretpad.web.constant.AuthConstants;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * Initialize db init resource
 *
 * @author yutu
 * @date 2024/04/22
 */
@RequiredArgsConstructor
@Slf4j
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DbDataInit implements CommandLineRunner {
    private final UserAccountsRepository userAccountsRepository;
    @Autowired
    private Environment environment;
    @Value("${secretpad.platform-type}")
    private String platformType;
    @Value("${secretpad.node-id}")
    private String nodeId;

    @Resource
    private DataProxyService dataProxyService;

    @Override
    public void run(String... args) throws Exception {
        initUserAndPwd();
        dataProxyService.updateDataSourceUseDataProxyInMaster();
    }

    /**
     * 初始管理员口令写入的文件路径（0600）。安全整改 P0-1：初始口令**不进日志**、**不进环境变量回显**，
     * 只在首次建户时写到这个文件一次；运维首次登录后应删除该文件（首登强制改密在 P1-3 落地）。
     */
    @Value("${secretpad.auth.initial-password-file:./config/initial-admin-password}")
    private String initialPasswordFile;

    /**
     * 【仅本地开发 / 自动化测试】固定测试口令开关。默认 {@code false}；只有
     * {@code application-dev.yaml} / {@code application-test.yaml} 显式打开，生产配置与 edge/p2p
     * 部署 profile 都没有这一项。开启后新建的管理员账号使用 {@link AuthConstants#FIXED_TEST_PASSWORD}
     * （与整改前的默认口令数值相同），启动日志会打印醒目 WARN，不会静默生效。
     */
    @Value("${secretpad.auth.fixed-test-password-enabled:false}")
    private boolean fixedTestPasswordEnabled;

    /**
     * 初始化管理员账号。
     * <p>
     * 安全整改（docs/secretpad_auth.md P0-1）：历史实现在账号**已存在**时也会用环境变量或固定值
     * {@code 12345678} 覆盖口令哈希（"user already exists update password"），使用户自行改过的口令在每次
     * 重启后被重置；且启动日志明文打印口令。现在的规则只有三条：
     * <ol>
     *   <li>账号已存在：<b>一律不动</b>，只刷新 ownerId 文件；</li>
     *   <li>账号不存在：口令取 {@code secretpad.auth.pad_pwd}（环境变量注入）；缺失时，若
     *       {@link #fixedTestPasswordEnabled} 为真则用固定测试口令，否则生成 16 位随机口令；</li>
     *   <li>随机口令 / 固定测试口令只写入 {@link #initialPasswordFile}（0600）一次，任何日志里都
     *       不出现口令明文（固定测试口令本身是公开已知值，不属于需要保密的秘密，但仍不在日志里回显，
     *       保持"日志不打印口令字段"这一条规则没有例外）。</li>
     * </ol>
     */
    public void initUserAndPwd() {
        String username = readProperty("secretpad.auth.pad_name");
        if (StringUtils.isBlank(username)) {
            username = AuthConstants.USER_NAME;
        }

        Optional<AccountsDO> existing = userAccountsRepository.findByName(username);
        if (existing.isPresent()) {
            // 已存在的账号口令属于用户，启动流程无权改写；这里连"是否与环境变量一致"都不比对——
            // 比对本身就意味着进程持有一份口令明文。这条规则对固定测试口令同样成立：开着这个开关
            // 重启一个已经改过密的账号，口令依然不会被打回固定值。
            log.info("user {} already exists, password left untouched", username);
            FileUtils.writeToFile(existing.get().getOwnerId());
            return;
        }

        String password = readProperty("secretpad.auth.pad_pwd");
        boolean suppliedExplicitly = StringUtils.isNotBlank(password);
        if (!suppliedExplicitly) {
            if (fixedTestPasswordEnabled) {
                password = AuthConstants.FIXED_TEST_PASSWORD;
                log.warn("secretpad.auth.fixed-test-password-enabled=true: user {} will be created with the "
                        + "well-known fixed test password. This flag must NEVER be enabled outside local "
                        + "development or automated tests — see docs/secretpad_auth.md P0-1.", username);
            } else {
                password = AuthConstants.getRandomPassword();
            }
        }

        AccountsDO accountsDO = AccountsDO.builder()
                .name(username)
                .passwordHash(Sha256Utils.hash(password))
                .ownerType(UserOwnerTypeEnum.fromString(platformType))
                .ownerId(nodeId)
                .instId("")
                .build();
        userAccountsRepository.save(accountsDO);
        FileUtils.writeToFile(accountsDO.getOwnerId());

        if (suppliedExplicitly) {
            log.info("user {} created with the password supplied by secretpad.auth.pad_pwd", username);
        } else {
            writeInitialPassword(username, password);
        }
    }

    /**
     * 读取一个可能带未解析占位符的配置项：{@code ${SECRETPAD_PASSWORD}} 这类无默认值的占位符在环境变量缺失时
     * 会让 {@code Environment#getProperty} 抛异常，历史实现在这里回落到固定口令；现在回落为"空"，由调用方决定生成随机口令。
     */
    private String readProperty(String key) {
        try {
            return environment.getProperty(key);
        } catch (Exception e) {
            log.debug("property {} unresolved, treated as absent", key, e);
            return null;
        }
    }

    /**
     * 把随机初始口令写到 0600 文件。文件已存在时覆盖（上一次的初始口令已随账号建立失效）。
     * 权限设置在不支持 POSIX 权限的文件系统上会失败，此时只告警不中断——账号已经建好，口令文件是交付通道而不是安全边界。
     */
    private void writeInitialPassword(String username, String password) {
        Path path = Paths.get(initialPasswordFile);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, password + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                log.warn("cannot set 0600 on {} (non-POSIX filesystem); protect it manually", path);
            }
            log.info("initial password for user {} written to {} — read it once, log in, change it, then delete the file",
                    username, path.toAbsolutePath());
        } catch (IOException e) {
            // 写不出去也不能把口令打进日志：抛出让启动失败，运维改用 secretpad.auth.pad_pwd 注入。
            throw new IllegalStateException("cannot write initial admin password to " + path
                    + "; set secretpad.auth.pad_pwd instead", e);
        }
    }
}
