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
package org.secretflow.secretpad.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Check if the file safe utils
 * <p>
 * 安全整改（二次评审，见 docs/secretpad_auth.md §8）：本类此前存在两处会让整个白名单校验失去意义的缺陷：
 * <ol>
 *   <li><b>异常路径 fail-open</b>：{@code getCanonicalPath()} 抛异常时两个重载都 {@code return true}
 *       （"检查失败就当作通过"）。规范化失败恰恰是路径穿越攻击最容易触发的场景之一（例如极长路径、
 *       非法字符），"检查不了就放行"等于给攻击者一条绕过路径。现在改为异常也拒绝（fail-closed）。</li>
 *   <li><b>字符串前缀匹配可被同级目录名绕过</b>：{@code canonicalPath.startsWith(whitePath)} 对
 *       {@code whitePath="/app/data"}、{@code canonicalPath="/app/data-evil/x"} 会误判为"在白名单内"——
 *       后者根本不是前者的子目录，只是文件名恰好以同一串字符开头。现在按路径分段比较，而不是按字符串前缀。</li>
 * </ol>
 * 唯一的生产调用方是 {@link org.secretflow.secretpad.service.impl.DataServiceImpl}（更早还有一处已被
 * 注释掉的 {@code InstServiceImpl} 调用，见 docs/secretpad_auth.md 现状清单 H4，不在本次修复范围）；
 * 该调用点此前还完全没有使用本方法的返回值，见 docs/secretpad_auth.md §8 的对应记录。
 *
 * @author : xiaonan.fhn
 * @date 2023/06/27
 */
public class SafeFileUtils {
    private final static Logger LOGGER = LoggerFactory.getLogger(SafeFileUtils.class);

    /**
     * Check the filePath whether exist in the whitelist
     * The argument is util of the file path
     *
     * @param filePath      file path
     * @param whitelistPath while list of path
     * @return whether exist in the whitelist
     */
    public static boolean checkPathInWhitelist(String filePath, List<String> whitelistPath) {
        try {
            File file = new File(filePath);
            return checkPathInWhitelist(file, whitelistPath);
        } catch (Exception e) {
            // fail-closed：规范化异常本身就是可疑信号，不能当作"通过"处理。
            LOGGER.error("FilepathTraversalChecker checkPathTraversal CatchException, " +
                    "filePath = {} Check path traversal catch exception, deny! ", filePath, e);
            return false;
        }
    }

    /**
     * Check the filePath whether exist in the whitelist
     * The argument is util of the file path
     *
     * @param file          file path
     * @param whitelistPath while list of path
     * @return whether exist in the whitelist
     */
    public static boolean checkPathInWhitelist(File file, List<String> whitelistPath) {
        try {
            if (file == null) {
                LOGGER.error("Target file path is null, need to be deny!");
                return false;
            }
            String canonicalPath = file.getCanonicalPath();
            if (canonicalPath.isEmpty()) {
                LOGGER.error("Target canonical file path is null, need to be deny!");
                return false;
            }
            if (whitelistPath == null) {
                LOGGER.error("White path list is null, using error, need to be deny!");
                return false;
            }
            for (String whitePath : whitelistPath) {
                if (isWithinDirectory(canonicalPath, canonicalizeQuietly(whitePath))) {
                    return true;
                }
            }
            LOGGER.error("Target canonical file path not in white list, need to deny!");
            return false;
        } catch (Exception e) {
            // fail-closed：与上面的重载同一条原则，异常即拒绝，不再"当作通过"。
            LOGGER.error("Check path traversal catch exception, deny!", e);
            return false;
        }
    }

    /**
     * 判断 {@code canonicalPath} 是否真的位于目录 {@code canonicalDir} 之下（含相等）。
     * <p>
     * 不用 {@code String.startsWith}：那样 {@code "/app/data"} 会把 {@code "/app/data-evil"} 误判为子路径，
     * 两者只是字符串前缀相同，并非目录包含关系。这里补一个路径分隔符再比较，等价于按路径分段判断。
     */
    private static boolean isWithinDirectory(String canonicalPath, String canonicalDir) {
        if (canonicalPath.equals(canonicalDir)) {
            return true;
        }
        String dirWithSeparator = canonicalDir.endsWith(File.separator) ? canonicalDir : canonicalDir + File.separator;
        return canonicalPath.startsWith(dirWithSeparator);
    }

    /**
     * 白名单目录本身也需要规范化后再比较，否则调用方传入的相对路径会让 {@link #isWithinDirectory} 的
     * 分隔符比较失效。规范化失败时原样返回该字符串，交由上层比较自然失败（fail-closed 的方向不变）。
     */
    private static String canonicalizeQuietly(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            LOGGER.error("Cannot canonicalize whitelist entry {}, treat literally (likely to fail the check, which is the safe direction)", path, e);
            return path;
        }
    }
}
