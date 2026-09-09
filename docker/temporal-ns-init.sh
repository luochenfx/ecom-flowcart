#!/bin/sh
# ecom-flowcart：注册 default namespace（等价官方 auto-setup 镜像的 setup_server 段）。
#
# 执行者：temporalio/admin-tools 镜像（内置 temporal CLI）。
# 时序：temporalio/server 启动后执行——先轮询 `temporal operator cluster health` 至 SERVING，
#       再注册 default namespace（describe 已存在则跳过，幂等）。
set -eu

: "${TEMPORAL_ADDRESS:=temporal:7233}"
: "${DEFAULT_NAMESPACE:=default}"
: "${DEFAULT_NAMESPACE_RETENTION:=24h}"
: "${READY_TIMEOUT_SEC:=90}"

export TEMPORAL_ADDRESS

i=0
until temporal operator cluster health 2>/dev/null | grep -q SERVING; do
  i=$((i + 1))
  if [ "${i}" -ge "${READY_TIMEOUT_SEC}" ]; then
    echo "[temporal-init] ERROR: Temporal Server not SERVING after ${READY_TIMEOUT_SEC}s" >&2
    exit 1
  fi
  echo "[temporal-init] waiting for Temporal Server ..."
  sleep 1
done
echo "[temporal-init] Temporal Server SERVING."

if ! temporal operator namespace describe --namespace "${DEFAULT_NAMESPACE}" >/dev/null 2>&1; then
  echo "[temporal-init] registering namespace '${DEFAULT_NAMESPACE}' ..."
  temporal operator namespace create \
    --retention "${DEFAULT_NAMESPACE_RETENTION}" \
    --description "Default namespace for Temporal Server." \
    --namespace "${DEFAULT_NAMESPACE}"
  echo "[temporal-init] namespace '${DEFAULT_NAMESPACE}' registered."
else
  echo "[temporal-init] namespace '${DEFAULT_NAMESPACE}' already registered."
fi
