#!/bin/bash
#
# SecretPad macOS 本地开发环境一键启动脚本（不启动 Kuscia）
#
# 用法：
#   bash scripts/dev-start-mac.sh
#
# 停止服务：
#   bash scripts/dev-stop-mac.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
log_step() { echo -e "${BLUE}[STEP]${NC} $*"; }

version_ge() {
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
    pnpm -v 2>/dev/null || true
}

check_environment() {
    log_step "检查本地开发环境 ..."

    if command_exists java && version_ge "$(get_java_version)" "17"; then
        log_info "Java $(get_java_version) 已满足要求"
    else
        log_error "需要 JDK 17+，请安装并配置 JAVA_HOME"
        exit 1
    fi

    if command_exists mvn && version_ge "$(get_mvn_version)" "3.8.8"; then
        log_info "Maven $(get_mvn_version) 已满足要求"
    else
        log_error "需要 Maven 3.8.8+，请安装并配置 PATH"
        exit 1
    fi

    if command_exists node && version_ge "$(get_node_version)" "16.14.0"; then
        log_info "Node.js $(get_node_version) 已满足要求"
    else
        log_error "需要 Node.js 16.14.0+，请安装并配置 PATH"
        exit 1
    fi

    local pnpm_ver
    pnpm_ver="$(get_pnpm_version)"
    if [ "$pnpm_ver" = "8.8.0" ]; then
        log_info "pnpm $pnpm_ver 已满足要求"
    else
        log_warn "当前 pnpm $pnpm_ver，项目固定 8.8.0，建议执行：npm install -g pnpm@8.8.0"
    fi
}

port_in_use() {
    ss -tln 2>/dev/null | grep -qE ":[[:space:]]*$1[[:space:]]" || \
        lsof -i :"$1" >/dev/null 2>&1
}

wait_for_port() {
    local host="$1" port="$2" timeout_sec="${3:-60}" what="$4"
    log_info "等待 $what 就绪：$host:$port（最多 ${timeout_sec}s）..."
    for ((i = 0; i < timeout_sec; i++)); do
        if curl -s "http://$host:$port/actuator/health" >/dev/null 2>&1 || \
           (ss -tln 2>/dev/null | grep -qE ":[[:space:]]*$port[[:space:]]") || \
           lsof -i :"$port" >/dev/null 2>&1; then
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
    local abort=false
    for p in 8080 8443 8000; do
        if port_in_use "$p"; then
            log_error "端口 $p 已被占用，无法启动"
            abort=true
        fi
    done
    if [ "$abort" = true ]; then
        log_error "请先释放占用端口，或执行 bash scripts/dev-stop-mac.sh 清理"
        exit 1
    fi
}

generate_certs() {
    log_step "生成证书与后端 JKS ..."
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

start_backend() {
    log_step "启动后端服务 ..."
    local pidfile="$LOG_DIR/backend.pid"
    if [ -f "$pidfile" ]; then
        local pid
        pid="$(cat "$pidfile")"
        if kill -0 "$pid" 2>/dev/null; then
            log_info "后端已在运行（pid $pid）"
            return 0
        fi
        rm -f "$pidfile"
    fi

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

    wait_for_port 127.0.0.1 8080 180 "后端 HTTP"
}

start_frontend() {
    log_step "启动前端开发服务器 ..."
    local pidfile="$LOG_DIR/frontend.pid"
    if [ -f "$pidfile" ]; then
        local pid
        pid="$(cat "$pidfile")"
        if kill -0 "$pid" 2>/dev/null; then
            log_info "前端已在运行（pid $pid）"
            return 0
        fi
        rm -f "$pidfile"
    fi

    cd "$ROOT/frontend-src"
    if [ ! -d "node_modules" ]; then
        log_info "首次运行，安装前端依赖 ..."
        pnpm bootstrap
    fi

    nohup pnpm --filter secretpad dev > "$LOG_DIR/frontend.log" 2>&1 &
    echo $! > "$pidfile"
    log_info "前端进程已启动，pid $!"

    wait_for_port 127.0.0.1 8000 180 "前端开发服务器"
}

print_summary() {
    local frontend_url="http://localhost:8000"
    local backend_health="http://localhost:8080/actuator/health"
    local backend_https="https://localhost:8443"

    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  c-life macOS 本地开发环境已启动${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "🌐 前端开发服务器：${BLUE}${frontend_url}${NC}"
    echo -e "🔧 后端健康检查：${BLUE}${backend_health}${NC}"
    echo -e "🔒 后端 HTTPS 地址：${BLUE}${backend_https}${NC}"
    echo ""
    echo -e "👤 登录账号：${YELLOW}admin / 12345678${NC}"
    echo ""
    echo -e "⚠️  说明：当前为无 Kuscia 模式，节点管理、数据管理、任务执行等依赖 Kuscia 的功能不可用。${NC}"
    echo ""
    echo -e "📄 日志文件："
    echo -e "   后端：$LOG_DIR/backend.log"
    echo -e "   前端：$LOG_DIR/frontend.log"
    echo ""
    echo -e "🛑 停止服务：${YELLOW}bash scripts/dev-stop-mac.sh${NC}"
    echo ""
}

case "${1:-}" in
--help | -h)
    cat <<EOF
SecretPad macOS 本地开发环境一键启动脚本（不启动 Kuscia）

用法：
  bash scripts/dev-start-mac.sh

停止服务：
  bash scripts/dev-stop-mac.sh
EOF
    exit 0
    ;;
esac

check_environment
check_required_ports
generate_certs
build_backend
start_backend
start_frontend
print_summary
