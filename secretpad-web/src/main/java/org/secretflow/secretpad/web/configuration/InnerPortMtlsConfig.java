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

package org.secretflow.secretpad.web.configuration;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内网口（{@code server.http-port-inner}）mTLS 配置。
 * <p>
 * 安全整改（docs/secretpad_auth.md P0-3）：内网口此前是纯 HTTP，节点身份完全依赖一个可被同一
 * 网络段上任何调用方伪造的 HTTP 头 {@code kuscia-origin-source}
 * （见 {@code LoginInterceptor#processByNodeRpcRequest}）。本类把该端口升级为可选的双向 TLS：
 * 启用后，只有出示了受信 CA（{@link #trustStore}，通常是 Kuscia 网关的签发 CA）签发证书的调用方
 * 才能完成 TCP 握手；未启用时退回历史的纯 HTTP + Header 信任模式，但会在启动时打印一条显式 WARN
 * （而不是像整改前那样悄悄以为自己是安全的）。
 * <p>
 * 默认关闭：真正启用需要 Kuscia 网关一侧也配置好客户端证书，这是本仓库控制不到的外部依赖
 * （docs/secretpad_auth.md §3.1 P0-3 已记录该限制）。配置了 {@code enabled=true} 却缺任一必需
 * 材料，视为配置错误，由 {@link #validate()} 在启动时拒绝——「配了就必须生效，否则报错」，
 * 不允许"以为开了 mTLS，其实退回了明文"这种状态存在。
 *
 * @author claude
 * @date 2026/09/24
 */
@Getter
@Component
public class InnerPortMtlsConfig {

    @Value("${secretpad.inner-port.mtls.enabled:false}")
    private boolean enabled;

    /**
     * 内网口用于向调用方证明自身身份的服务端证书（PKCS12），复用一份独立材料而不是主监听的
     * {@code server.ssl.key-store}——两个端口的信任域不同，混用会让轮换其中一个时误伤另一个。
     */
    @Value("${secretpad.inner-port.mtls.key-store:}")
    private String keyStore;

    @Value("${secretpad.inner-port.mtls.key-store-password:}")
    private String keyStorePassword;

    @Value("${secretpad.inner-port.mtls.key-alias:}")
    private String keyAlias;

    /**
     * 只信任这个信任库里的 CA（预期是 Kuscia 网关的签发 CA），不是系统默认信任库——
     * 默认信任库会让"任意公网 CA 签发的证书"都能通过握手，起不到收窄调用方的作用。
     */
    @Value("${secretpad.inner-port.mtls.trust-store:}")
    private String trustStore;

    @Value("${secretpad.inner-port.mtls.trust-store-password:}")
    private String trustStorePassword;

    /**
     * 校验配置自洽性：启用时五项材料缺一不可。
     *
     * @throws IllegalStateException 启用但材料不全
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        StringBuilder missing = new StringBuilder();
        if (StringUtils.isBlank(keyStore)) {
            missing.append("key-store ");
        }
        if (StringUtils.isBlank(keyStorePassword)) {
            missing.append("key-store-password ");
        }
        if (StringUtils.isBlank(keyAlias)) {
            missing.append("key-alias ");
        }
        if (StringUtils.isBlank(trustStore)) {
            missing.append("trust-store ");
        }
        if (StringUtils.isBlank(trustStorePassword)) {
            missing.append("trust-store-password ");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "secretpad.inner-port.mtls.enabled=true but required material is missing: "
                            + missing.toString().trim()
                            + " — see docs/secretpad_auth.md P0-3. Refusing to start with a half-configured "
                            + "mTLS inner port rather than silently falling back to header trust.");
        }
    }
}
