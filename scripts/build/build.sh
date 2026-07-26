#!/bin/bash
#
# Copyright 2023 Ant Group Co., Ltd.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# ============================================================================
# SecretPad 后端构建脚本
# ============================================================================
# 功能说明:
#   1. 可选地集成前端静态资源到后端项目中
#   2. 使用 Maven 编译打包后端 Java 应用
#   3. 生成可执行的 fat jar 包
#
# 使用方法:
#   ./scripts/build/build.sh [WITH_FRONTEND]
#   - WITH_FRONTEND: true/false，是否集成前端资源（默认 false）
#
# 示例:
#   ./scripts/build/build.sh        # 仅构建后端
#   ./scripts/build/build.sh true   # 构建后端并集成前端
# ============================================================================

# set -e: 遇到错误立即退出，防止错误累积
set -e

# ----------------------------------------------------------------------------
# 步骤1: 解析命令行参数
# ----------------------------------------------------------------------------
# 获取第一个参数：是否集成前端资源的标志
# 如果未提供参数，默认为 false（不集成前端）
WITH_FRONTEND_FLAG=$1

if [[ $WITH_FRONTEND_FLAG == "" ]]; then
	WITH_FRONTEND_FLAG=false
fi

# ----------------------------------------------------------------------------
# 步骤2: 如果需要集成前端，则本地构建 secretpad/web 并复制产物到后端 static 目录
# ----------------------------------------------------------------------------
# 前端与后端采用同仓并列隔离（Monorepo）架构：
#   - 前端代码位于 secretpad/web/，与后端同仓库但独立构建
#   - 生产打包时先在前端目录执行 pnpm build，再把 dist/* 复制到
#     secretpad-web/src/main/resources/static/
#   - Spring Boot 会自动将 src/main/resources/static 下的文件作为静态资源服务
if [[ $WITH_FRONTEND_FLAG == true ]]; then
	# 2.1 获取项目根目录的绝对路径
	ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../" && pwd -P)

	# 2.2 前端工程目录与产物目录
	FRONTEND_DIR="${FRONTEND_DIR:-${ROOT}/web}"
	DIST_DIR="${FRONTEND_DIR}/apps/secretpad/dist"
	TARGET_DIR="${ROOT}/secretpad-web/src/main/resources/static"

	if [[ ! -d "${FRONTEND_DIR}" ]]; then
		echo "[ERROR] 前端工程目录不存在: ${FRONTEND_DIR}"
		exit 1
	fi

	if ! command -v corepack >/dev/null 2>&1; then
		echo "[ERROR] 未找到 corepack，无法安装/执行 pnpm"
		exit 1
	fi

	echo "[INFO] 正在本地构建前端: ${FRONTEND_DIR} ..."
	(
		cd "${FRONTEND_DIR}"
		corepack pnpm install
		corepack pnpm run build
	)

	if [[ ! -d "${DIST_DIR}" ]]; then
		echo "[ERROR] 前端构建产物目录不存在: ${DIST_DIR}"
		exit 1
	fi

	echo "[INFO] 复制前端产物到 ${TARGET_DIR} ..."
	mkdir -p "${TARGET_DIR}"
	rm -rf "${TARGET_DIR:?}"/*
	cp -rpf "${DIST_DIR}"/* "${TARGET_DIR}/"
fi

# ----------------------------------------------------------------------------
# 步骤3: 验证构建环境
# ----------------------------------------------------------------------------
# 检查 Maven 版本，确保构建工具可用
mvn -version

# 检查 Java 版本，确认 JDK 环境正确（SecretPad 需要 JDK 17+）
java -version

# ----------------------------------------------------------------------------
# 步骤4: 使用 Maven 编译打包后端项目
# ----------------------------------------------------------------------------
# mvn clean package: 清理旧构建产物并重新打包
#   - clean: 删除 target/ 目录，确保干净构建
#   - package: 编译、测试（跳过）、打包成 jar/war
#
# -DskipTests: 跳过单元测试
#   原因: 
#     1. 加速构建流程（测试通常在 CI 阶段单独运行）
#     2. 避免测试依赖的外部服务不可用导致构建失败
#     3. 开发阶段快速迭代不需要每次都跑测试
#
# -Dfile.encoding=UTF-8: 设置文件编码为 UTF-8
#   原因:
#     1. 确保跨平台一致性（Windows/macOS/Linux）
#     2. 正确处理中文注释和资源文件
#     3. 避免乱码问题
#
# 最终产物:
#   secretpad-manager/target/secretpad-manager-{version}.jar
#   这是一个 fat jar（包含所有依赖），可直接运行:
#   java -jar secretpad-manager-{version}.jar
mvn clean package -DskipTests -Dfile.encoding=UTF-8
