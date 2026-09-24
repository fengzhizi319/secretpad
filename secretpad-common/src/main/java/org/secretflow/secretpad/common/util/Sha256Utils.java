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

/**
 * Sha256 utils (Migrated to SM3 / 兼容保留，底层统一使用国密 SM3 算法)
 *
 * @author : xiaonan.fhn
 * @date 2023/05/25
 * @deprecated 请直接使用 {@link Sm3Utils}
 */
@Deprecated
public class Sha256Utils {

    /**
     * Convert hash string from content using SM3
     *
     * @param content
     * @return hash string
     */
    public static String hash(String content) {
        return Sm3Utils.hash(content);
    }

    /**
     * Convert hash bytes from bytes using SM3
     *
     * @param content
     * @return hash bytes
     */
    public static byte[] hash(byte[] content) {
        return Sm3Utils.hash(content);
    }

    /**
     * Convert hash string from file using SM3
     *
     * @param filePath
     * @return file hash string
     */
    public static String fileHash(String filePath) {
        return Sm3Utils.fileHash(filePath);
    }
}
