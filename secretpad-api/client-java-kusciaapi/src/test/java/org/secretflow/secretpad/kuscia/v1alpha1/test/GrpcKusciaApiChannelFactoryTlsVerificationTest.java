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
package org.secretflow.secretpad.kuscia.v1alpha1.test;

import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaModeEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.constant.KusciaProtocolEnum;
import org.secretflow.secretpad.kuscia.v1alpha1.factory.impl.GrpcKusciaApiChannelFactory;
import org.secretflow.secretpad.kuscia.v1alpha1.mock.MockKusciaGrpcServer;
import org.secretflow.secretpad.kuscia.v1alpha1.model.KusciaGrpcConfig;
import org.secretflow.v1alpha1.kusciaapi.Health;
import org.secretflow.v1alpha1.kusciaapi.HealthServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全整改守卫（docs/secretpad_auth.md P0-2）。
 * <p>
 * 历史实现用 {@code InsecureTrustManagerFactory} 建 TLS 通道，不验证 Kuscia 服务端证书——
 * 任何自签证书都能冒充服务端，中间人可完全劫持这条控制面链路。本用例不满足于"通道对象建成功"
 * （历史测试 {@link KusciaApiChannelFactoryTest#test()} 就是这样，只覆盖 NOTLS），而是真的
 * 发起一次 RPC 触发握手：
 * <ul>
 *   <li>caFile 指向签发了服务端证书的真 CA → 握手成功，RPC 正常返回；</li>
 *   <li>caFile 指向一张无关的 CA（{@code certs/wrong-ca.crt}）→ 握手必须失败，RPC 抛
 *       {@link StatusRuntimeException}。这条断言在改动前必然失败——不验证证书时，
 *       任何 CA（包括这张无关的）都会被通道工厂接受。</li>
 * </ul>
 *
 * @author claude
 * @date 2026/09/24
 */
class GrpcKusciaApiChannelFactoryTlsVerificationTest {

    private static final int TLS_PORT = 50061;

    private MockKusciaGrpcServer mockKusciaGrpcServer;

    @BeforeEach
    void setUp() throws Exception {
        mockKusciaGrpcServer = new MockKusciaGrpcServer();
        // TLS 分支（非 NOTLS）会触发 MockKusciaGrpcServer.getServerBuilder()：
        // 用 classpath:certs/{server.crt,server.pem} 起服务端，用 classpath:certs/ca.crt 作为
        // 客户端证书的受信 CA（mock 服务端要求双向认证）。
        mockKusciaGrpcServer.start(TLS_PORT, KusciaProtocolEnum.TLS, null);
    }

    @AfterEach
    void tearDown() {
        mockKusciaGrpcServer.shutdown();
    }

    @Test
    void correctCaFileConnectsSuccessfully() throws InterruptedException {
        KusciaGrpcConfig config = buildConfig("classpath:certs/ca.crt");
        GrpcKusciaApiChannelFactory factory = new GrpcKusciaApiChannelFactory(config);
        try {
            ManagedChannel channel = factory.getChannel();
            Health.HealthResponse resp = HealthServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .healthZ(Health.HealthRequest.newBuilder().build());
            assertEquals(0, resp.getStatus().getCode(),
                    "handshake with the correct CA must succeed and the RPC must complete: " + resp.getStatus());
        } finally {
            factory.shutdown();
        }
    }

    @Test
    void wrongCaFileIsRejectedAtHandshake() {
        // wrong-ca.crt is an unrelated, valid CA that never signed the mock server's certificate.
        // Before P0-2 (InsecureTrustManagerFactory) this CA would have been accepted just the same —
        // this assertion is the one that must fail on the pre-fix code.
        KusciaGrpcConfig config = buildConfig("classpath:certs/wrong-ca.crt");
        GrpcKusciaApiChannelFactory factory = new GrpcKusciaApiChannelFactory(config);
        try {
            ManagedChannel channel = factory.getChannel();
            StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                    HealthServiceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .healthZ(Health.HealthRequest.newBuilder().build()));
            assertTrue(
                    ex.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE
                            || ex.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED,
                    "handshake with an unrelated CA must fail the TLS handshake, got: " + ex.getStatus());
        } finally {
            factory.shutdown();
        }
    }

    private KusciaGrpcConfig buildConfig(String caFile) {
        return KusciaGrpcConfig.builder()
                .domainId("alice")
                .host(MockKusciaGrpcServer.HOST)
                .port(TLS_PORT)
                .mode(KusciaModeEnum.P2P)
                .protocol(KusciaProtocolEnum.TLS)
                .certFile("classpath:certs/client.crt")
                .keyFile("classpath:certs/client.pem")
                .caFile(caFile)
                .token(MockKusciaGrpcServer.TOKEN)
                .build();
    }
}
