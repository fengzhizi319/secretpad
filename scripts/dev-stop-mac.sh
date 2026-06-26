#!/bin/bash
#
# 停止 SecretPad macOS 本地开发环境启动的后端/前端进程
# 不停止 Kuscia Docker 容器
#
# 用法：
#   bash scripts/dev-stop-mac.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/logs"

is_alive() {
    local pid="$1"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

stop_pidfile() {
    local pidfile="$1" name="$2"
    if [ -f "$pidfile" ]; then
        local pid
        pid="$(cat "$pidfile")"
        if is_alive "$pid"; then
            echo "停止 $name（pid $pid）..."
            kill "$pid" 2>/dev/null || true
            sleep 1
            if is_alive "$pid"; then
                kill -9 "$pid" 2>/dev/null || true
            fi
        else
            echo "$name 未在运行"
        fi
        rm -f "$pidfile"
    else
        echo "未找到 $name 的 pid 文件"
    fi
}

stop_pidfile "$LOG_DIR/backend.pid" "后端"
stop_pidfile "$LOG_DIR/frontend.pid" "前端"

echo "完成"
