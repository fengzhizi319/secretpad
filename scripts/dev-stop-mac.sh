#!/bin/bash
#
# 停止 SecretPad macOS 本地开发环境启动的后端/前端进程
# 不停止 Kuscia Docker 容器
#
# 用法：
#   bash scripts/dev-stop-mac.sh
#   bash scripts/dev-stop-mac.sh --clean   # 停止并清理日志与 pid 文件

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT}/logs"
PID_DIR="${LOG_DIR}"

CLEAN=false
if [ "${1:-}" = "--clean" ]; then
    CLEAN=true
fi

is_alive() {
    local pid="$1"
    [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null
}

# macOS 上 pnpm 启动的 dev server 会产生子进程，仅 kill 父进程可能残留。
# 这里先读 pid 文件停止记录进程，再按端口兜底清理。
stop_pidfile() {
    local pidfile="$1" name="$2"
    if [ -f "${pidfile}" ]; then
        local pid
        pid="$(cat "${pidfile}")"
        if is_alive "${pid}"; then
            echo "停止 ${name}（pid ${pid}）..."
            kill "${pid}" 2>/dev/null || true
            sleep 1
            if is_alive "${pid}"; then
                kill -9 "${pid}" 2>/dev/null || true
            fi
        else
            echo "${name} 未在运行"
        fi
        rm -f "${pidfile}"
    else
        echo "未找到 ${name} 的 pid 文件"
    fi
}

# 根据端口兜底结束进程
kill_by_port() {
    local port="$1" name="$2"
    local pid
    pid="$(lsof -ti :"${port}" 2>/dev/null | head -1 || true)"
    if [ -n "${pid}" ]; then
        echo "通过端口 ${port} 结束残留 ${name} 进程（pid ${pid}）..."
        kill -9 "${pid}" 2>/dev/null || true
    fi
}

stop_pidfile "${PID_DIR}/backend.pid" "后端"
kill_by_port 8080 "后端"
kill_by_port 8443 "后端"

stop_pidfile "${PID_DIR}/frontend.pid" "前端"
kill_by_port 8000 "前端"

if [ "${CLEAN}" = true ]; then
    echo "清理日志与 pid 文件 ..."
    rm -f "${LOG_DIR}"/*.log "${LOG_DIR}"/*.pid
    echo "完成清理"
else
    echo "完成"
fi
