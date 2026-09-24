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
package org.secretflow.secretpad.web;


import org.secretflow.secretpad.web.configuration.InnerPortMtlsConfig;
import org.secretflow.secretpad.web.configuration.InnerPortSslConnectorFactory;

import com.google.common.collect.Lists;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * SecretPad application
 *
 * @author yansi
 * @date 2023/3/23
 */
@Slf4j
@ComponentScan(basePackages = {"org.secretflow.secretpad.*"})
@SpringBootApplication
@EnableAsync
@EnableCaching
public class SecretPadApplication {

    @Value("${server.http-port}")
    private Integer httpPort;
    @Value("${server.http-port-inner}")
    private Integer innerHttpPort;
    @Value("${server.compression.mime-types}")
    private String mimeTypes;
    @Value("${server.compression.min-response-size}")
    private Integer compressionMinResponseSize;

    @Resource
    private InnerPortMtlsConfig innerPortMtlsConfig;

    public static void main(String[] args) throws UnknownHostException {
        ConfigurableApplicationContext context = SpringApplication.run(SecretPadApplication.class, args);
        Environment environment = context.getBean(Environment.class);
        printEnvironment(environment);
    }

    private static void printEnvironment(Environment environment) throws UnknownHostException {
        log.info(startupBanner(environment, InetAddress.getLocalHost().getHostAddress()));
    }

    /**
     * 启动横幅文本。安全整改 P0-1：历史横幅打印 {@code userName:admin password:12345678}，
     * 任何能读日志的人都能拿到管理员口令。横幅现在只含地址、端口与 profile，
     * 口令的唯一出口是 {@code secretpad.auth.initial-password-file}（见 DbDataInit）。
     * 独立成方法是为了让用例能断言"横幅里没有口令"。
     */
    static String startupBanner(Environment environment, String hostAddress) {
        return String.format("SecretPad start success, http://%s:%s innerHttpPort:%s Profile:%s; "
                        + "initial admin credentials (first start only): see file %s",
                hostAddress,
                environment.getProperty("server.port"),
                environment.getProperty("server.http-port-inner"),
                Arrays.toString(environment.getActiveProfiles()),
                environment.getProperty("secretpad.auth.initial-password-file", "./config/initial-admin-password"));
    }

    /**
     * Build tomcat servlet webServer factory
     *
     * @return tomcat servlet webServer factory
     */
    @Bean
    public ServletWebServerFactory containerFactory() {
        // 安全整改（docs/secretpad_auth.md P0-3）：内网 RPC 口是否升级为 mTLS 在这里做一次性拒绝检查——
        // 「配了就必须生效，否则报错」，不允许 enabled=true 却缺材料，静默退回明文。
        innerPortMtlsConfig.validate();
        if (!innerPortMtlsConfig.isEnabled()) {
            log.warn("secretpad.inner-port.mtls.enabled=false: the inner RPC port ({}) trusts the "
                            + "'kuscia-origin-source' header alone, which any caller on the same network "
                            + "segment can forge. See docs/secretpad_auth.md P0-3 before exposing this port "
                            + "beyond a fully trusted network.",
                    innerHttpPort);
        }
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        buildConnector(tomcat, httpPort, false);
        buildConnector(tomcat, innerHttpPort, true);
        tomcat.setUriEncoding(StandardCharsets.UTF_8);
        return tomcat;
    }

    private void buildConnector(TomcatServletWebServerFactory tomcat, Integer port, boolean isInner) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setProperty(ConnectorCompression.COMPRESSION, "on");
        connector.setProperty(ConnectorCompression.COMPRESSION_MIN_RESPONSE_SIZE, String.valueOf(compressionMinResponseSize));
        connector.setProperty(ConnectorCompression.COMPRESSION_MIME_TYPES, mimeTypes);
        if (isInner && innerPortMtlsConfig.isEnabled()) {
            InnerPortSslConnectorFactory.applyMtls(connector, innerPortMtlsConfig);
        }
        tomcat.addAdditionalTomcatConnectors(connector);
    }

    private static class ConnectorCompression {
        static final String COMPRESSION_MIN_RESPONSE_SIZE = "compressionMinResponseSize";
        static final String COMPRESSION_MIME_TYPES = "compressionMimeTypes";
        static final String COMPRESSION = "compression";
    }

    /**
     * Build a new http message converters
     *
     * @return a new http message converters
     */
    @Bean
    public HttpMessageConverters protobufHttpMessageConverter() {
        ProtobufHttpMessageConverter protobufHttpMessageConverter = new ProtobufHttpMessageConverter();
        protobufHttpMessageConverter.setSupportedMediaTypes(Lists.newArrayList(MediaType.APPLICATION_JSON, MediaType.parseMediaType(MediaType.TEXT_PLAIN_VALUE + ";charset=ISO-8859-1")));
        return new HttpMessageConverters(protobufHttpMessageConverter);
    }
}
