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

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 安全整改（二次评审，见 docs/secretpad_auth.md §8，SSRF 防护）：本类原实现把地址字节数组塞进一个
 * 64 位 {@code long}（{@code result <<= 8; result |= octet}）。IPv4 地址是 4 字节，塞得下；
 * IPv6 地址是 16 字节，循环移位 16 次相当于要求 128 位，而 {@code long} 只有 64 位——Java 对 long
 * 的移位量按 64 取模，结果是一堆和真实地址毫无关系的数字。配置里的黑名单 CIDR 全是 IPv4
 * （{@code config/application.yaml} 的 {@code ip.block.list}），任何解析到 IPv6 地址（含
 * {@code ::ffff:169.254.169.254} 这类 IPv4 映射地址、{@code ::1} 回环、{@code fe80::/10} 链路本地）
 * 的目标都会被这条比较错误地判定为"不在黑名单里"，从而绕过整个 SSRF 防护——这是 SSRF 检测最经典的
 * 旁路手法之一。
 * <p>
 * 现在改用 {@link BigInteger} 承载地址（不受 64 位宽度限制，IPv4/IPv6 都能正确表示），并且要求
 * 待测地址与 CIDR 地址是同一地址族（都是 4 字节或都是 16 字节）——跨族没有"是否同网段"这个问题，
 * 直接判不匹配，不做任何隐式转换（例如把 IPv4 当成 IPv4-mapped IPv6 展开），避免引入新的、
 * 更难审计的等价判断分支。
 *
 * @author yutu
 * @date 2024/03/11
 */
public final class IpFilter {
    private IpFilter() {
    }

    public static boolean isIpInRange(String ip, String cidr) throws UnknownHostException {
        String[] parts = cidr.split("/");
        String address = parts[0];
        byte[] targetBytes = InetAddress.getByName(ip).getAddress();
        byte[] subnetBytes = InetAddress.getByName(address).getAddress();
        if (targetBytes.length != subnetBytes.length) {
            return false;
        }
        int addressBits = targetBytes.length * 8;
        int prefix;
        if (parts.length < 2) {
            prefix = 0;
        } else {
            prefix = Integer.parseInt(parts[1]);
        }
        if (prefix < 0 || prefix > addressBits) {
            throw new IllegalArgumentException("invalid CIDR prefix " + prefix + " for a " + addressBits + "-bit address family: " + cidr);
        }
        BigInteger ipInt = new BigInteger(1, targetBytes);
        BigInteger subnetInt = new BigInteger(1, subnetBytes);
        // 掩码 = 高 prefix 位为 1、其余为 0。prefix=0 时整段地址都算通配，掩码全零。
        BigInteger mask = prefix == 0
                ? BigInteger.ZERO
                : BigInteger.ONE.shiftLeft(addressBits).subtract(BigInteger.ONE)
                .shiftRight(addressBits - prefix).shiftLeft(addressBits - prefix);
        return ipInt.and(mask).equals(subnetInt.and(mask));
    }
}