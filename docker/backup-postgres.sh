#!/usr/bin/env bash
# ecom-flowcart：postgres 双库定时备份脚本骨架（dry-run 可跑，无数据也可验证）。
#
# 背景/口径：见 docs/ops/backup-restore.md（SOP 单一权威）。
#   - 备份对象 = postgres 容器内三个 database：flowcart（业务）+ temporal + temporal_visibility（编排，持久执行真相）。
#   - 格式 = pg_dump -Fc（custom：压缩、选择性 restore、pg_restore 友好）。
#   - 三库同一实例但无跨库事务 → 顺序 dump，接受 RPO 近似一致（恢复语义见 SOP §4）。
#
# 用法：
#   docker/backup-postgres.sh                # 三库各产一份 timestamped .dump
#   docker/backup-postgres.sh --dry-run      # 校验连通性 + 库存在性（门禁）/命令路径，不产出（骨架期验证入口）
#
# 可覆盖环境变量（可在 .env 提供，脚本读取）：
#   POSTGRES_USER / POSTGRES_PASSWORD         # 默认 flowcart / flowcart
#   BACKUP_DIR                                # 默认 <repo>/docker/backups（已被 .gitignore 拦截）
#   BACKUP_RETENTION                          # 本地保留份数，默认 7
#   POSTGRES_HOST / POSTGRES_PORT             # 默认 localhost / 5433（compose 宿主映射；本机无 postgres 客户端则改用 docker exec）
#
# 执行通道（自动选择）：
#   1) 宿主机装有 psql/pg_dump → 直接连 POSTGRES_HOST:POSTGRES_PORT；
#   2) 否则 → docker exec 进 postgres 容器内执行（compose 拓扑内保证可用）。
set -eu

# ---- 解析参数 ----
DRY_RUN=0
for a in "$@"; do
  case "$a" in
    --dry-run) DRY_RUN=1 ;;
    *) echo "未知参数: $a（支持 --dry-run）" >&2; exit 2 ;;
  esac
done

# ---- 变量（含 .env 覆盖） ----
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$REPO_ROOT/.env" ] && set -a && . "$REPO_ROOT/.env" && set +a

PGUSER="${POSTGRES_USER:-flowcart}"
PGPASSWORD="${POSTGRES_PASSWORD:-flowcart}"
export PGPASSWORD
PGHOST="${POSTGRES_HOST:-localhost}"
PGPORT="${POSTGRES_PORT:-5433}"
BACKUP_DIR="${BACKUP_DIR:-$REPO_ROOT/docker/backups}"
BACKUP_RETENTION="${BACKUP_RETENTION:-7}"

# 备份对象：业务库 + 两个 temporal 编排库
DB_LIST="flowcart temporal temporal_visibility"
STAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"

# ---- 命令通道探测 ----
USE_DOCKER=0
if command -v pg_dump >/dev/null 2>&1; then
  echo "[backup] 使用宿主机 pg_dump（$PGHOST:$PGPORT）"
else
  if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q '^flowcart-postgres$'; then
    USE_DOCKER=1
    echo "[backup] 宿主机无 pg_dump，改走 docker exec flowcart-postgres"
  else
    echo "[backup] 错误：宿主机无 pg_dump 且容器 flowcart-postgres 未运行；装 postgresql-client 或先 docker compose up -d postgres。" >&2
    exit 1
  fi
fi

# ---- 库可达性探测：真正连上目标库执行一条 SQL ----
# 注意：不要用 pg_isready 代替 —— 实测 pg_isready -U u -d <不存在的库> 仍返回 0
#（它只证明服务端在 accept 连接，不校验库是否存在）。用它会让 dry-run 对"库根本没建出来"
# 也报 ok（假阳性），而那正是 init 脚本失效时最需要被发现的故障。
db_probe() { # db_probe "<db>"
  if [ "$USE_DOCKER" = "1" ]; then
    docker exec flowcart-postgres psql -U "$PGUSER" -d "$1" -Atc 'SELECT 1' >/dev/null 2>&1
  else
    PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$1" -Atc 'SELECT 1' >/dev/null 2>&1
  fi
}

# ---- dry-run：连通性 + 库存在性校验即止（任一库不可用则非零退出，作门禁） ----
if [ "$DRY_RUN" = "1" ]; then
  echo "[backup] dry-run：校验连接与数据库存在性（不产出 dump）"
  FAILED=0
  for db in $DB_LIST; do
    if db_probe "$db"; then
      echo "[backup] ok: $db 可连接（库存在）"
    else
      echo "[backup] fail: $db 不可达或不存在（检查 postgres 是否起来、init 是否建库）" >&2
      FAILED=1
    fi
  done
  if [ "$FAILED" = "1" ]; then
    echo "[backup] dry-run 失败：存在不可用的目标库，备份不可信。" >&2
    exit 1
  fi
  echo "[backup] dry-run 完成。目标: $BACKUP_DIR"
  exit 0
fi

# ---- 执行备份 ----
echo "[backup] 开始: $(date -Is)  →  $BACKUP_DIR"
for db in $DB_LIST; do
  out="$BACKUP_DIR/${db}-${STAMP}.dump"
  if [ "$USE_DOCKER" = "1" ]; then
    docker exec -e PGPASSWORD="$PGPASSWORD" flowcart-postgres \
      pg_dump -U "$PGUSER" -Fc -d "$db" > "$out"
  else
    PGPASSWORD="$PGPASSWORD" pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -Fc -d "$db" > "$out"
  fi
  # -Fc 输出非零退出即失败；空文件视为异常（骨架期 schema 未建会得到空 dump）
  if [ ! -s "$out" ]; then
    echo "[backup] warn: ${db} dump 为空（该库可能尚无内容）—— 已产出空占位，勿当有效备份" 
  fi
  echo "[backup] ok: $out ($(du -h "$out" | cut -f1))"
done

# ---- 保留策略（本地轮换，默认留 7 份/库） ----
for db in $DB_LIST; do
  # 按 mtime 保留最新 BACKUP_RETENTION 份，删旧
  ls -1t "$BACKUP_DIR"/${db}-*.dump 2>/dev/null | tail -n +$((BACKUP_RETENTION + 1)) | while read -r old; do
    rm -f "$old" && echo "[backup] 轮换删除: $old"
  done
done

echo "[backup] 完成: $(date -Is)"
echo "[backup] 提示: 备份含明文数据；灾备需异机/对象存储；点亮定时前先跑一次恢复演练（见 SOP §5-6）。"
