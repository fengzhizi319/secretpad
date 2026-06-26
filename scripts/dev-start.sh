#!/bin/bash
#
# SecretPad 本地开发环境一键启动脚本
# 自动检测并安装缺失的运行时，然后依次启动：
#   1. Kuscia Docker 环境（master + alice + bob）
#   2. SecretPad 后端（Spring Boot）
#   3. SecretPad 前端（Umi dev server）
#
# 用法：
#   bash scripts/dev-start.sh
#
# 停止服务：
#   bash scripts/dev-stop.sh
#   bash scripts/dev-stop.sh --kuscia   # 同时停止 Kuscia 容器

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

# ------------------------------------------------------------------
# 颜色与日志
# ------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
log_step() { echo -e "${BLUE}[STEP]${NC} $*"; }

# ------------------------------------------------------------------
# 工具函数
# ------------------------------------------------------------------
version_ge() {
    # 如果 $1 >= $2 则返回 0
    printf '%s\n%s\n' "$2" "$1" | sort -V -C
}

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

get_java_version() {
    java -version 2>&1 | awk -F '"' '/version/ {print $2}' | head -1
}

get_mvn_version() {
    mvn -version 2>&1 | head -1 | grep -oE '[0-9]+(\.[0-9]+)+' | head -1
}

get_node_version() {
    node -v 2>/dev/null | sed 's/^v//'
}

get_pnpm_version() {
    corepack pnpm -v 2>/dev/null || true
}

get_docker_version() {
    docker --version 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | head -1
}

# ------------------------------------------------------------------
# 自动安装到 .tools/
# ------------------------------------------------------------------
install_jdk() {
    log_step "未检测到 JDK 17+，将自动安装到 .tools/jdk-17 ..."
    mkdir -p "$ROOT/.tools"
    local url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.11_9.tar.gz"
    curl -fsSL -o "$ROOT/.tools/jdk17.tar.gz" "$url"
    tar -xzf "$ROOT/.tools/jdk17.tar.gz" -C "$ROOT/.tools"
    rm -rf "$ROOT/.tools/jdk-17"
    mv "$ROOT/.tools/jdk-17.0.11+9" "$ROOT/.tools/jdk-17"
    rm -f "$ROOT/.tools/jdk17.tar.gz"
    export JAVA_HOME="$ROOT/.tools/jdk-17"
    export PATH="$JAVA_HOME/bin:$PATH"
    log_info "JDK 17 安装完成：$JAVA_HOME"
}

install_maven() {
    log_step "未检测到 Maven 3.8.8+，将自动安装到 .tools/maven ..."
    mkdir -p "$ROOT/.tools"
    local url="https://archive.apache.org/dist/maven/maven-3/3.9.12/binaries/apache-maven-3.9.12-bin.tar.gz"
    curl -fsSL -o "$ROOT/.tools/maven.tar.gz" "$url"
    tar -xzf "$ROOT/.tools/maven.tar.gz" -C "$ROOT/.tools"
    rm -rf "$ROOT/.tools/maven"
    mv "$ROOT/.tools/apache-maven-3.9.12" "$ROOT/.tools/maven"
    rm -f "$ROOT/.tools/maven.tar.gz"
    export PATH="$ROOT/.tools/maven/bin:$PATH"
    log_info "Maven 安装完成：$ROOT/.tools/maven"
}

install_node() {
    log_step "未检测到 Node.js 16.14.0+，将自动安装到 .tools/node ..."
    mkdir -p "$ROOT/.tools"
    local url="https://nodejs.org/dist/v20.14.0/node-v20.14.0-linux-x64.tar.xz"
    curl -fsSL -o "$ROOT/.tools/node.tar.xz" "$url"
    tar -xf "$ROOT/.tools/node.tar.xz" -C "$ROOT/.tools"
    rm -rf "$ROOT/.tools/node"
    mv "$ROOT/.tools/node-v20.14.0-linux-x64" "$ROOT/.tools/node"
    rm -f "$ROOT/.tools/node.tar.xz"
    export PATH="$ROOT/.tools/node/bin:$PATH"
    log_info "Node.js 安装完成：$ROOT/.tools/node"
}

# ------------------------------------------------------------------
# 环境检测
# ------------------------------------------------------------------
check_environment() {
    log_step "检查本地开发环境 ..."

    # Java
    if command_exists java && version_ge "$(get_java_version)" "17"; then
        log_info "Java $(get_java_version) 已满足要求"
    else
        install_jdk
    fi

    # Maven
    if command_exists mvn && version_ge "$(get_mvn_version)" "3.8.8"; then
        log_info "Maven $(get_mvn_version) 已满足要求"
    else
        install_maven
    fi

    # Node
    if command_exists node && version_ge "$(get_node_version)" "16.14.0"; then
        log_info "Node.js $(get_node_version) 已满足要求"
    else
        install_node
    fi

    # pnpm：项目固定 8.8.0，通过 corepack 管理
    if command_exists corepack; then
        local pnpm_ver
        pnpm_ver="$(get_pnpm_version)"
        if [ "$pnpm_ver" = "8.8.0" ]; then
            log_info "pnpm $pnpm_ver（通过 corepack）已满足要求"
        else
            log_warn "正在通过 corepack 安装 pnpm@8.8.0 ..."
            (cd "$ROOT/frontend-src" && corepack install)
        fi
    else
        log_error "未找到 corepack，请升级 Node.js 到 16.10+ 或手动安装 pnpm 8.8.0"
        exit 1
    fi

    # Docker
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
        exit 1
    fi
}

# ------------------------------------------------------------------
# 端口检测
# ------------------------------------------------------------------
port_in_use() {
    ss -tln 2>/dev/null | grep -qE ":[[:space:]]*$1[[:space:]]"
}

port_pid() {
    ss -tlnp 2>/dev/null | grep -E ":[[:space:]]*$1[[:space:]]" | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2
}

read_pidfile() {
    local f="$1"
    if [ -f "$f" ]; then
        cat "$f"
    fi
}

wait_for_port() {
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

    local backend_pid frontend_pid
    backend_pid="$(read_pidfile "$LOG_DIR/backend.pid")"
    frontend_pid="$(read_pidfile "$LOG_DIR/frontend.pid")"
    local kuscia_running=false
    if docker ps --filter "name=${USER}-kuscia-master" --format '{{.Names}}' | grep -q .; then
        kuscia_running=true
    fi

    local abort=false

    # 后端端口：只能由我们记录的后端进程占用
    for p in 8080 8443; do
        if port_in_use "$p"; then
            local pid
            pid="$(port_pid "$p")"
            if [ -n "$backend_pid" ] && [ "$pid" = "$backend_pid" ]; then
                log_info "端口 $p 已由当前后端进程占用"
            else
                log_error "端口 $p 被其他进程（pid ${pid:-unknown}）占用，无法启动后端"
                abort=true
            fi
        fi
    done

    # 前端端口
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

    # Kuscia 端口：只有在 Kuscia 未运行时被占用才需要报错
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

    if [ "$abort" = true ]; then
        log_error "请先释放占用端口，或执行 bash scripts/dev-stop.sh 清理残留进程"
        exit 1
    fi
}

# ------------------------------------------------------------------
# 进程管理
# ------------------------------------------------------------------
is_process_alive() {
    local pid="$1"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

stop_service_by_pidfile() {
    local pidfile="$1" name="$2"
    if [ -f "$pidfile" ]; then
        local pid
        pid="$(cat "$pidfile")"
        if is_process_alive "$pid"; then
            log_info "停止已运行的 $name（pid $pid）..."
            kill "$pid" 2>/dev/null || true
            sleep 1
            if is_process_alive "$pid"; then
                kill -9 "$pid" 2>/dev/null || true
            fi
        fi
        rm -f "$pidfile"
    fi
}

# ------------------------------------------------------------------
# 各服务启动
# ------------------------------------------------------------------
generate_certs() {
    log_step "生成 KusciaAPI 证书与后端 JKS ..."
    bash "$ROOT/scripts/test/setup.sh"
}

build_backend() {
    log_step "编译后端（生成 fat jar）..."
    mvn clean install -Dmaven.test.skip=true
    if [ ! -f "$ROOT/target/secretpad.jar" ]; then
        log_error "后端编译失败：未找到 target/secretpad.jar"
        exit 1
    fi
    log_info "后端编译完成"
}

start_kuscia() {
    log_step "检查 Kuscia Docker 环境 ..."
    if docker ps --filter "name=${USER}-kuscia-master" --format '{{.Names}}' | grep -q .; then
        log_info "Kuscia master 已在运行，跳过部署"
    else
        log_info "正在部署 Kuscia（master + alice + bob）..."
        log_warn "如果脚本询问 'Whether to retain k3s data?(y/n):'，首次部署建议输入 n"
        bash "$ROOT/scripts/install-kuscia-only.sh" master -P notls
    fi

    wait_for_port 127.0.0.1 18083 180 "Kuscia API gRPC"
    wait_for_port 127.0.0.1 13081 180 "Kuscia Envoy 内部端口"
}

start_backend() {
    log_step "启动后端服务 ..."
    local pidfile="$LOG_DIR/backend.pid"
    if [ -f "$pidfile" ] && is_process_alive "$(cat "$pidfile")"; then
        log_info "后端已在运行（pid $(cat "$pidfile")）"
        return 0
    fi
    stop_service_by_pidfile "$pidfile" "backend"

    export KUSCIA_API_ADDRESS=127.0.0.1
    export KUSCIA_GW_ADDRESS=127.0.0.1:13081
    export KUSCIA_PROTOCOL=notls

    nohup java \
        -Dspring.profiles.active=dev \
        -Dsun.net.http.allowRestrictedHeaders=true \
        -Dserver.port=8443 \
        -jar "$ROOT/target/secretpad.jar" > "$LOG_DIR/backend.log" 2>&1 &
    echo $! > "$pidfile"
    log_info "后端进程已启动，pid $!"

    wait_for_port 127.0.0.1 8080 120 "后端 HTTP"
}

start_frontend() {
    log_step "启动前端开发服务器 ..."
    local pidfile="$LOG_DIR/frontend.pid"
    if [ -f "$pidfile" ] && is_process_alive "$(cat "$pidfile")"; then
        log_info "前端已在运行（pid $(cat "$pidfile")）"
        return 0
    fi
    stop_service_by_pidfile "$pidfile" "frontend"

    cd "$ROOT/frontend-src"
    if [ ! -d "node_modules" ]; then
        log_info "首次运行，安装前端依赖 ..."
        corepack pnpm bootstrap
    fi

    nohup corepack pnpm --filter secretpad dev > "$LOG_DIR/frontend.log" 2>&1 &
    echo $! > "$pidfile"
    log_info "前端进程已启动，pid $!"

    wait_for_port 127.0.0.1 8000 120 "前端开发服务器"
}

print_summary() {
    local frontend_url="http://localhost:8000"
    local backend_health="http://localhost:8080/actuator/health"
    local backend_https="https://localhost:8443"

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
# 命令行参数与主流程
# ------------------------------------------------------------------
case "${1:-}" in
--check | -c)
    check_environment
    echo ""
    log_info "环境检查通过"
    exit 0
    ;;
--help | -h)
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

check_environment
check_required_ports
generate_certs
build_backend
start_kuscia
start_backend
start_frontend
print_summary
