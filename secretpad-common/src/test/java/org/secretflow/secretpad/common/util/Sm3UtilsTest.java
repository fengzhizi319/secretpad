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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Sm3Utils unit tests / 国密 SM3 工具类测试
 *
 * @author cml
 * @date 2024/08/31
 */
public class Sm3UtilsTest {

    @Test
    void testStandardVectorAbc() {
        // GB/T 32918 / GM/T 0004-2012 标准测试向量 "abc"
        String hash = Sm3Utils.hash("abc");
        Assertions.assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", hash);
    }

    @Test
    void testHashString() {
        String hash = Sm3Utils.hash("12#$qwER");
        Assertions.assertEquals("8ccf05454275f10f634e1a6402d6c82e9a9b2f7e29251db0bdd6a78d93153d40", hash);
    }

    @Test
    void testHashBytes() {
        byte[] hash = Sm3Utils.hash("12#$qwER".getBytes());
        Assertions.assertEquals(32, hash.length);
    }

    @Test
    void testFileHash() throws IOException {
        File tempFile = File.createTempFile("sm3_test", ".txt");
        tempFile.deleteOnExit();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("abc");
        }
        String fileHash = Sm3Utils.fileHash(tempFile.getAbsolutePath());
        Assertions.assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", fileHash);
    }

    @Test
    void testHmacSm3() {
        String hmac = Sm3Utils.hmacSm3("key", "message");
        Assertions.assertNotNull(hmac);
        Assertions.assertEquals(64, hmac.length());
    }
}
