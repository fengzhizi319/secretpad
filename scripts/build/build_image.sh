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
# SecretPad Docker 镜像构建脚本
# ============================================================================
# 功能说明:
#   1. 自动构建后端应用（含前端资源）
#   2. 生成语义化的 Docker 镜像标签（版本+时间+commit）
#   3. 使用 Docker Buildx 构建多架构镜像（ARM64 + AMD64）
#   4. 基于 Anolis OS 基础镜像打包
#
# 使用方法:
#   ./scripts/build/build_image.sh
#
# 前置条件:
#   - 已安装 Docker 和 Docker Buildx 插件
#   - 已配置 Git 仓库（需要 tag 信息）
#   - 有足够的磁盘空间存储多架构镜像
#
# 输出产物:
#   secretpad:{VERSION}-{DATETIME}-{COMMIT_ID}
#   例如: secretpad:v1.2.0-20231215143025-a1b2c3
# ============================================================================

# set -e: 遇到错误立即退出，确保构建的原子性
set -e

# ----------------------------------------------------------------------------
# 步骤1: 构建后端应用（包含前端静态资源）
# ----------------------------------------------------------------------------
# github_flag=true: 启用前端集成模式
# 这会触发 build.sh 下载最新的前端预编译产物并打包到 jar 中
github_flag=true
./scripts/build/build.sh ${github_flag}

# ----------------------------------------------------------------------------
# 步骤2: 生成 Docker 镜像标签
# ----------------------------------------------------------------------------
# 标签格式: {GIT_TAG}-{YYYYMMDDHHmmss}-{SHORT_COMMIT_ID}
# 示例: v1.2.0-20231215143025-a1b2c3
#
# 设计理念:
#   - GIT_TAG: 语义化版本号，便于追溯功能版本
#   - DATETIME: 精确到秒的时间戳，区分同版本的多次构建
#   - COMMIT_ID: Git 提交哈希前6位，精确定位代码版本

# 2.1 获取当前时间戳
# 格式: YYYYMMDDHHmmSS（年月日时分秒）
# 用途: 确保每次构建的镜像标签唯一，避免覆盖
DATETIME=$(date +"%Y%m%d%H%M%S")

# 2.2 拉取远程 Git 标签
# --tags: 获取所有标签引用
# 原因: 确保本地有最新的 tag 信息（特别是 CI/CD 环境）
git fetch --tags

# 2.3 获取最新的 Git 标签版本号
# git rev-list --tags --max-count=1: 列出所有tag对应的commit，取最近的一个
# git describe --tags <commit>: 根据commit找到对应的tag名称
# shellcheck disable 注释说明: 抑制 shellcheck 对命令替换的警告
# shellcheck disable=SC2046
# shellcheck disable=SC2006
VERSION_TAG="$(git describe --tags $(git rev-list --tags --max-count=1))"

# 2.4 获取当前 commit 的短哈希值
# git log -n 1 --pretty=oneline: 显示最近一次提交的完整信息
# awk '{print $1}': 提取第一列（完整的commit hash）
# cut -b 1-6: 截取前6个字符作为短哈希
# 示例: a1b2c3d4e5f6... -> a1b2c3
commit_id=$(git log -n 1 --pretty=oneline | awk '{print $1}' | cut -b 1-6)

# 2.5 组合完整的镜像标签
tag=${VERSION_TAG}-${DATETIME}-"${commit_id}"

# 2.6 定义本地镜像名称
tag=${VERSION_TAG}-${DATETIME}-"${commit_id}"
local_image=secretpad:${tag}

# 打印 commit ID 用于调试和日志追踪
echo "$commit_id"

# ----------------------------------------------------------------------------
# 步骤3: 配置 Docker Buildx 构建器
# ----------------------------------------------------------------------------
# Docker Buildx 是 Docker 的下一代构建工具，支持:
#   - 多架构并行构建（ARM64、AMD64等）
#   - 高级缓存策略
#   - 自定义构建驱动
#
# 为什么需要 Buildx？
#   传统 docker build 只能构建单一架构镜像
#   Buildx 可以同时构建多个架构，生成 manifest list

# 3.1 检查是否已存在名为 secretpad_image_buildx 的构建器
# docker buildx inspect: 检查指定构建器的状态
# >/dev/null 2>&1: 重定向标准输出和错误输出到空设备（不显示结果）
# echo $?: 获取上一个命令的退出码（0=存在，非0=不存在）
BUILDER_EXISTS=$(
	docker buildx inspect secretpad_image_buildx >/dev/null 2>&1
	echo $?
)

# 3.2 根据检查结果决定使用已有构建器还是创建新的
if [ "$BUILDER_EXISTS" -eq 0 ]; then
	# 构建器已存在，直接切换到该构建器
	echo "existing buildx builder: secretpad_image_buildx"
	docker buildx use secretpad_image_buildx
else
	# 构建器不存在，创建新的构建器
	# --name: 指定构建器名称
	# --use: 创建后立即切换使用该构建器
	echo "creating new buildx builder: secretpad_image_buildx"
	docker buildx create --name secretpad_image_buildx --use
fi

# ----------------------------------------------------------------------------
# 步骤4: 执行多架构 Docker 镜像构建
# ----------------------------------------------------------------------------
# 仅当 github_flag 为 true 时执行构建
# 这个判断预留了扩展性，未来可以根据不同环境使用不同的构建策略
if [[ "$github_flag" == "true" ]]; then
	echo "github_flag is true"
	
	# 4.1 使用 docker buildx build 执行构建
	docker buildx build \
		# 指定目标平台架构（多架构支持）
		# linux/arm64: Apple Silicon M1/M2、AWS Graviton、华为鲲鹏等
		# linux/amd64: Intel/AMD x86_64 处理器，最常见的服务器架构
		# 优势: 一次构建，多平台部署，无需分别构建
		--platform linux/arm64,linux/amd64 \
		
		# 设置镜像标签
		# 格式: secretpad:v1.2.0-20231215143025-a1b2c3
		--tag "${local_image}" \
		
		# 指定 Dockerfile 路径
		# ./build/Dockerfiles/anolis.Dockerfile: 基于龙蜥操作系统（Anolis OS）
		# Anolis OS 是阿里云开源的企业级 Linux 发行版，兼容 CentOS
		# 特点: 安全性高、性能优化、长期支持
		-f ./build/Dockerfiles/anolis.Dockerfile . \
		
		# --load: 将构建结果加载到本地 Docker daemon
		# 默认情况下，buildx 只构建不加载（适合推送到 registry）
		# --load 参数使得构建完成后可以直接在本地运行和测试
		# 其他可选参数:
		#   --push: 直接推送到远程镜像仓库
		#   --output type=tar: 导出为 tar 文件
		--load
fi
