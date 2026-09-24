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
package org.secretflow.secretpad.service.test;

import org.secretflow.secretpad.service.configuration.IpBlockConfig;
import org.secretflow.secretpad.service.util.IpFilterUtil;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @author yutu
 * @date 2024/03/11
 */
public class IpFilterUtilTest {
    private final List<String> ipList = List
            .of("0.0.0.0/32",
                    "10.0.0.0/8",
                    "0.0.0.0/32",
                    "11.0.0.0/8",
                    "30.0.0.0/8",
                    "100.64.0.0/10",
                    "172.16.0.0/12",
                    "192.168.0.0/16",
                    "33.0.0.0/8"
            );

    @Test
    void test() {
        IpBlockConfig ipBlockConfig = new IpBlockConfig();
        ipBlockConfig.setEnable(true);
        ipBlockConfig.setList(ipList);
        IpFilterUtil ipFilterUtil = new IpFilterUtil(ipBlockConfig);
        Assertions.assertTrue(ipFilterUtil.isIpInRange("10.2.2.2"));
        Assertions.assertFalse(ipFilterUtil.isIpInRange("9.2.2.2"));
        Assertions.assertFalse(ipFilterUtil.isIpInRange("9.2.2"));
        String url = "https://12";
        Assertions.assertFalse(ipFilterUtil.urlIsIpInRange(url));
        url = "https://127.0.0.1";
        Assertions.assertFalse(ipFilterUtil.urlIsIpInRange(url));
        url = "https://10.0.0.1";
        Assertions.assertTrue(ipFilterUtil.urlIsIpInRange(url));
        ipBlockConfig.setEnable(false);
        ipFilterUtil = new IpFilterUtil(ipBlockConfig);
        Assertions.assertFalse(ipFilterUtil.isIpInRange("10.2.2.2"));
    }

    /**
     * 安全整改回归（docs/secretpad_auth.md §8，SSRF 防护）：默认黑名单此前缺少
     * {@code 169.254.0.0/16}（各大云厂商实例元数据端点所在网段），且 {@link org.secretflow.secretpad.common.util.IpFilter}
     * 对 IPv6 地址的比较结构性失效，二者叠加意味着无论黑名单怎么配，元数据端点与任何 IPv6 目标
     * 都不会被拦。这里用与 {@code config/application.yaml} 实际生产配置一致的清单验证两者都已堵上。
     */
    @Test
    void cloudMetadataAndIpv6TargetsAreBlockedWithProductionList() {
        List<String> productionList = List.of(
                "0.0.0.0/32", "127.0.0.1/8", "10.0.0.0/8", "11.0.0.0/8", "30.0.0.0/8",
                "100.64.0.0/10", "172.16.0.0/12", "192.168.0.0/16", "33.0.0.0/8",
                "169.254.0.0/16", "::1/128", "fe80::/10");
        IpBlockConfig ipBlockConfig = new IpBlockConfig();
        ipBlockConfig.setEnable(true);
        ipBlockConfig.setList(productionList);
        IpFilterUtil ipFilterUtil = new IpFilterUtil(ipBlockConfig);

        // AWS/GCP/Azure/阿里云通用的实例元数据端点。
        Assertions.assertTrue(ipFilterUtil.isIpInRange("169.254.169.254"),
                "cloud metadata endpoint must be blocked");
        Assertions.assertTrue(ipFilterUtil.urlIsIpInRange("http://169.254.169.254/latest/meta-data/"));

        // IPv6 回环与链路本地地址：修复前 IpFilter 对 IPv6 的比较恒不匹配，这两条必然放行。
        Assertions.assertTrue(ipFilterUtil.isIpInRange("::1"), "IPv6 loopback must be blocked");
        Assertions.assertTrue(ipFilterUtil.isIpInRange("fe80::1"), "IPv6 link-local must be blocked");

        // 一个不在任何黑名单网段里的公网地址必须仍然放行，确认修复没有变成"全部拦截"。
        Assertions.assertFalse(ipFilterUtil.isIpInRange("8.8.8.8"));
    }
}