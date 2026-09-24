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

/**
 * @author yutu
 * @date 2024/04/22
 */
public class Sha256UtilsTest {

    @Test
    void hash() {
        String hash = Sha256Utils.hash("12#$qwER");
        Assertions.assertEquals("8ccf05454275f10f634e1a6402d6c82e9a9b2f7e29251db0bdd6a78d93153d40", hash, "not support");
    }
}