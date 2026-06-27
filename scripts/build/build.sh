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
# 步骤2: 如果需要集成前端，则下载并解压预编译的前端产物
# ----------------------------------------------------------------------------
# 前端与后端采用分离式架构：
#   - 前端代码在独立仓库维护（https://github.com/fengzhizi319/secretpad-frontend.git）
#   - 前端构建产物以 tar 包形式发布到 OSS
#   - 后端构建时动态下载最新版本的tar包并解压到 static 目录
#   - Spring Boot 会自动将 src/main/resources/static 下的文件作为静态资源服务
if [[ $WITH_FRONTEND_FLAG == true ]]; then
	# 2.1 获取项目根目录的绝对路径
	# ${BASH_SOURCE[0]} 是当前脚本的路径
	# dirname 获取脚本所在目录，cd ../../ 回到项目根目录
	ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../" && pwd -P)
	
	# 2.2 查询前端仓库的最新版本标签（tag）
	# git ls-remote --refs --tags: 列出远程仓库的所有标签引用
	# --sort='version:refname': 按版本号排序（支持语义化版本）
	# tail -n1: 取最后一个（即最新版本）
	# sed 's/.*\///': 提取标签名（去除 refs/tags/ 前缀）
	FRONTEND_LATEST_TAG=$(git ls-remote --sort='version:refname' --refs --tags https://github.com/fengzhizi319/secretpad-frontend.git | tail -n1 | sed 's/.*\///')
	
	# 2.3 创建工作目录用于临时存放前端文件
	WORK_DIR="./tmp/frontend"
	mkdir -p $WORK_DIR
	
	# 2.4 从阿里云 OSS 下载前端预编译产物
	# OSS 地址: https://secretflow-public.oss-cn-hangzhou.aliyuncs.com/secretpad-frontend/
	# 文件名格式: {TAG}.tar（例如: v1.0.0.tar）
	# 优势: 
	#   - 避免在前端源码纳入后端仓库，保持仓库轻量化
	#   - 前端可以独立发布，后端只需引用对应版本
	#   - 利用 CDN 加速下载
	wget -O $WORK_DIR/frontend.tar https://secretflow-public.oss-cn-hangzhou.aliyuncs.com/secretpad-frontend/"${FRONTEND_LATEST_TAG}".tar
	
	# 2.5 解压前端 tar 包
	# -xvf: 解压并显示详细过程
	# -C ${WORK_DIR}: 指定解压目标目录
	# --strip-components=1: 去除压缩包内第一层目录结构
	#   例如: archive/apps/platform/dist -> apps/platform/dist
	tar -xvf $WORK_DIR/frontend.tar -C ${WORK_DIR} --strip-components=1
	
	# 2.6 定位前端构建产物的输出目录
	# frontend-src 是 Monorepo 结构，platform 是主应用
	# dist 目录包含 Vite/Webpack 构建后的静态文件（HTML、CSS、JS等）
	DIST_DIR="$WORK_DIR/apps/platform/dist"
	
	# 2.7 确定后端静态资源目标目录
	# Spring Boot 约定: src/main/resources/static 下的文件可通过 / 路径访问
	# 例如: static/index.html 可通过 http://localhost:8080/ 访问
	TARGET_DIR="${ROOT}/secretpad-web/src/main/resources/static"
	mkdir -p "${TARGET_DIR}"
	
	# 2.8 复制前端构建产物到后端静态资源目录
	# -r: 递归复制目录
	# -p: 保留文件属性（权限、时间戳）
	# -f: 强制覆盖已存在的文件
	cp -rpf $DIST_DIR/* "${TARGET_DIR}"
	
	# 2.9 清理临时工作目录
	# 避免残留文件占用磁盘空间
	rm -rf "$WORK_DIR"
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
