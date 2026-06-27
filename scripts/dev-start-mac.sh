#!/bin/bash
#
# SecretPad macOS 本地开发环境一键启动脚本（不启动 Kuscia）
#
# 说明：
#   - 优先使用项目 .tools/ 目录下的 JDK 17、Maven、Node（避免系统 Java 版本过高导致 Lombok 编译失败）。
#   - 不依赖 Docker / Kuscia，仅启动前后端，适合页面调试、接口联调与单元测试。
#   - 后端源码位于项目根目录（SecretPad），前端源码位于 frontend-src/。
#
# 用法：
#   bash scripts/dev-start-mac.sh                # 启动前后端
#   bash scripts/dev-start-mac.sh --check        # 仅检查环境
#   bash scripts/dev-start-mac.sh --backend-only # 只启动后端
#   bash scripts/dev-start-mac.sh --frontend-only# 只启动前端
#   bash scripts/dev-start-mac.sh --test         # 运行前后端测试
#   bash scripts/dev-start-mac.sh --test-backend # 只运行后端测试
#   bash scripts/dev-start-mac.sh --test-frontend# 只运行前端测试
#   bash scripts/dev-start-mac.sh --no-build     # 跳过 Maven 编译
#   bash scripts/dev-start-mac.sh --no-certs     # 跳过证书生成
#
# 停止服务：
#   bash scripts/dev-stop-mac.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

LOG_DIR="${ROOT}/logs"
PID_DIR="${LOG_DIR}"
mkdir -p "${LOG_DIR}"

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
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

version_ge() {
    # 如果 $1 >= $2 则返回 0
    printf '%s\n%s\n' "$2" "$1" | sort -V -C
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

# ------------------------------------------------------------------
# 工具链配置：优先使用项目内置 .tools/
# ------------------------------------------------------------------
JAVA_HOME=""
MAVEN_HOME=""
NODE_HOME=""

setup_builtin_tools() {
    if [ -d "${ROOT}/.tools/jdk-17" ]; then
        # macOS .app 结构与 Linux 不同，需要定位到 Contents/Home
        if [ -x "${ROOT}/.tools/jdk-17/Contents/Home/bin/java" ]; then
            JAVA_HOME="${ROOT}/.tools/jdk-17/Contents/Home"
        elif [ -x "${ROOT}/.tools/jdk-17/bin/java" ]; then
            JAVA_HOME="${ROOT}/.tools/jdk-17"
        fi
    fi

    if [ -d "${ROOT}/.tools/maven" ] && [ -x "${ROOT}/.tools/maven/bin/mvn" ]; then
        MAVEN_HOME="${ROOT}/.tools/maven"
    fi

    if [ -d "${ROOT}/.tools/node" ] && [ -x "${ROOT}/.tools/node/bin/node" ]; then
        NODE_HOME="${ROOT}/.tools/node"
    fi

    local new_path=""
    [ -n "${JAVA_HOME}" ] && new_path="${JAVA_HOME}/bin"
    [ -n "${MAVEN_HOME}" ] && new_path="${new_path:+${new_path}:}${MAVEN_HOME}/bin"
    [ -n "${NODE_HOME}" ] && new_path="${new_path:+${new_path}:}${NODE_HOME}/bin"

    if [ -n "${new_path}" ]; then
        export PATH="${new_path}:${PATH}"
        [ -n "${JAVA_HOME}" ] && export JAVA_HOME
        [ -n "${MAVEN_HOME}" ] && export MAVEN_HOME
        [ -n "${NODE_HOME}" ] && export NODE_HOME
    fi
}

# ------------------------------------------------------------------
# 环境检测
# ------------------------------------------------------------------
check_environment() {
    log_step "检查本地开发环境 ..."

    # Java
    if [ -n "${JAVA_HOME}" ]; then
        log_info "使用项目内置 JDK：${JAVA_HOME}"
    fi
    if command_exists java; then
        local java_ver
        java_ver="$(get_java_version)"
        log_info "检测到 Java ${java_ver}"
        if [[ "${java_ver}" != 17* ]]; then
            log_error "SecretPad 需要 JDK 17，当前版本 ${java_ver} 会导致 Lombok 等组件编译失败"
            log_error "请安装 JDK 17 并放到 .tools/jdk-17/，或在 PATH 中优先使用 JDK 17"
            exit 1
        fi
    else
        log_error "未找到 Java，请安装 JDK 17 并配置 JAVA_HOME"
        exit 1
    fi

    # Maven
    if [ -n "${MAVEN_HOME}" ]; then
        log_info "使用项目内置 Maven：${MAVEN_HOME}"
    fi
    if command_exists mvn && version_ge "$(get_mvn_version)" "3.8.8"; then
        log_info "Maven $(get_mvn_version) 已满足要求"
    else
        log_error "需要 Maven 3.8.8+，请安装并配置 PATH"
        exit 1
    fi

    # Node
    if [ -n "${NODE_HOME}" ]; then
        log_info "使用项目内置 Node.js：${NODE_HOME}"
    fi
    if command_exists node && version_ge "$(get_node_version)" "16.14.0"; then
        log_info "Node.js $(get_node_version) 已满足要求"
    else
        log_error "需要 Node.js 16.14.0+，请安装并配置 PATH"
        exit 1
    fi

    # pnpm
    local pnpm_ver
    pnpm_ver="$(get_pnpm_version)"
    if [ "${pnpm_ver}" = "8.8.0" ]; then
        log_info "pnpm ${pnpm_ver} 已满足要求"
    else
        log_warn "当前 pnpm ${pnpm_ver}，项目固定 8.8.0，建议执行：npm install -g pnpm@8.8.0"
    fi
}

# ------------------------------------------------------------------
# 端口检测（macOS 兼容，不使用 Linux 的 ss）
# ------------------------------------------------------------------
port_in_use() {
    local port="$1"
    lsof -i :"${port}" >/dev/null 2>&1
}

port_pid() {
    local port="$1"
    lsof -ti :"${port}" 2>/dev/null | head -1
}

wait_for_port() {
    local host="$1" port="$2" timeout_sec="${3:-60}" what="$4"
    log_info "等待 ${what} 就绪：${host}:${port}（最多 ${timeout_sec}s）..."
    for ((i = 0; i < timeout_sec; i++)); do
        if curl -s --max-time 2 "http://${host}:${port}/actuator/health" >/dev/null 2>&1 || \
           port_in_use "${port}"; then
            log_info "${what} 已就绪"
            return 0
        fi
        sleep 1
    done
    log_error "${what} 在 ${host}:${port} 上未就绪，请查看日志"
    return 1
}

check_required_ports() {
    log_step "检查关键端口占用情况 ..."

    local abort=false
    local backend_pid frontend_pid
    backend_pid="$(read_pidfile "${PID_DIR}/backend.pid")"
    frontend_pid="$(read_pidfile "${PID_DIR}/frontend.pid")"

    for p in 8080 8443; do
        if port_in_use "${p}"; then
            local pid
            pid="$(port_pid "${p}")"
            if [ -n "${backend_pid}" ] && [ "${pid}" = "${backend_pid}" ]; then
                log_info "端口 ${p} 已由当前后端进程占用"
            else
                log_error "端口 ${p} 被其他进程（pid ${pid:-unknown}）占用，无法启动后端"
                abort=true
            fi
        fi
    done

    if port_in_use 8000; then
        local pid
        pid="$(port_pid 8000)"
        if [ -n "${frontend_pid}" ] && [ "${pid}" = "${frontend_pid}" ]; then
            log_info "端口 8000 已由当前前端进程占用"
        else
            log_error "端口 8000 被其他进程（pid ${pid:-unknown}）占用，无法启动前端"
            abort=true
        fi
    fi

    if [ "${abort}" = true ]; then
        log_error "请先释放占用端口，或执行 bash scripts/dev-stop-mac.sh 清理"
        exit 1
    fi
}

# ------------------------------------------------------------------
# 进程管理
# ------------------------------------------------------------------
read_pidfile() {
    local f="$1"
    if [ -f "${f}" ]; then
        cat "${f}"
    fi
}

is_process_alive() {
    local pid="$1"
    [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null
}

stop_service_by_pidfile() {
    local pidfile="$1" name="$2"
    if [ -f "${pidfile}" ]; then
        local pid
        pid="$(cat "${pidfile}")"
        if is_process_alive "${pid}"; then
            log_info "停止已运行的 ${name}（pid ${pid}）..."
            kill "${pid}" 2>/dev/null || true
            sleep 1
            if is_process_alive "${pid}"; then
                kill -9 "${pid}" 2>/dev/null || true
            fi
        fi
        rm -f "${pidfile}"
    fi
}

# ------------------------------------------------------------------
# 证书 / 构建 / 启动
# ------------------------------------------------------------------
generate_certs() {
    if [ -f "${ROOT}/config/server.jks" ] && [ -f "${ROOT}/config/certs/client.crt" ]; then
        log_info "证书与 JKS 已存在，跳过生成（如需重新生成请删除 config/server.jks 和 config/certs/）"
        return 0
    fi
    # setup.sh 中的 keytool 在 server.jks 已存在时会报错，先清理旧 jks
    rm -f "${ROOT}/config/server.jks"
    log_step "生成证书与后端 JKS ..."
    bash "${ROOT}/scripts/test/setup.sh"
}

build_backend() {
    log_step "编译后端（生成 fat jar）..."
    mvn clean install -Dmaven.test.skip=true
    if [ ! -f "${ROOT}/target/secretpad.jar" ]; then
        log_error "后端编译失败：未找到 target/secretpad.jar"
        exit 1
    fi
    log_info "后端编译完成"
}

ensure_frontend_env() {
    local env_file="${ROOT}/frontend-src/apps/platform/.env"
    if [ ! -f "${env_file}" ]; then
        log_info "创建前端代理配置 ${env_file}"
        cat > "${env_file}" <<'EOF'
PROXY_URL=http://127.0.0.1:8080
EOF
    elif ! grep -q '^PROXY_URL=' "${env_file}" 2>/dev/null; then
        log_info "向前端代理配置追加 PROXY_URL"
        echo "PROXY_URL=http://127.0.0.1:8080" >> "${env_file}"
    fi
}

start_backend() {
    log_step "启动后端服务 ..."
    local pidfile="${PID_DIR}/backend.pid"
    if [ -f "${pidfile}" ] && is_process_alive "$(cat "${pidfile}")"; then
        log_info "后端已在运行（pid $(cat "${pidfile}")）"
        return 0
    fi
    stop_service_by_pidfile "${pidfile}" "后端"

    # 无 Kuscia 模式：指向本地不存在的服务，避免 UnknownHostException
    export KUSCIA_API_ADDRESS=127.0.0.1
    export KUSCIA_GW_ADDRESS=127.0.0.1:13081
    export KUSCIA_PROTOCOL=notls

    nohup java \
        -Dspring.profiles.active=dev \
        -Dsun.net.http.allowRestrictedHeaders=true \
        -Dserver.port=8443 \
        -jar "${ROOT}/target/secretpad.jar" > "${LOG_DIR}/backend.log" 2>&1 &
    echo $! > "${pidfile}"
    log_info "后端进程已启动，pid $!"

    wait_for_port 127.0.0.1 8080 180 "后端 HTTP"
}

start_frontend() {
    log_step "启动前端开发服务器 ..."
    local pidfile="${PID_DIR}/frontend.pid"
    if [ -f "${pidfile}" ] && is_process_alive "$(cat "${pidfile}")"; then
        log_info "前端已在运行（pid $(cat "${pidfile}")）"
        return 0
    fi
    stop_service_by_pidfile "${pidfile}" "前端"

    ensure_frontend_env

    cd "${ROOT}/frontend-src"
    if [ ! -d "node_modules" ]; then
        log_info "首次运行，安装前端依赖 ..."
        pnpm bootstrap
    fi

    nohup pnpm --filter secretpad dev > "${LOG_DIR}/frontend.log" 2>&1 &
    echo $! > "${pidfile}"
    log_info "前端进程已启动，pid $!"

    wait_for_port 127.0.0.1 8000 180 "前端开发服务器"
}

run_backend_tests() {
    log_step "运行后端单元测试 ..."
    mvn test
}

run_frontend_tests() {
    log_step "运行前端单元测试 ..."
    cd "${ROOT}/frontend-src"
    if [ ! -d "node_modules" ]; then
        log_info "首次运行，安装前端依赖 ..."
        pnpm bootstrap
    fi
    pnpm --filter secretpad test
}

print_summary() {
    local frontend_url="http://localhost:8000"
    local backend_health="http://localhost:8080/actuator/health"
    local backend_https="https://localhost:8443"

    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  SecretPad macOS 本地开发环境已启动${NC}"
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
    echo -e "   后端：${LOG_DIR}/backend.log"
    echo -e "   前端：${LOG_DIR}/frontend.log"
    echo ""
    echo -e "🛑 停止服务：${YELLOW}bash scripts/dev-stop-mac.sh${NC}"
    echo ""
}

# ------------------------------------------------------------------
# 命令行参数解析
# ------------------------------------------------------------------
DO_CHECK=false
BACKEND_ONLY=false
FRONTEND_ONLY=false
RUN_TESTS=false
TEST_BACKEND_ONLY=false
TEST_FRONTEND_ONLY=false
SKIP_BUILD=false
SKIP_CERTS=false

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
        --check | -c)
            DO_CHECK=true
            ;;
        --backend-only)
            BACKEND_ONLY=true
            ;;
        --frontend-only)
            FRONTEND_ONLY=true
            ;;
        --test)
            RUN_TESTS=true
            ;;
        --test-backend)
            TEST_BACKEND_ONLY=true
            ;;
        --test-frontend)
            TEST_FRONTEND_ONLY=true
            ;;
        --no-build)
            SKIP_BUILD=true
            ;;
        --no-certs)
            SKIP_CERTS=true
            ;;
        --help | -h)
            cat <<EOF
SecretPad macOS 本地开发环境一键启动脚本（不启动 Kuscia）

用法：
  bash scripts/dev-start-mac.sh                  启动前后端
  bash scripts/dev-start-mac.sh --check          仅检查环境
  bash scripts/dev-start-mac.sh --backend-only   只启动后端
  bash scripts/dev-start-mac.sh --frontend-only  只启动前端
  bash scripts/dev-start-mac.sh --test           运行前后端单元测试
  bash scripts/dev-start-mac.sh --test-backend   只运行后端单元测试
  bash scripts/dev-start-mac.sh --test-frontend  只运行前端单元测试
  bash scripts/dev-start-mac.sh --no-build       跳过 Maven 编译
  bash scripts/dev-start-mac.sh --no-certs       跳过证书生成

停止服务：
  bash scripts/dev-stop-mac.sh
  bash scripts/dev-stop-mac.sh --clean           停止并清理日志/pid 文件
EOF
            exit 0
            ;;
        *)
            log_error "未知参数：$1"
            log_error "使用 --help 查看帮助"
            exit 1
            ;;
        esac
        shift
    done
}

# ------------------------------------------------------------------
# 主流程
# ------------------------------------------------------------------
main() {
    parse_args "$@"
    setup_builtin_tools
    check_environment

    if [ "${DO_CHECK}" = true ]; then
        echo ""
        log_info "环境检查通过"
        exit 0
    fi

    if [ "${TEST_BACKEND_ONLY}" = true ]; then
        run_backend_tests
        exit 0
    fi

    if [ "${TEST_FRONTEND_ONLY}" = true ]; then
        run_frontend_tests
        exit 0
    fi

    if [ "${RUN_TESTS}" = true ]; then
        run_backend_tests
        run_frontend_tests
        exit 0
    fi

    if [ "${FRONTEND_ONLY}" != true ]; then
        check_required_ports
        # 注意：必须先编译再生成证书。根目录 mvn clean 会删除 config/ 下的 *.crt/*.key，
        # 如果先生成证书再编译，证书会被 clean 阶段清理掉。
        [ "${SKIP_BUILD}" != true ] && build_backend
        [ "${SKIP_CERTS}" != true ] && generate_certs
        start_backend
    fi

    if [ "${BACKEND_ONLY}" != true ]; then
        start_frontend
    fi

    print_summary
}

main "$@"
