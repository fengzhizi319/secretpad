#!/bin/bash
#
# SecretPad 后端开发启动脚本（对接本地源码编译的 Kuscia）
#
# 用法：
#   bash scripts/dev-start-local-kuscia.sh
#
# 前置条件：
#   1. 本地 Kuscia 已经运行并暴露端口：
#      - master KusciaAPI gRPC: 127.0.0.1:18083
#      - alice KusciaAPI gRPC: 127.0.0.1:28083
#      - bob   KusciaAPI gRPC: 127.0.0.1:38083
#      - Kuscia Gateway (Envoy Internal): 127.0.0.1:13081
#   2. 如果本地 Kuscia 使用不同端口，请修改 config/application-dev.yaml 或设置环境变量。
#
# 本脚本会：
#   1. 生成证书与初始化数据库（scripts/test/setup.sh）
#   2. 编译后端 fat jar
#   3. 使用 dev profile 启动后端，连接本地 Kuscia

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${PROJECT_ROOT}"

echo "==> 生成证书与初始化数据库..."
bash scripts/test/setup.sh

echo "==> 编译后端 fat jar..."
mvn clean install -Dmaven.test.skip=true

echo "==> 启动 SecretPad 后端（连接本地 Kuscia）..."
export KUSCIA_API_ADDRESS=127.0.0.1
export KUSCIA_GW_ADDRESS=127.0.0.1:13081
export KUSCIA_PROTOCOL=notls

java -Dspring.profiles.active=dev \
     -Dsun.net.http.allowRestrictedHeaders=true \
     -Dserver.port=8443 \
     -jar target/secretpad.jar
