#!/bin/bash
#
# 无Docker运行脚本 - 启动 SecretPad 前端、后端和 Kuscia
#
# 用法：
#   bash run-all-no-docker.sh
#   bash run-all-no-docker.sh --stop
#
# 说明：
#   - 启动顺序：Kuscia Master → SecretPad 后端 → SecretPad 前端
#   - 所有服务都以非容器方式运行
#   - 使用本地编译的二进制文件
#   - 适用于开发和测试环境
#
set -euo pipefail

ROOT_DIR="/home/charles/code/sfwork"
KUSCIA_DIR="$ROOT_DIR/kuscia"
SECRETPAD_DIR="$ROOT_DIR/secretpad"
LOG_DIR="$ROOT_DIR/logs"
PID_DIR="$LOG_DIR/pids"

# 创建日志和PID目录
mkdir -p "$LOG_DIR"
mkdir -p "$PID_DIR"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
log_step() { echo -e "${BLUE}[STEP]${NC} $*"; }

# 进程管理函数
is_process_alive() {
    local pid="$1"
    [ -n "$pid" ] && ps -p "$pid" >/dev/null 2>&1
}

read_pidfile() {
    local f="$1"
    if [ -f "$f" ]; then
        cat "$f"
    fi
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

# 端口检测函数
port_in_use() {
    local port="$1"
    ss -tln 2>/dev/null | grep -qE ":$port\b"
}

wait_for_port() {
    local host="$1" port="$2" timeout_sec="${3:-60}" what="$4"
    log_info "等待 $what 就绪：$host:$port（最多 ${timeout_sec}s）..."
    for ((i = 0; i < timeout_sec; i++)); do
        if ss -tln 2>/dev/null | grep -qE ":$port\b"; then
            log_info "$what 已就绪"
            return 0
        fi
        sleep 1
    done
    log_error "$what 在 $host:$port 上未就绪，请查看日志"
    return 1
}

# 检查并安装依赖
check_dependencies() {
    log_step "检查系统依赖..."

    # 检查必需的命令
    for cmd in java mvn node pnpm; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            log_error "未找到 $cmd，该脚本需要 $cmd 才能运行"
            exit 1
        fi
    done

    # docker 仅在 Kuscia 首次提取依赖时需要；若 .local-kuscia 已有依赖则仅警告
    if ! command -v docker >/dev/null 2>&1; then
        if [ ! -f "$ROOT_DIR/.local-kuscia/bin/k3s" ]; then
            log_error "未找到 docker，且本地 Kuscia 依赖尚未初始化，无法继续"
            exit 1
        fi
        log_warn "未找到 docker；将复用已存在的 .local-kuscia 依赖"
    fi

    # 检查Java版本
    local java_version
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
    if [ "$java_version" != "17" ]; then
        log_error "需要 Java 17，当前版本: $(java -version 2>&1 | head -1)"
        exit 1
    fi

    # 检查Node.js版本
    local node_version
    node_version=$(node -v | sed 's/v//')
    if ! dpkg --compare-versions "$node_version" ge "16.14.0" 2>/dev/null || [ $? -ne 0 ]; then
        log_warn "建议 Node.js 版本 >= 16.14.0，当前版本: $(node -v)"
    fi
}

# 检查关键端口是否被占用
check_ports() {
    log_step "检查关键端口占用情况..."
    local ports=(53 80 8080 8082 8083 8443 8000)
    local occupied=()
    for port in "${ports[@]}"; do
        if port_in_use "$port"; then
            occupied+=("$port")
        fi
    done
    if [ ${#occupied[@]} -gt 0 ]; then
        log_warn "以下端口已被占用：${occupied[*]}"
        log_warn "若启动失败，请先释放这些端口（特别是 80 / 8083 / 53）"
    else
        log_info "关键端口空闲"
    fi
}

# 编译 Kuscia
build_kuscia() {
    log_step "编译 Kuscia ..."
    cd "$KUSCIA_DIR"
    
    # 构建 kuscia 二进制
    bash hack/build.sh -t kuscia
    
    log_info "Kuscia 编译完成"
}

# 启动 Kuscia Master (非Docker模式)
start_kuscia_master() {
    log_step "启动 Kuscia Master (非Docker模式)..."
    
    # 停止可能存在的旧进程
    stop_kuscia_master
    
    # 设置 KUSCIA_HOME 目录
    export KUSCIA_HOME="$ROOT_DIR/.local-kuscia"
    mkdir -p "$KUSCIA_HOME"
    
    # 使用 sudo 运行 run-local-master.sh 脚本
    # 提供 sudo 密码作为输入；输出重定向到日志文件
    echo "110734" | sudo -S bash "$KUSCIA_DIR/scripts/run-local-master.sh" "$KUSCIA_HOME" > "$LOG_DIR/kuscia-master.log" 2>&1 &
    
    # 保存 sudo 进程ID（启动脚本本身）
    echo $! > "$PID_DIR/kuscia-master.pid"
    
    # 等待关键端口就绪（非 Docker 模式下 run-local-master.sh 使用内部默认端口）
    wait_for_port 127.0.0.1 8083 180 "Kuscia API gRPC"
    wait_for_port 127.0.0.1 80 180 "Kuscia Envoy 内部端口"
    
    # 记录实际 kuscia 进程 PID，便于后续停止
    local kuscia_pid
    kuscia_pid=$(pgrep -f "kuscia start --config $KUSCIA_HOME/etc/conf/kuscia.yaml" | head -1 || true)
    if [ -n "$kuscia_pid" ]; then
        echo "$kuscia_pid" > "$PID_DIR/kuscia-master.pid"
        log_info "Kuscia Master 已启动（实际 pid $kuscia_pid）"
    else
        log_warn "未能获取 Kuscia Master 实际进程 pid，停止时可能依赖关键字匹配"
    fi
}

# 停止 Kuscia Master
stop_kuscia_master() {
    local pidfile="$PID_DIR/kuscia-master.pid"
    if [ -f "$pidfile" ]; then
        local pid
        pid="$(cat "$pidfile")"
        if is_process_alive "$pid"; then
            log_info "停止 Kuscia Master（pid $pid）..."
            echo "110734" | sudo -S kill "$pid" 2>/dev/null || true
            sleep 2
            if is_process_alive "$pid"; then
                echo "110734" | sudo -S kill -9 "$pid" 2>/dev/null || true
            fi
        fi
        rm -f "$pidfile"
    fi
    
    # 清理可能残留的 kuscia start 进程
    local remaining
    remaining=$(pgrep -f "kuscia start --config" 2>/dev/null || true)
    if [ -n "$remaining" ]; then
        log_warn "发现残留 Kuscia 进程，强制清理..."
        echo "110734" | sudo -S kill -9 $remaining 2>/dev/null || true
    fi
}

# 编译 SecretPad 后端
build_secretpad_backend() {
    log_step "编译 SecretPad 后端..."
    cd "$SECRETPAD_DIR"
    
    # 清理并编译
    mvn clean install -Dmaven.test.skip=true
    
    if [ ! -f "$SECRETPAD_DIR/target/secretpad.jar" ]; then
        log_error "后端编译失败：未找到 target/secretpad.jar"
        exit 1
    fi
    
    log_info "SecretPad 后端编译完成"
}

# 生成证书
generate_certs() {
    log_step "生成证书与 JKS ..."
    cd "$SECRETPAD_DIR"
    
    # 如果证书已存在，则跳过
    if [ -f "$SECRETPAD_DIR/config/server.jks" ] && [ -f "$SECRETPAD_DIR/config/certs/client.crt" ]; then
        log_info "证书与 JKS 已存在，跳过生成"
        return 0
    fi
    
    # 清理可能存在的旧证书
    rm -f "$SECRETPAD_DIR/config/server.jks"
    rm -rf "$SECRETPAD_DIR/config/certs/"
    
    # 生成新证书
    bash "$SECRETPAD_DIR/scripts/test/setup.sh"
    
    log_info "证书生成完成"
}

# 启动 SecretPad 后端
start_secretpad_backend() {
    log_step "启动 SecretPad 后端..."
    
    # 停止可能存在的旧进程
    stop_service_by_pidfile "$PID_DIR/secretpad-backend.pid" "SecretPad 后端"
    
    # 设置环境变量
    # 非 Docker 模式下，Kuscia 内部 gRPC 端口为 8083，Envoy 内部端口为 80
    export KUSCIA_API_ADDRESS=127.0.0.1
    export KUSCIA_GW_ADDRESS=127.0.0.1:80
    export KUSCIA_PROTOCOL=notls
    
    # 启动后端服务
    nohup java \
        -Dspring.profiles.active=dev \
        -Dsun.net.http.allowRestrictedHeaders=true \
        -Dserver.port=8443 \
        -jar "$SECRETPAD_DIR/target/secretpad.jar" > "$LOG_DIR/backend.log" 2>&1 &
    
    # 保存进程ID
    echo $! > "$PID_DIR/secretpad-backend.pid"
    
    # 等待后端 HTTP 端口就绪（Spring Boot dev 模式下 http-port 为 8080）
    wait_for_port 127.0.0.1 8080 120 "后端 HTTP"
    
    log_info "SecretPad 后端已启动"
}

# 启动 SecretPad 前端
start_secretpad_frontend() {
    log_step "启动 SecretPad 前端..."
    
    # 停止可能存在的旧进程
    stop_service_by_pidfile "$PID_DIR/secretpad-frontend.pid" "SecretPad 前端"
    
    # 确保前端代理配置
    local env_file="$SECRETPAD_DIR/frontend-src/apps/platform/.env"
    if [ ! -f "$env_file" ]; then
        log_info "创建前端代理配置 $env_file"
        echo "PROXY_URL=http://127.0.0.1:8080" > "$env_file"
    elif ! grep -q '^PROXY_URL=' "$env_file" 2>/dev/null; then
        log_info "向前端代理配置追加 PROXY_URL"
        echo "PROXY_URL=http://127.0.0.1:8080" >> "$env_file"
    fi
    
    # 切换到前端目录
    cd "$SECRETPAD_DIR/frontend-src"
    
    # 安装前端依赖（如果需要）
    if [ ! -d "node_modules" ]; then
        log_info "首次运行，安装前端依赖..."
        pnpm bootstrap
    fi
    
    # 启动前端开发服务器
    nohup pnpm --filter secretpad dev > "$LOG_DIR/frontend.log" 2>&1 &
    
    # 保存进程ID
    echo $! > "$PID_DIR/secretpad-frontend.pid"
    
    # 等待前端开发服务器就绪
    wait_for_port 127.0.0.1 8000 120 "前端开发服务器"
    
    log_info "SecretPad 前端已启动"
}

# 停止所有服务
stop_all_services() {
    log_step "停止所有服务..."
    
    # 停止前端
    stop_service_by_pidfile "$PID_DIR/secretpad-frontend.pid" "SecretPad 前端"
    
    # 停止后端
    stop_service_by_pidfile "$PID_DIR/secretpad-backend.pid" "SecretPad 后端"
    
    # 停止 Kuscia Master（需要 sudo）
    stop_kuscia_master
    
    log_info "所有服务已停止"
}

# 打印摘要信息
print_summary() {
    local frontend_url="http://localhost:8000"
    local backend_health="http://localhost:8080/actuator/health"
    local backend_https="https://localhost:8443"

    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  所有服务已启动（无Docker模式）${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "🌐 前端开发服务器：${BLUE}${frontend_url}${NC}"
    echo -e "🔧 后端健康检查：${BLUE}${backend_health}${NC}"
    echo -e "🔒 后端 HTTPS 地址：${BLUE}${backend_https}${NC}"
    echo ""
    echo -e "👤 登录账号：${YELLOW}admin / 12345678${NC}"
    echo ""
    echo -e "📄 日志文件："
    echo -e "   Kuscia：$LOG_DIR/kuscia-master.log"
    echo -e "   后端：$LOG_DIR/backend.log"
    echo -e "   前端：$LOG_DIR/frontend.log"
    echo ""
    echo -e "🛑 停止服务：${YELLOW}bash run-all-no-docker.sh --stop${NC}"
    echo ""
}

# 主函数
main() {
    # 检查是否是停止命令
    if [ "${1:-}" = "--stop" ]; then
        stop_all_services
        exit 0
    fi
    
    # 检查依赖与端口
    check_dependencies
    check_ports
    
    # 编译 Kuscia
    build_kuscia
    
    # 启动 Kuscia Master
    start_kuscia_master
    
    # 编译 SecretPad 后端
    build_secretpad_backend
    
    # 生成证书
    generate_certs
    
    # 启动 SecretPad 后端
    start_secretpad_backend
    
    # 启动 SecretPad 前端
    start_secretpad_frontend
    
    # 打印摘要信息
    print_summary
}

# 运行主函数
main "$@"