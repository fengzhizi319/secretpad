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

import static org.secretflow.secretpad.common.util.IpFilter.isIpInRange;

/**
 * @author yutu
 * @date 2024/03/11
 */
public class IpFilterTest {
    @Test
    void test() throws Exception {
        String cidr = "33.0.0.0/8";
        String ipToCheck = "33.123.45.67";
        Assertions.assertTrue(isIpInRange(ipToCheck, cidr));
        ipToCheck = "34.123.45.67";
        Assertions.assertFalse(isIpInRange(ipToCheck, cidr));
    }

    @Test
    void ipv4PrefixZeroMatchesEverything() throws Exception {
        Assertions.assertTrue(isIpInRange("8.8.8.8", "0.0.0.0/0"));
    }

    @Test
    void ipv4Slash32MatchesExactlyOneAddress() throws Exception {
        Assertions.assertTrue(isIpInRange("10.0.0.5", "10.0.0.5/32"));
        Assertions.assertFalse(isIpInRange("10.0.0.6", "10.0.0.5/32"));
    }

    /**
     * 安全整改回归（docs/secretpad_auth.md §8，SSRF 防护）：历史实现用 64 位 long 承载地址字节，
     * IPv6（16 字节）在其中做移位运算会因移位量超过 long 位宽而产生垃圾结果，导致这条比较
     * 结构性地失效——不是"某个具体地址判断错了"，而是"IPv6 地址在这套算法下永远判不对"。
     * 这里用回环地址 ::1 与其自身的 /128 CIDR 验证：一个地址与只包含它自己的最小网段比较，
     * 结果必须为真；历史实现在 IPv6 输入下这类判断根本不可靠。
     */
    @Test
    void ipv6AddressMatchesItsOwnSlash128() throws Exception {
        Assertions.assertTrue(isIpInRange("::1", "::1/128"));
        Assertions.assertFalse(isIpInRange("::2", "::1/128"));
    }

    @Test
    void ipv6LinkLocalPrefixMatches() throws Exception {
        // fe80::/10 是 IPv6 链路本地地址段——修复前这类地址在黑名单比对里恒不匹配。
        Assertions.assertTrue(isIpInRange("fe80::1", "fe80::/10"));
        Assertions.assertFalse(isIpInRange("2001:4860:4860::8888", "fe80::/10"));
    }

    @Test
    void crossFamilyComparisonNeverMatches() throws Exception {
        // IPv4 目标地址不应该被判定为"落在"一个 IPv6 CIDR 里，反之亦然——两者字节长度不同，
        // 没有共同的"网段"概念，必须直接判不匹配，而不是产出一个偶然为真的结果。
        Assertions.assertFalse(isIpInRange("127.0.0.1", "::1/128"));
        Assertions.assertFalse(isIpInRange("::1", "127.0.0.1/8"));
    }
}