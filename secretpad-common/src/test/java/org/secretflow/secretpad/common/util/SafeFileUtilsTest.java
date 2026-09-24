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
package org.secretflow.secretpad.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全整改守卫（docs/secretpad_auth.md §8，二次评审）：
 * <ul>
 *   <li>字符串前缀比较必须不被"同级但不同目录、名字恰好共享前缀"的路径绕过；</li>
 *   <li>规范化失败（如 {@code null} 输入）必须 fail-closed，不能像历史实现那样 {@code return true}。</li>
 * </ul>
 *
 * @author claude
 * @date 2026/09/24
 */
class SafeFileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void fileInsideWhitelistDirIsAllowed() throws Exception {
        Path whiteDir = tempDir.resolve("app-data");
        Path file = whiteDir.resolve("sub").resolve("file.csv");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, "x");

        assertTrue(SafeFileUtils.checkPathInWhitelist(file.toFile(), List.of(whiteDir.toString())));
    }

    @Test
    void siblingDirectoryWithSharedNamePrefixIsRejected() throws Exception {
        // 核心回归：SafeFileUtils 此前用 String.startsWith 判断，"/x/app-data-evil" 会被
        // "/x/app-data" 误判为子目录——两者只是文件名前几个字符相同，根本不是包含关系。
        Path whiteDir = tempDir.resolve("app-data");
        Path evilSibling = tempDir.resolve("app-data-evil");
        java.nio.file.Files.createDirectories(whiteDir);
        java.nio.file.Files.createDirectories(evilSibling);
        File evilFile = evilSibling.resolve("secret.txt").toFile();
        java.nio.file.Files.writeString(evilFile.toPath(), "x");

        assertFalse(SafeFileUtils.checkPathInWhitelist(evilFile, List.of(whiteDir.toString())),
                "a sibling directory that merely shares a name prefix must not be treated as a subdirectory");
    }

    @Test
    void parentDirectoryEscapeIsRejected() throws Exception {
        Path whiteDir = tempDir.resolve("app-data");
        java.nio.file.Files.createDirectories(whiteDir);
        // 等价于 nodeId=".." 拼出来的落盘目标：storeDir 的上一级目录。
        File escaped = new File(whiteDir.toFile(), "../escaped.csv");

        assertFalse(SafeFileUtils.checkPathInWhitelist(escaped, List.of(whiteDir.toString())));
    }

    @Test
    void exactWhitelistDirItselfIsAllowed() {
        Path whiteDir = tempDir.resolve("app-data");
        assertTrue(SafeFileUtils.checkPathInWhitelist(whiteDir.toFile(), List.of(whiteDir.toString())),
                "the whitelist root itself must be considered inside the whitelist");
    }

    @Test
    void nullFileIsRejectedNotFailOpen() {
        assertFalse(SafeFileUtils.checkPathInWhitelist((File) null, List.of(tempDir.toString())));
    }

    @Test
    void nullWhitelistIsRejectedNotFailOpen() {
        assertFalse(SafeFileUtils.checkPathInWhitelist(tempDir.resolve("f.csv").toFile(), null));
    }

    @Test
    void canonicalizationExceptionFailsClosed() {
        // 核心回归：getCanonicalPath() 抛异常（这里用超长文件名在 Linux 上稳定触发
        // "File name too long"）时，历史实现的 catch 块会 return true——"查不清楚就放行"，
        // 而规范化失败本身就是路径穿越攻击最容易撞上的场景之一。现在必须 fail-closed。
        String tooLongName = "a".repeat(5000);
        File file = new File(tempDir.toFile(), tooLongName);
        assertFalse(SafeFileUtils.checkPathInWhitelist(file, List.of(tempDir.toString())),
                "a canonicalization failure must deny, not silently allow");
        assertFalse(SafeFileUtils.checkPathInWhitelist(file.getPath(), List.of(tempDir.toString())),
                "the String overload must fail closed the same way");
    }
}
