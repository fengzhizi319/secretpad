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
package org.secretflow.secretpad.web.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * @author yutu
 * @date 2024/08/26
 */
@Slf4j
@Aspect
@Component
public class LoggingAspect {

    @Before("execution(* org.secretflow.secretpad.web.controller..*.*(..))")
    public void logRequest(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        // 安全整改（二次评审，见 docs/secretpad_auth.md §8，日志卫生）：本切面对仓库里每一个
        // controller 方法的入参都会 toString() 后整段打进 INFO 日志——这对结构化 DTO 而言，敏感字段
        // 尚可靠各 DTO 自身的 @ToString.Exclude 兜底（如 UserUpdatePwdRequest/ResetNodeUserPwdRequest
        // 的哈希字段），但对裸 String/MultipartFile 入参完全失效：InstController.registerNode 的
        // jsonData 是携带 Kuscia 入网 token 的原始 JSON，DataSyncController.sync 的 p 是任意允许表的
        // 整行 JSON——这两个入口此前会把明文 token/凭据原样打进日志，且与请求内容是什么 DTO 无关。
        // 这里统一只打印裸字符串的长度，不打印内容，堵住"新增任何裸字符串入参"这一整类未来风险，
        // 而不是逐个入口打补丁。
        Object[] safeArgs = Arrays.stream(args).map(LoggingAspect::redactRawString).toArray();
        log.info("Executing: {}", joinPoint.getSignature() + ", Args: " + Arrays.toString(safeArgs));
    }

    private static Object redactRawString(Object arg) {
        if (arg instanceof String s) {
            return "<redacted string, length=" + s.length() + ">";
        }
        return arg;
    }

    @AfterReturning(pointcut = "execution(* org.secretflow.secretpad.web.controller..*.*(..))", returning = "result")
    public void logResponse(JoinPoint joinPoint, Object result) {
        if (result instanceof ResponseEntity) {
            log.info("Returning from: {}", joinPoint.getSignature() + ", Response: " + result);
        }
    }
}