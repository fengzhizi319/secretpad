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

import org.secretflow.secretpad.common.dto.UserContextDTO;
import org.secretflow.secretpad.common.enums.PlatformTypeEnum;
import org.secretflow.secretpad.common.enums.UserOwnerTypeEnum;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.service.model.data.UploadDataResultVO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全整改守卫（docs/secretpad_auth.md §8，二次评审，路径穿越）：{@code DataServiceImpl.upload()}
 * 此前用 {@code nodeId=".."} 就能让落盘路径逃出 {@code storeDir}——{@code nodeIdValidCheck} 只挡
 * {@code /} 和 {@code \\}，而真正该兜底的 {@code SafeFileUtils.checkPathInWhitelist} 调用了却
 * 完全丢弃返回值。本用例钉住修复后的两条防线：nodeId 格式校验、白名单检查真正生效。
 *
 * @author claude
 * @date 2026/09/24
 */
class DataServiceImplUploadSecurityTest {

    @TempDir
    Path tempDir;

    private DataServiceImpl dataService;
    private Path storeDir;

    @BeforeEach
    void setUp() {
        dataService = new DataServiceImpl();
        storeDir = tempDir.resolve("app-data");
        ReflectionTestUtils.setField(dataService, "storeDir", storeDir.toString());

        UserContextDTO center = UserContextDTO.builder()
                .name("admin")
                .platformType(PlatformTypeEnum.CENTER)
                .ownerType(UserOwnerTypeEnum.CENTER)
                .ownerId("kuscia-system")
                .build();
        UserContext.setBaseUser(center);
    }

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    @Test
    void parentDirectoryTraversalViaNodeIdIsRejected() {
        // 核心回归：修复前，nodeId=".." 不含 '/' 或 '\\'，能通过历史的 nodeIdValidCheck，
        // 落盘路径 Path.of(storeDir, "..") 规范化后指向 storeDir 的上一级目录——攻击者由此可以
        // 把上传内容写到 storeDir 之外。
        MockMultipartFile file = new MockMultipartFile("file", "evil.csv", "text/csv", "a,b\n1,2".getBytes());

        assertThrows(SecretpadException.class, () -> dataService.upload(file, ".."));

        // 无论异常前落盘逻辑跑到哪一步，storeDir 的上一级目录里都不应该出现任何新文件。
        File parentOfStoreDir = storeDir.getParent().toFile();
        String[] before = parentOfStoreDir.list();
        assertTrue(before == null || java.util.Arrays.stream(before).noneMatch(n -> n.endsWith(".csv")),
                "no file must ever be written outside storeDir: found " + java.util.Arrays.toString(before));
    }

    @Test
    void bareDotNodeIdIsRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "evil.csv", "text/csv", "a,b\n1,2".getBytes());
        assertThrows(SecretpadException.class, () -> dataService.upload(file, "."));
    }

    @Test
    void validNodeIdUploadsInsideStoreDir() {
        MockMultipartFile file = new MockMultipartFile("file", "ok.csv", "text/csv", "a,b\n1,2".getBytes());

        UploadDataResultVO result = dataService.upload(file, "alice");

        Assertions.assertNotNull(result.getRealName());
        File written = storeDir.resolve("alice").resolve(result.getRealName()).toFile();
        assertTrue(written.exists(), "the file must land inside storeDir/alice/ for a legitimate nodeId");
    }
}
