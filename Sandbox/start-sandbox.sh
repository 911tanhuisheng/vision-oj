#!/usr/bin/env bash
# Ubuntu 一键启动脚本：在 Sandbox 目录执行 bash start-sandbox.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESTART="${1:-}"
if [[ -n "${RESTART}" && "${RESTART}" != "--restart" ]]; then
  echo "用法：bash start-sandbox.sh [--restart]"
  exit 1
fi
ENV_FILE="${SCRIPT_DIR}/.sandbox.env"
LOG_DIR="${SCRIPT_DIR}/.runtime-logs"
PID_FILE="${LOG_DIR}/sandbox.pid"

if [[ ! -f "${ENV_FILE}" ]]; then
  cp "${SCRIPT_DIR}/.sandbox.env.example" "${ENV_FILE}"
  echo "已创建 ${ENV_FILE}，请设置与主后端一致的随机 SANDBOX_AUTH_KEY 后重试。"
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

SANDBOX_KEY_VALUE="${SANDBOX_AUTH_KEY:-}"
if [[ ${#SANDBOX_KEY_VALUE} -lt 32 || "${SANDBOX_KEY_VALUE}" == "123456" || "${SANDBOX_KEY_VALUE}" == replace-with-* ]]; then
  echo "SANDBOX_AUTH_KEY 必须是至少 32 位的随机密钥，且与主后端保持一致。"
  exit 1
fi

for command in docker java mvn curl; do
  command -v "${command}" >/dev/null || {
    echo "缺少命令：${command}"
    exit 1
  }
done

docker info >/dev/null 2>&1 || {
  echo "Docker 未启动，或当前用户没有 Docker 权限。"
  exit 1
}

if ! docker image inspect "${SANDBOX_PYTHON_IMAGE}" >/dev/null 2>&1; then
  echo "缺少图像判题镜像：${SANDBOX_PYTHON_IMAGE}"
  echo "请在完整项目根目录执行 deploy/ubuntu/start-sandbox.sh 构建该镜像。"
  exit 1
fi

mkdir -p "${LOG_DIR}" "${SANDBOX_TMP_DIR}"

if [[ "${RESTART}" != "--restart" ]] && curl -fsS "http://127.0.0.1:${SERVER_PORT}/api/health" | grep -q '"status":"ok"'; then
  echo "Sandbox 已运行：http://127.0.0.1:${SERVER_PORT}/api/health"
  exit 0
fi

if [[ "${RESTART}" == "--restart" ]] && curl -fsS "http://127.0.0.1:${SERVER_PORT}/api/health" >/dev/null 2>&1; then
  if [[ ! -f "${PID_FILE}" ]] || ! kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    echo "8080 端口由脚本之外的进程占用，请先在 Ubuntu 上停止旧 Sandbox，再运行此脚本。"
    exit 1
  fi
fi

cd "${SCRIPT_DIR}"
mvn -q -DskipTests package

JAR="$(find "${SCRIPT_DIR}/target" -maxdepth 1 -name '*.jar' ! -name '*.original' | head -n 1)"
[[ -n "${JAR}" ]] || { echo "未找到 Sandbox jar"; exit 1; }

if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
  kill "$(cat "${PID_FILE}")" || true
  for _ in {1..20}; do
    if ! curl -fsS "http://127.0.0.1:${SERVER_PORT}/api/health" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi

nohup java -jar "${JAR}" >"${LOG_DIR}/sandbox.log" 2>&1 &
echo $! >"${PID_FILE}"

for _ in {1..30}; do
  if curl -fsS "http://127.0.0.1:${SERVER_PORT}/api/health" | grep -q '"status":"ok"'; then
    echo "Sandbox 启动成功：http://127.0.0.1:${SERVER_PORT}/api/health"
    exit 0
  fi
  sleep 1
done

echo "Sandbox 启动失败，查看日志：${LOG_DIR}/sandbox.log"
exit 1
