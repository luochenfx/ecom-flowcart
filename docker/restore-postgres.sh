#!/usr/bin/env bash
# ecom-flowcart：postgres 双库恢复演练脚本骨架。
#
# 用途：验证 dump 可 restore（点亮定时 cron 前的验收门槛，见 docs/ops/backup-restore.md §6）。
# 设计：**不直接覆盖生产库**——把一份 dump 恢复到临时 database，校验后 drop，确保恢复路径可信且无副作用。
#
# 用法：
#   docker/restore-postgres.sh <db> <path-to.dump>      # 恢复到临时库 <db>_restore_test，校验行数后 drop
#   docker/restore-postgres.sh --all                    # 对 BACKUP_DIR 最新一份逐库演练
#
# 可覆盖环境变量：POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_HOST / POSTGRES_PORT（同 backup 脚本）。
set -eu

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$REPO_ROOT/.env" ] && set -a && . "$REPO_ROOT/.env" && set +a

PGUSER="${POSTGRES_USER:-flowcart}"
PGPASSWORD="${POSTGRES_PASSWORD:-flowcart}"
export PGPASSWORD
PGHOST="${POSTGRES_HOST:-localhost}"
PGPORT="${POSTGRES_PORT:-5433}"
BACKUP_DIR="${BACKUP_DIR:-$REPO_ROOT/docker/backups}"

USE_DOCKER=0
if ! command -v pg_restore >/dev/null 2>&1; then
  if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q '^flowcart-postgres$'; then
    USE_DOCKER=1
  else
    echo "[restore] 错误：宿主机无 pg_restore 且容器未运行。" >&2; exit 1
  fi
fi

# psql 执行辅助（含 docker exec 分支）
psql_x() { # psql_x "<db>" "<sql>"
  if [ "$USE_DOCKER" = "1" ]; then
    docker exec flowcart-postgres psql -U "$PGUSER" -d "$1" -v ON_ERROR_STOP=1 -c "$2"
  else
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$1" -v ON_ERROR_STOP=1 -c "$2"
  fi
}
restore_x() { # restore_x "<db>" "<dumpfile>"
  if [ "$USE_DOCKER" = "1" ]; then
    docker exec -i flowcart-postgres pg_restore -U "$PGUSER" -d "$1" < "$2"
  else
    pg_restore -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$1" "$2"
  fi
}

# 演练单库：目标库必须已存在（flowcart / temporal / temporal_visibility）
restore_one() {
  local db="$1" dump="$2"
  [ -f "$dump" ] || { echo "[restore] 文件不存在: $dump" >&2; exit 1; }
  local tmp="${db}_restore_test"
  echo "[restore] 演练: $db ← $dump （临时库 $tmp）"
  # 已有残留临时库则清理
  psql_x postgres "DROP DATABASE IF EXISTS ${tmp};" >/dev/null
  psql_x postgres "CREATE DATABASE ${tmp} TEMPLATE template0;" >/dev/null
  restore_x "$tmp" "$dump"
  # 简单校验：表数量非负、能查 pg_catalog
  local n
  n="$(psql_x "$tmp" "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema');" | sed -n '3p' | tr -d ' ')"
  echo "[restore] ok: ${db} restore 成功，业务表数=${n:-0}"
  psql_x postgres "DROP DATABASE ${tmp};" >/dev/null
  echo "[restore] 已清理临时库 ${tmp}"
}

# ---- 入口 ----
if [ "$1" = "--all" ]; then
  for db in flowcart temporal temporal_visibility; do
    latest="$(ls -1t "$BACKUP_DIR"/${db}-*.dump 2>/dev/null | head -n1 || true)"
    if [ -n "$latest" ]; then
      restore_one "$db" "$latest"
    else
      echo "[restore] 跳过: $db 无 dump（$BACKUP_DIR）"
    fi
  done
elif [ "$#" = "2" ]; then
  restore_one "$1" "$2"
else
  echo "用法: $0 <db> <dumpfile> | $0 --all" >&2; exit 2
fi
echo "[restore] 演练完成。所有目标库可 restore → 满足点亮定时前置（SOP §6）。"
