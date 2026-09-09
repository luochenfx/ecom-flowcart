#!/bin/sh
# ecom-flowcart：Temporal PostgreSQL schema 一次性引导（等价官方 auto-setup 镜像的建 schema 段）。
#
# 执行者：temporalio/admin-tools 镜像（内置 temporal-sql-tool + /etc/temporal/schema）。
# 前置：postgres 容器首次初始化时已由 docker/init/01-temporal-databases.sql 建好
#       temporal / temporal_visibility 两个 database（本脚本不再负责建库）。
# 幂等：setup-schema + update-schema 可重复执行（与官方 auto-setup 容器重启语义一致），
#       docker compose down（不加 -v）后再次 up 会重跑本脚本且不报错。
# 注意：库名默认 temporal / temporal_visibility（与 01-temporal-databases.sql 及
#       Temporal Server 侧默认配置一致）；改名须同步三处。
set -eu

: "${POSTGRES_SEEDS:=postgres}"
: "${POSTGRES_USER:=flowcart}"
: "${POSTGRES_PWD:=flowcart}"
: "${DB_PORT:=5432}"
: "${DBNAME:=temporal}"
: "${VISIBILITY_DBNAME:=temporal_visibility}"

# temporal-sql-tool 经 SQL_PASSWORD 读密码（对齐官方 auto-setup.sh 的 export 行为）
export SQL_PASSWORD="${POSTGRES_PWD}"

SCHEMA_BASE=/etc/temporal/schema/postgresql/v12

setup_one() {
  db=$1
  dir=$2
  echo "[temporal-setup] ${db}: setup-schema -v 0.0"
  temporal-sql-tool \
    --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -p "${DB_PORT}" --db "${db}" \
    setup-schema -v 0.0
  echo "[temporal-setup] ${db}: update-schema -d ${dir}"
  temporal-sql-tool \
    --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -p "${DB_PORT}" --db "${db}" \
    update-schema -d "${dir}"
}

setup_one "${DBNAME}" "${SCHEMA_BASE}/temporal/versioned"
setup_one "${VISIBILITY_DBNAME}" "${SCHEMA_BASE}/visibility/versioned"

echo "[temporal-setup] done: ${DBNAME} / ${VISIBILITY_DBNAME} schemas are up to date."
