#!/bin/bash
#
# ============================================================================
# SecretPad 本地开发环境一键启动脚本
# ============================================================================
#
# 功能概述:
#   本脚本提供完整的本地开发环境自动化部署能力，包括:
#   1. 自动检测并安装缺失的运行时依赖（JDK、Maven、Node.js）
#   2. 部署 Kuscia Docker 环境（master + alice + bob 三节点）
#   3. 编译并启动 SecretPad 后端服务（Spring Boot fat jar）
#   4. 启动前端开发服务器（Umi dev server with HMR）
#   5. 端口冲突检测与智能处理
#   6. 进程管理与优雅停止支持
#
# 架构说明:
#   ┌─────────────────────────────────────────────┐
#   │          用户浏览器 (访问 localhost:8000)     │
#   └──────────────────┬──────────────────────────┘
#                      │ HTTP/WS
#   ┌──────────────────▼──────────────────────────┐
#   │      前端开发服务器 (Umi, port 8000)         │
#   │      - Hot Module Replacement (HMR)         │
#   │      - API 代理到后端                        │
#   └──────────────────┬──────────────────────────┘
#                      │ HTTP/HTTPS
#   ┌──────────────────▼──────────────────────────┐
#   │      后端服务 (Spring Boot, port 8080/8443)  │
#   │      - RESTful API                          │
#   │      - gRPC Client → Kuscia API             │
#   │      - Swagger UI                           │
#   └──────────────────┬──────────────────────────┘
#                      │ gRPC (notls)
#   ┌──────────────────▼──────────────────────────┐
#   │      Kuscia Master (port 18083)              │
#   │      ├─ Alice Domain (隐私计算参与方A)       │
#   │      └─ Bob Domain (隐私计算参与方B)         │
#   └─────────────────────────────────────────────┘
#
# 使用方法:
#   bash scripts/dev-start.sh          # 完整启动流程
#   bash scripts/dev-start.sh --check  # 仅检查并安装运行时
#   bash scripts/dev-start.sh --help   # 显示帮助信息
#
# 停止服务:
#   bash scripts/dev-stop.sh           # 停止后端和前端
#   bash scripts/dev-stop.sh --kuscia  # 同时停止 Kuscia 容器
#
# 前置条件:
#   - Linux/macOS 操作系统（需要 bash）
#   - 网络连接（用于下载依赖和镜像）
#   - 足够的磁盘空间（至少 10GB）
#   - Docker 已安装且当前用户有权限执行 docker 命令
#
# 输出产物:
#   - logs/backend.log    : 后端运行日志
#   - logs/frontend.log   : 前端运行日志
#   - logs/backend.pid    : 后端进程ID
#   - logs/frontend.pid   : 前端进程ID
#   - target/secretpad.jar: 后端可执行jar包
# ============================================================================

# set -euo pipefail: Bash严格模式
#   -e: 遇到错误立即退出
#   -u: 使用未定义变量时报错
#   -o pipefail: 管道中任一命令失败则整个管道失败
set -euo pipefail

# 获取项目根目录的绝对路径
# ${BASH_SOURCE[0]}: 当前脚本的路径
# dirname: 获取脚本所在目录（scripts/）
# cd ..: 回到上一级（项目根目录）
# pwd: 获取绝对路径
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 日志目录配置
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

# ------------------------------------------------------------------
# 颜色与日志系统
# ------------------------------------------------------------------
# ANSI 转义码定义，用于终端彩色输出
# 格式: \033[{属性};{前景色}m
RED='\033[0;31m'      # 红色：错误信息
GREEN='\033[0;32m'    # 绿色：成功/信息
YELLOW='\033[1;33m'   # 黄色：警告
BLUE='\033[0;34m'     # 蓝色：步骤提示
NC='\033[0m'          # No Color: 重置颜色

# 日志函数：统一格式化输出
# 优势:
#   1. 视觉层次清晰，快速定位问题类型
#   2. 支持 grep 过滤特定级别的日志
#   3. log_error 重定向到 stderr，便于错误捕获
log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
log_step() { echo -e "${BLUE}[STEP]${NC} $*"; }

# ------------------------------------------------------------------
# 工具函数库
# ------------------------------------------------------------------
# 设计模式: 实用工具函数集合，提供版本比较、命令检测等基础能力

version_ge() {
    # 版本号比较函数：判断 $1 >= $2
    # 实现原理:
    #   printf '%s\n%s\n' "$2" "$1": 将两个版本号按行输出（注意顺序：$2在前）
    #   sort -V: 按版本号排序（Version sort，支持语义化版本如 1.10 > 1.9）
    #   -C: 检查输入是否已排序，如果是则返回0，否则返回1
    #
    # 示例:
    #   version_ge "17.0.11" "17"  → 返回 0 (true)
    #   version_ge "16.14.0" "17"  → 返回 1 (false)
    #
    # 为什么不用 [[ ]] 直接比较？
    #   字符串比较 "17.0.11" >= "17" 会得到错误结果
    #   sort -V 能正确处理多段版本号
    printf '%s\n%s\n' "$2" "$1" | sort -V -C
}

command_exists() {
    # 检测命令是否存在于 PATH 中
    # command -v: POSIX 标准的命令检测方式（比 which 更可靠）
    # >/dev/null 2>&1: 丢弃所有输出，只关心退出码
    # 返回: 0=存在, 1=不存在
    command -v "$1" >/dev/null 2>&1
}

get_java_version() {
    # 提取 Java 版本号
    # java -version 输出示例:
    #   openjdk version "17.0.11" 2024-04-16
    #   OpenJDK Runtime Environment Temurin-17.0.11+9 (build 17.0.11+9)
    #
    # awk -F '"': 以双引号为分隔符
    # /version/: 匹配包含 version 的行
    # {print $2}: 输出第二个字段（即版本号）
    # head -1: 只取第一行（避免多行输出）
    java -version 2>&1 | awk -F '"' '/version/ {print $2}' | head -1
}

get_mvn_version() {
    # 提取 Maven 版本号
    # mvn -version 输出示例:
    #   Apache Maven 3.9.12 (bef951f85a4fc4eadb42ba0ca6c8a0a3bfbb1052)
    #
    # head -1: 取第一行
    # grep -oE '[0-9]+(\.[0-9]+)+': 提取版本号模式（数字.数字.数字...）
    # head -1: 只取第一个匹配（避免匹配到后面的日期等）
    mvn -version 2>&1 | head -1 | grep -oE '[0-9]+(\.[0-9]+)+' | head -1
}

get_node_version() {
    # 提取 Node.js 版本号（去除 v 前缀）
    # node -v 输出: v20.14.0
    # sed 's/^v//': 删除开头的 v 字符
    node -v 2>/dev/null | sed 's/^v//'
}

get_pnpm_version() {
    # 获取 pnpm 版本号
    # corepack pnpm -v: 通过 corepack 管理的 pnpm
    # 2>/dev/null || true: 如果失败不报错，返回空字符串
    corepack pnpm -v 2>/dev/null || true
}

get_docker_version() {
    # 提取 Docker 版本号
    # docker --version 输出: Docker version 24.0.7, build afdd53b
    # grep -oE '[0-9]+(\.[0-9]+)+': 提取版本号
    # head -1: 取第一个匹配
    docker --version 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | head -1
}

# ------------------------------------------------------------------
# 自动安装模块：将运行时安装到 .tools/ 目录
# ------------------------------------------------------------------
# 设计理念:
#   1. 项目级隔离：每个项目独立的 .tools 目录，避免全局污染
#   2. 版本锁定：固定特定版本，确保团队环境一致性
#   3. 自动化：无需手动下载安装，降低新人上手门槛
#   4. 可移植性：.tools 在 .gitignore 中，不同平台可独立安装
#
# 目录结构:
#   .tools/
#   ├── jdk-17/           # JDK 17 (Temurin)
#   ├── maven/            # Maven 3.9.12
#   └── node/             # Node.js 20.14.0

install_jdk() {
    log_step "未检测到 JDK 17+，将自动安装到 .tools/jdk-17 ..."
    mkdir -p "$ROOT/.tools"
    
    # 下载 Adoptium Temurin JDK 17（Eclipse Adoptium 项目，原 AdoptOpenJDK）
    # 选择理由:
    #   - 开源免费，商业友好
    #   - 长期支持（LTS）版本
    #   - 性能优化好，生产环境广泛使用
    local url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.11_9.tar.gz"
    
    # curl 参数说明:
    #   -f: 失败时不输出 HTML 错误页面
    #   -s: 静默模式（不显示进度条）
    #   -S: 显示错误信息
    #   -L: 跟随重定向
    #   -o: 指定输出文件
    curl -fsSL -o "$ROOT/.tools/jdk17.tar.gz" "$url"
    
    # 解压到 .tools 目录
    tar -xzf "$ROOT/.tools/jdk17.tar.gz" -C "$ROOT/.tools"
    
    # 清理旧版本（如果存在）
    rm -rf "$ROOT/.tools/jdk-17"
    
    # 重命名为统一名称（去除版本号，便于后续引用）
    mv "$ROOT/.tools/jdk-17.0.11+9" "$ROOT/.tools/jdk-17"
    
    # 清理压缩包，节省空间
    rm -f "$ROOT/.tools/jdk17.tar.gz"
    
    # 设置环境变量（仅对当前 shell 会话有效）
    export JAVA_HOME="$ROOT/.tools/jdk-17"
    export PATH="$JAVA_HOME/bin:$PATH"
    
    log_info "JDK 17 安装完成：$JAVA_HOME"
}

install_maven() {
    log_step "未检测到 Maven 3.8.8+，将自动安装到 .tools/maven ..."
    mkdir -p "$ROOT/.tools"
    
    # 从 Apache Archive 下载 Maven 3.9.12
    # 选择理由:
    #   - 最新稳定版本
    #   - 兼容 JDK 17+
    #   - 支持最新的 pom.xml 特性
    local url="https://archive.apache.org/dist/maven/maven-3/3.9.12/binaries/apache-maven-3.9.12-bin.tar.gz"
    
    curl -fsSL -o "$ROOT/.tools/maven.tar.gz" "$url"
    tar -xzf "$ROOT/.tools/maven.tar.gz" -C "$ROOT/.tools"
    
    rm -rf "$ROOT/.tools/maven"
    mv "$ROOT/.tools/apache-maven-3.9.12" "$ROOT/.tools/maven"
    rm -f "$ROOT/.tools/maven.tar.gz"
    
    # 将 Maven bin 目录加入 PATH
    export PATH="$ROOT/.tools/maven/bin:$PATH"
    
    log_info "Maven 安装完成：$ROOT/.tools/maven"
}

install_node() {
    log_step "未检测到 Node.js 16.14.0+，将自动安装到 .tools/node ..."
    mkdir -p "$ROOT/.tools"
    
    # 下载 Node.js 20.14.0 LTS 版本
    # 选择理由:
    #   - LTS（长期支持）版本，稳定性高
    #   - 内置 corepack（pnpm 管理工具）
    #   - 兼容前端项目的所有依赖
    local url="https://nodejs.org/dist/v20.14.0/node-v20.14.0-linux-x64.tar.xz"
    
    # .tar.xz 是 xz 压缩格式，比 gzip 压缩率更高
    curl -fsSL -o "$ROOT/.tools/node.tar.xz" "$url"
    tar -xf "$ROOT/.tools/node.tar.xz" -C "$ROOT/.tools"
    
    rm -rf "$ROOT/.tools/node"
    mv "$ROOT/.tools/node-v20.14.0-linux-x64" "$ROOT/.tools/node"
    rm -f "$ROOT/.tools/node.tar.xz"
    
    export PATH="$ROOT/.tools/node/bin:$PATH"
    
    log_info "Node.js 安装完成：$ROOT/.tools/node"
}

# ------------------------------------------------------------------
# 环境检测与自动安装
# ------------------------------------------------------------------
# 功能说明:
#   检测所有必需的运行时依赖，如果缺失或版本过低则自动安装
#   采用“检测→安装→验证”的模式，确保环境符合要求
#
# 检测顺序:
#   1. Java (JDK 17+)     → 后端编译和运行必需
#   2. Maven (3.8.8+)     → 后端构建工具
#   3. Node.js (16.14.0+) → 前端开发和构建
#   4. pnpm (8.8.0)       → 前端包管理器（通过 corepack 管理）
#   5. Docker (20.10+)    → Kuscia 容器化部署
check_environment() {
    log_step "检查本地开发环境 ..."

    # ------------------------------------------------------------------
    # Java 环境检测
    # ------------------------------------------------------------------
    # 要求: JDK 17 或更高版本
    # 原因: SecretPad 使用 Java 17 的新特性（Records、Pattern Matching等）
    if command_exists java && version_ge "$(get_java_version)" "17"; then
        log_info "Java $(get_java_version) 已满足要求"
    else
        install_jdk
    fi

    # ------------------------------------------------------------------
    # Maven 环境检测
    # ------------------------------------------------------------------
    # 要求: Maven 3.8.8 或更高版本
    # 原因: 旧版本可能不支持某些 pom.xml 特性或插件
    if command_exists mvn && version_ge "$(get_mvn_version)" "3.8.8"; then
        log_info "Maven $(get_mvn_version) 已满足要求"
    else
        install_maven
    fi

    # ------------------------------------------------------------------
    # Node.js 环境检测
    # ------------------------------------------------------------------
    # 要求: Node.js 16.14.0 或更高版本
    # 原因: 前端项目依赖的最低 Node 版本要求
    if command_exists node && version_ge "$(get_node_version)" "16.14.0"; then
        log_info "Node.js $(get_node_version) 已满足要求"
    else
        install_node
    fi

    # ------------------------------------------------------------------
    # pnpm 环境检测（通过 corepack 管理）
    # ------------------------------------------------------------------
    # corepack: Node.js 16.10+ 内置的包管理器管理工具
    # 优势:
    #   1. 自动切换不同项目所需的包管理器版本
    #   2. 避免全局安装 pnpm 导致的版本冲突
    #   3. package.json 中的 packageManager 字段可锁定版本
    if command_exists corepack; then
        local pnpm_ver
        pnpm_ver="$(get_pnpm_version)"
        if [ "$pnpm_ver" = "8.8.0" ]; then
            log_info "pnpm $pnpm_ver（通过 corepack）已满足要求"
        else
            # 版本不匹配，重新安装指定版本
            log_warn "正在通过 corepack 安装 pnpm@8.8.0 ..."
            # 在 frontend-src 目录下执行，读取该目录的 packageManager 配置
            (cd "$ROOT/frontend-src" && corepack install)
        fi
    else
        # corepack 不存在，说明 Node.js 版本过旧
        log_error "未找到 corepack，请升级 Node.js 到 16.10+ 或手动安装 pnpm 8.8.0"
        exit 1
    fi

    # ------------------------------------------------------------------
    # Docker 环境检测
    # ------------------------------------------------------------------
    # 要求: Docker 20.10 或更高版本
    # 原因: Kuscia 部署需要较新的 Docker 特性（buildx、compose v2等）
    # 注意: Docker 必须手动安装（涉及系统级权限），脚本不提供自动安装
    if command_exists docker; then
        local docker_ver
        docker_ver="$(get_docker_version)"
        if version_ge "$docker_ver" "20.10.0"; then
            log_info "Docker $docker_ver 已满足要求"
        else
            log_error "需要 Docker 20.10+，当前版本 $docker_ver"
            exit 1
        fi
    else
        log_error "未找到 Docker，请手动安装 Docker >= 20.10"
        log_error "参考文档: https://docs.docker.com/get-docker/"
        exit 1
    fi
}

# ------------------------------------------------------------------
# 端口检测与管理
# ------------------------------------------------------------------
# 功能说明:
#   检测关键端口是否被占用，避免启动失败或端口冲突
#   采用智能判断：如果是自己之前启动的进程占用，则允许继续
#
# 关键端口列表:
#   - 8080: 后端 HTTP 服务（Spring Boot）
#   - 8443: 后端 HTTPS 服务（带 TLS 证书）
#   - 8000: 前端开发服务器（Umi dev server）
#   - 18080: Kuscia Gateway HTTP 端口
#   - 18082: Kuscia Gateway gRPC 端口
#   - 18083: Kuscia API gRPC 端口（master节点）
#   - 13081: Kuscia Envoy 内部代理端口

port_in_use() {
    # 检测指定端口是否正在监听
    # ss -tln: 显示所有 TCP 监听端口
    #   -t: TCP 协议
    #   -l: listening 状态
    #   -n: numeric（不解析服务名，加快速度）
    # grep -qE ":[[:space:]]*$1[[:space:]]": 精确匹配端口号
    #   [[:space:]]: 确保不会误匹配（如 8080 不会匹配到 18080）
    #   -q: quiet 模式，不输出匹配结果，只返回退出码
    ss -tln 2>/dev/null | grep -qE ":[[:space:]]*$1[[:space:]]"
}

port_pid() {
    # 获取占用指定端口的进程ID
    # ss -tlnp: 显示进程信息（-p 参数）
    # grep -oE 'pid=[0-9]+': 提取 pid=数字 格式
    # head -1: 只取第一个匹配（可能有多个连接）
    # cut -d= -f2: 以 = 为分隔符，取第二个字段（即 PID）
    ss -tlnp 2>/dev/null | grep -E ":[[:space:]]*$1[[:space:]]" | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2
}

read_pidfile() {
    # 读取 PID 文件内容
    # PID 文件存储了之前启动的进程ID，用于判断是否是自己启动的服务
    local f="$1"
    if [ -f "$f" ]; then
        cat "$f"
    fi
}

wait_for_port() {
    # 等待指定端口就绪（轮询检测）
    # 参数:
    #   $1: host - 主机地址（如 127.0.0.1）
    #   $2: port - 端口号
    #   $3: timeout_sec - 超时时间（秒），默认 60
    #   $4: what - 服务名称（用于日志提示）
    #
    # 实现原理:
    #   每秒检测一次端口是否开始监听，最多等待 timeout_sec 秒
    #   优势：比固定 sleep 更智能，服务就绪后立即继续
    local host="$1" port="$2" timeout_sec="${3:-60}" what="$4"
    log_info "等待 $what 就绪：$host:$port（最多 ${timeout_sec}s）..."
    for ((i = 0; i < timeout_sec; i++)); do
        if ss -tln 2>/dev/null | grep -qE ":[[:space:]]*$port[[:space:]]"; then
            log_info "$what 已就绪"
            return 0
        fi
        sleep 1
    done
    log_error "$what 在 $host:$port 上未就绪，请查看日志"
    return 1
}

check_required_ports() {
    log_step "检查关键端口占用情况 ..."

    # 读取之前保存的进程ID
    local backend_pid frontend_pid
    backend_pid="$(read_pidfile "$LOG_DIR/backend.pid")"
    frontend_pid="$(read_pidfile "$LOG_DIR/frontend.pid")"
    
    # 检测 Kuscia 是否在运行
    # docker ps --filter: 过滤容器
    # --format '{{.Names}}': 只输出容器名称
    # grep -q .: 检查是否有输出（即是否有匹配的容器）
    local kuscia_running=false
    if docker ps --filter "name=${USER}-kuscia-master" --format '{{.Names}}' | grep -q .; then
        kuscia_running=true
    fi

    local abort=false

    # ------------------------------------------------------------------
    # 后端端口检测（8080, 8443）
    # ------------------------------------------------------------------
    # 规则: 只能由我们记录的后端进程占用
    # 如果被其他进程占用，则拒绝启动（避免端口冲突）
    for p in 8080 8443; do
        if port_in_use "$p"; then
            local pid
            pid="$(port_pid "$p")"
            if [ -n "$backend_pid" ] && [ "$pid" = "$backend_pid" ]; then
                # 是自己之前启动的后端进程，允许继续
                log_info "端口 $p 已由当前后端进程占用"
            else
                # 被其他未知进程占用，报错
                log_error "端口 $p 被其他进程（pid ${pid:-unknown}）占用，无法启动后端"
                abort=true
            fi
        fi
    done

    # ------------------------------------------------------------------
    # 前端端口检测（8000）
    # ------------------------------------------------------------------
    if port_in_use 8000; then
        local pid
        pid="$(port_pid 8000)"
        if [ -n "$frontend_pid" ] && [ "$pid" = "$frontend_pid" ]; then
            log_info "端口 8000 已由当前前端进程占用"
        else
            log_error "端口 8000 被其他进程（pid ${pid:-unknown}）占用，无法启动前端"
            abort=true
        fi
    fi

    # ------------------------------------------------------------------
    # Kuscia 端口检测（18080, 18082, 18083, 13081）
    # ------------------------------------------------------------------
    # 规则: 只有在 Kuscia 未运行时被占用才需要报错
    # 如果 Kuscia 已经在运行，其端口占用是符合预期的
    if [ "$kuscia_running" = false ]; then
        for p in 18080 18082 18083 13081; do
            if port_in_use "$p"; then
                log_error "端口 $p 已被占用，无法部署 Kuscia"
                abort=true
            fi
        done
    else
        log_info "Kuscia 已在运行，其端口占用符合预期"
    fi

    # 如果有端口冲突，终止脚本
    if [ "$abort" = true ]; then
        log_error "请先释放占用端口，或执行 bash scripts/dev-stop.sh 清理残留进程"
        exit 1
    fi
}

# ------------------------------------------------------------------
# 进程管理工具函数
# ------------------------------------------------------------------
# 设计模式: 基于 PID 文件的进程生命周期管理
# 优势:
#   1. 精确控制：只管理自己启动的进程
#   2. 优雅停止：先 SIGTERM，再 SIGKILL
#   3. 幂等性：多次调用不会产生副作用

is_process_alive() {
    # 检测进程是否存活
    # kill -0 PID: 不发送信号，仅检查进程是否存在
    # 返回: 0=存活, 1=不存在或无权限
    local pid="$1"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

stop_service_by_pidfile() {
    # 根据 PID 文件停止服务
    # 参数:
    #   $1: pidfile - PID 文件路径
    #   $2: name - 服务名称（用于日志）
    #
    # 停止策略（优雅停止）:
    #   1. 发送 SIGTERM（允许进程清理资源）
    #   2. 等待 1 秒
    #   3. 如果还在运行，发送 SIGKILL（强制终止）
    #   4. 删除 PID 文件
    local pidfile="$1" name="$2"
    if [ -f "$pidfile" ]; then
        local pid
        pid="$(cat "$pidfile")"
        if is_process_alive "$pid"; then
            log_info "停止已运行的 $name（pid $pid）..."
            kill "$pid" 2>/dev/null || true  # SIGTERM
            sleep 1
            if is_process_alive "$pid"; then
                kill -9 "$pid" 2>/dev/null || true  # SIGKILL
            fi
        fi
        rm -f "$pidfile"
    fi
}

# ------------------------------------------------------------------
# 各服务启动函数
# ------------------------------------------------------------------
# 启动顺序（依赖关系）:
#   1. 生成证书 → 2. 编译后端 → 3. 启动Kuscia → 4. 启动后端 → 5. 启动前端
#
# 依赖说明:
#   - 后端依赖 Kuscia API（gRPC 连接）
#   - 前端依赖后端 API（HTTP 代理）
#   - 证书是 Kuscia TLS 通信和后端 HTTPS 的基础

generate_certs() {
    log_step "生成 KusciaAPI 证书与后端 JKS ..."
    # 调用测试环境设置脚本，生成:
    #   - Kuscia API TLS 证书（用于 gRPC 加密通信）
    #   - 后端 JKS 密钥库（用于 HTTPS 服务）
    # 脚本位置: scripts/test/setup.sh
    bash "$ROOT/scripts/test/setup.sh"
}

build_backend() {
    log_step "编译后端（生成 fat jar）..."
    
    # Maven 编译参数说明:
    #   clean: 清理之前的构建产物（target/目录）
    #   install: 编译、测试、打包并安装到本地仓库
    #   -Dmaven.test.skip=true: 跳过测试（加速构建，测试在CI中运行）
    #
    # 为什么用 install 而不是 package？
    #   install 会将 jar 安装到本地 Maven 仓库，其他模块可以引用
    mvn clean install -Dmaven.test.skip=true
    
    # 验证编译结果
    # fat jar 位置: target/secretpad.jar
    # 这是 Spring Boot Maven Plugin 生成的可执行jar包
    if [ ! -f "$ROOT/target/secretpad.jar" ]; then
        log_error "后端编译失败：未找到 target/secretpad.jar"
        exit 1
    fi
    log_info "后端编译完成"
}

start_kuscia() {
    log_step "检查 Kuscia Docker 环境 ..."
    
    # 检测 Kuscia master 容器是否在运行
    # docker ps --filter: 按名称过滤容器
    # --format '{{.Names}}': 只输出容器名称
    # grep -q .: 检查是否有输出
    if docker ps --filter "name=${USER}-kuscia-master" --format '{{.Names}}' | grep -q .; then
        log_info "Kuscia master 已在运行，跳过部署"
    else
        # 首次部署或容器已停止，需要重新部署
        log_info "正在部署 Kuscia（master + alice + bob）..."
        log_warn "如果脚本询问 'Whether to retain k3s data?(y/n):'，首次部署建议输入 n"
        
        # 调用 Kuscia 安装脚本
        # 参数说明:
        #   master: 部署模式（master节点）
        #   -P notls: 不使用 TLS（开发环境简化配置）
        #
        # 部署内容:
        #   - kuscia-master: 控制平面，管理所有域
        #   - kuscia-alice: Alice 域的 worker 节点
        #   - kuscia-bob: Bob 域的 worker 节点
        bash "$ROOT/scripts/install-kuscia-only.sh" master -P notls
    fi

    # 等待 Kuscia 关键端口就绪
    # 18083: Kuscia API gRPC 端口（后端通过此端口调用 Kuscia）
    # 13081: Kuscia Envoy 内部代理端口（数据面通信）
    # 超时时间: 180秒（Kuscia 启动较慢，需要拉取镜像、初始化k3s等）
    wait_for_port 127.0.0.1 18083 180 "Kuscia API gRPC"
    wait_for_port 127.0.0.1 13081 180 "Kuscia Envoy 内部端口"
}

start_backend() {
    log_step "启动后端服务 ..."
    local pidfile="$LOG_DIR/backend.pid"
    
    # 检查后端是否已在运行
    if [ -f "$pidfile" ] && is_process_alive "$(cat "$pidfile")"; then
        log_info "后端已在运行（pid $(cat "$pidfile")）"
        return 0
    fi
    
    # 如果 PID 文件存在但进程不存在，清理残留文件
    stop_service_by_pidfile "$pidfile" "backend"

    # 设置环境变量（后端启动时需要）
    export KUSCIA_API_ADDRESS=127.0.0.1      # Kuscia API 地址
    export KUSCIA_GW_ADDRESS=127.0.0.1:13081  # Kuscia Gateway 地址
    export KUSCIA_PROTOCOL=notls              # 通信协议（notls/tls）

    # 启动后端服务（后台运行）
    # nohup: 忽略 SIGHUP 信号（终端关闭后进程继续运行）
    # java 参数说明:
    #   -Dspring.profiles.active=dev: 激活 dev 配置文件（application-dev.yaml）
    #   -Dsun.net.http.allowRestrictedHeaders=true: 允许设置受限的 HTTP header（如 Host）
    #   -Dserver.port=8443: 设置 HTTPS 端口为 8443
    #   -jar: 运行可执行 jar 包
    # > "$LOG_DIR/backend.log" 2>&1: 重定向标准输出和错误输出到日志文件
    # &: 后台运行
    nohup java \
        -Dspring.profiles.active=dev \
        -Dsun.net.http.allowRestrictedHeaders=true \
        -Dserver.port=8443 \
        -jar "$ROOT/target/secretpad.jar" > "$LOG_DIR/backend.log" 2>&1 &
    
    # 保存进程ID到 PID 文件
    echo $! > "$pidfile"
    log_info "后端进程已启动，pid $!"

    # 等待后端 HTTP 端口就绪（8080）
    # 注意：虽然设置了 server.port=8443，但 Spring Boot actuator 可能仍在 8080
    # 超时时间: 120秒（Spring Boot 启动需要初始化数据库连接池、加载配置等）
    wait_for_port 127.0.0.1 8080 120 "后端 HTTP"
}

start_frontend() {
    log_step "启动前端开发服务器 ..."
    local pidfile="$LOG_DIR/frontend.pid"
    
    # 检查前端是否已在运行
    if [ -f "$pidfile" ] && is_process_alive "$(cat "$pidfile")"; then
        log_info "前端已在运行（pid $(cat "$pidfile")）"
        return 0
    fi
    
    stop_service_by_pidfile "$pidfile" "frontend"

    # 切换到前端源码目录
    cd "$ROOT/frontend-src"
    
    # 首次运行时安装依赖
    # node_modules 不存在说明是第一次启动或依赖被清理
    if [ ! -d "node_modules" ]; then
        log_info "首次运行，安装前端依赖 ..."
        # corepack pnpm bootstrap: 使用 corepack 管理的 pnpm 安装依赖
        # bootstrap 是项目自定义脚本，通常包括:
        #   - pnpm install: 安装所有依赖
        #   - 可能的额外初始化步骤
        corepack pnpm bootstrap
    fi

    # 启动前端开发服务器（后台运行）
    # corepack pnpm --filter secretpad dev:
    #   --filter secretpad: 在 monorepo 中只启动 secretpad 应用
    #   dev: 开发模式，支持 HMR（热模块替换）
    #
    # Umi dev server 特性:
    #   - 实时编译 TypeScript/React 代码
    #   - HMR: 修改代码后浏览器自动刷新（无需手动刷新）
    #   - API 代理: 将 /api 请求转发到后端（避免跨域问题）
    nohup corepack pnpm --filter secretpad dev > "$LOG_DIR/frontend.log" 2>&1 &
    echo $! > "$pidfile"
    log_info "前端进程已启动，pid $!"

    # 等待前端开发服务器就绪（8000端口）
    # 超时时间: 120秒（首次启动需要编译大量 TypeScript 文件）
    wait_for_port 127.0.0.1 8000 120 "前端开发服务器"
}

print_summary() {
    # 打印启动成功后的摘要信息
    # 包含所有服务的访问地址、账号信息和日志位置
    
    local frontend_url="http://localhost:8000"          # 前端开发服务器
    local backend_health="http://localhost:8080/actuator/health"  # 后端健康检查端点
    local backend_https="https://localhost:8443"        # 后端 HTTPS 服务

    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  SecretPad 本地开发环境已启动${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "🌐 前端开发服务器：${BLUE}${frontend_url}${NC}"
    echo -e "   （在支持的终端中可直接点击链接打开浏览器）"
    echo ""
    echo -e "🔧 后端健康检查：${BLUE}${backend_health}${NC}"
    echo -e "🔒 后端 HTTPS 地址：${BLUE}${backend_https}${NC}"
    echo ""
    echo -e "👤 登录账号：${YELLOW}admin / 12345678${NC}"
    echo ""
    echo -e "📄 日志文件："
    echo -e "   后端：$LOG_DIR/backend.log"
    echo -e "   前端：$LOG_DIR/frontend.log"
    echo ""
    echo -e "🛑 停止服务：${YELLOW}bash scripts/dev-stop.sh${NC}"
    echo -e "🛑 同时停止 Kuscia：${YELLOW}bash scripts/dev-stop.sh --kuscia${NC}"
    echo ""
}

# ------------------------------------------------------------------
# 命令行参数解析与主流程
# ------------------------------------------------------------------
# 支持的模式:
#   1. 默认模式（无参数）: 完整启动流程
#   2. --check/-c: 仅检查并安装运行时，不启动服务
#   3. --help/-h: 显示帮助信息
#
# 设计模式: case 语句实现命令分发
# ${1:-}: 获取第一个参数，如果不存在则为空字符串
case "${1:-}" in
--check | -c)
    # 仅检查环境模式
    # 用途: 提前验证环境是否符合要求，或预安装依赖
    check_environment
    echo ""
    log_info "环境检查通过"
    exit 0
    ;;
--help | -h)
    # 帮助信息模式
    # 使用 heredoc（cat <<EOF ... EOF）输出多行文本
    cat <<EOF
SecretPad 本地开发环境一键启动脚本

用法：
  bash scripts/dev-start.sh          完整启动（检查环境 → 编译 → 启动 Kuscia/后端/前端）
  bash scripts/dev-start.sh --check  仅检查并自动安装运行时
  bash scripts/dev-start.sh --help   显示本帮助

停止服务：
  bash scripts/dev-stop.sh
  bash scripts/dev-stop.sh --kuscia  # 同时停止 Kuscia 容器
EOF
    exit 0
    ;;
esac

# ------------------------------------------------------------------
# 主启动流程（默认模式）
# ------------------------------------------------------------------
# 执行顺序（严格按照依赖关系）:
#
# 步骤1: check_environment
#   - 检测 Java、Maven、Node.js、pnpm、Docker
#   - 自动安装缺失的运行时到 .tools/ 目录
#
# 步骤2: check_required_ports
#   - 检测 8080、8443、8000、18080-18083、13081 端口占用
#   - 智能判断是否是自己之前启动的进程
#
# 步骤3: generate_certs
#   - 生成 Kuscia API TLS 证书
#   - 生成后端 JKS 密钥库
#
# 步骤4: build_backend
#   - Maven 编译后端项目
#   - 生成 target/secretpad.jar (fat jar)
#
# 步骤5: start_kuscia
#   - 部署 Kuscia Docker 环境（master + alice + bob）
#   - 等待 gRPC 端口就绪（18083、13081）
#
# 步骤6: start_backend
#   - 设置环境变量（KUSCIA_API_ADDRESS等）
#   - 后台启动 Spring Boot 应用
#   - 等待 HTTP 端口就绪（8080）
#
# 步骤7: start_frontend
#   - 安装前端依赖（首次运行）
#   - 启动 Umi dev server（支持 HMR）
#   - 等待开发服务器就绪（8000）
#
# 步骤8: print_summary
#   - 打印访问地址、账号信息、日志位置
#
# 异常处理:
#   - set -euo pipefail: 任一命令失败立即退出
#   - 每个步骤都有详细的错误提示和解决建议
#   - 端口冲突时会提示执行 dev-stop.sh 清理
check_environment
check_required_ports
generate_certs
build_backend
start_kuscia
start_backend
start_frontend
print_summary
