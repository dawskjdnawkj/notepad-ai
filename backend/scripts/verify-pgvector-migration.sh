#!/usr/bin/env bash
#
# SimpleVectorStore → pgvector 数据迁移的端到端验收脚本。
#
# 迁移方式就是一次「快照恢复」：simple 模式下做快照 → 切 pgvector → 恢复同一份快照。
# 不需要任何专门的迁移工具，而且向量逐位原样，差异被隔离在检索层而不是 Embedding 层。
#
# 验证内容：
#   1. 迁移前后规模一致（210 / 41 / 1024）
#   2. 语义 round-trip 比对（文本 / metadata / 向量全一致）—— 不能 diff，jsonb 会重排 key
#   3. 14 个固定回归用例在 pgvector 下仍然满分
#   4. 末了切回 simple，确认可回退且数据文件完好
#
# 前置：本机跑着一个带 pgvector 扩展的 PostgreSQL，容器名 notepad-pgvector，端口 5433。
#
# 用法：
#   NOTEPAD_USER=<账号> NOTEPAD_PASSWORD=<密码> NOTEPAD_PGVECTOR_PASSWORD=<密码> \
#     bash backend/scripts/verify-pgvector-migration.sh

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WS="$BACKEND_DIR/../.idea/workspace.xml"
LOG_FILE="/tmp/notepad-backend.log"
PG_CONTAINER="${PG_CONTAINER:-notepad-pgvector}"
PG_DB="${PG_DB:-notepad_vector}"

PASSED=0
FAILED=0
SNAPSHOT=""

pass() { PASSED=$((PASSED + 1)); echo "  [PASS] $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  [FAIL] $1"; }
info() { echo "  ---- $1"; }
section() { echo; echo "=== $1 ==="; }

ensure_simple_mode() {
    if [ -n "$(netstat -ano 2>/dev/null | grep 'LISTENING' | grep ':8080')" ]; then
        local current
        current="$(curl -s "$BASE_URL/api/ai/notes/index/status" -H "$AUTH" 2>/dev/null | jq -r '.data.storeType // empty')"
        [ "$current" = "simple" ] && return 0
    fi
    info "脚本结束时切回 simple 模式"
    restart_backend
}

cleanup() {
    if [ -n "$TOKEN" ]; then
        ensure_simple_mode
    fi
}
TOKEN=""

if [ -z "${NOTEPAD_USER:-}" ] || [ -z "${NOTEPAD_PASSWORD:-}" ]; then
    echo "缺少凭据。请设置 NOTEPAD_USER 和 NOTEPAD_PASSWORD。" >&2
    exit 2
fi
if [ -z "${NOTEPAD_PGVECTOR_PASSWORD:-}" ]; then
    echo "缺少 NOTEPAD_PGVECTOR_PASSWORD（pgvector 实例的密码）。" >&2
    exit 2
fi
for tool in curl jq python; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖：$tool" >&2; exit 2; }
done

restart_backend() { # [额外环境变量赋值 ...]
    local extra=("$@")
    local pid
    pid="$(netstat -ano 2>/dev/null | grep 'LISTENING' | grep ':8080' | head -1 | awk '{print $NF}')"
    [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1
    sleep 3

    cd "$BACKEND_DIR" || exit 2
    (
        get() { sed -n "s/.*<env name=\"$1\" value=\"\([^\"]*\)\".*/\1/p" "$WS" | head -1; }
        for name in DB_USERNAME DB_PASSWORD JWT_SECRET AI_DASHSCOPE_API_KEY MAIL_USERNAME MAIL_PASSWORD; do
            if [ -z "$(eval echo "\${$name:-}")" ]; then
                export "$name=$(get "$name")"
            fi
        done
        for assignment in "${extra[@]:-}"; do
            [ -n "$assignment" ] && export "$assignment"
        done
        nohup mvn spring-boot:run > "$LOG_FILE" 2>&1 &
        disown
    )
    for _ in $(seq 1 60); do
        sleep 2
        netstat -ano 2>/dev/null | grep 'LISTENING' | grep -q ':8080' && { sleep 2; return 0; }
    done
    echo "后端启动超时，请查看 $LOG_FILE" >&2
    exit 2
}

login() {
    TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$NOTEPAD_USER\",\"password\":\"$NOTEPAD_PASSWORD\"}" \
        | jq -r '.data.token // empty')"
    [ -n "$TOKEN" ] || { echo "登录失败。" >&2; exit 2; }
    AUTH="Authorization: Bearer $TOKEN"
}

auth() { curl -s "$@" -H "$AUTH"; }
pg() { docker exec "$PG_CONTAINER" psql -U postgres -d "$PG_DB" -t -c "$1" 2>&1; }

# ---------------------------------------------------------------- 步骤 1

section "步骤 1：simple 模式下建立迁移快照"
login
STORE_TYPE="$(auth "$BASE_URL/api/ai/notes/index/status" | jq -r '.data.storeType // empty')"
if [ "$STORE_TYPE" = "simple" ]; then
    pass "当前是 simple 模式"
else
    fail "当前不是 simple 模式（storeType=$STORE_TYPE），请先切回"
    exit 1
fi

CHUNKS_BEFORE="$(auth "$BASE_URL/api/ai/notes/index/status" | jq -r '.data.chunkCount')"
info "simple 模式下片段数=$CHUNKS_BEFORE"

SNAP="$(auth -X POST "$BASE_URL/api/ai/notes/index/backups")"
SNAPSHOT="$(echo "$SNAP" | jq -r '.data.fileName // empty')"
if [ -n "$SNAPSHOT" ]; then
    pass "迁移快照已创建：$SNAPSHOT"
else
    fail "创建迁移快照失败：$(echo "$SNAP" | jq -c '.message')"
    exit 1
fi

# ---------------------------------------------------------------- 步骤 2

section "步骤 2：切到 pgvector 并从快照恢复（这就是迁移）"
restart_backend "NOTEPAD_VECTOR_STORE_TYPE=pgvector" "NOTEPAD_PGVECTOR_PASSWORD=$NOTEPAD_PGVECTOR_PASSWORD"
login
MODE="$(auth "$BASE_URL/api/ai/notes/index/status" | jq -r '.data.storeType')"
if [ "$MODE" = "pgvector" ]; then
    pass "已切到 pgvector 模式"
else
    fail "模式切换失败（storeType=$MODE）"
    exit 1
fi

RESTORE="$(auth -X POST "$BASE_URL/api/ai/notes/index/backups/$SNAPSHOT/restore")"
COUNT="$(echo "$RESTORE" | jq -r '.data.restoredEntryCount // 0')"
if [ "$COUNT" = "$CHUNKS_BEFORE" ]; then
    pass "恢复片段数与快照一致（$COUNT）"
else
    fail "恢复片段数 $COUNT，期望 $CHUNKS_BEFORE"
fi

# ---------------------------------------------------------------- 步骤 3

section "步骤 3：迁移正确性验证"
INFO_LINE="$(pg "SELECT count(*), count(DISTINCT metadata->>'noteId'), min(vector_dims(embedding)), max(vector_dims(embedding)) FROM vector_store;" | head -1)"
info "SQL 侧：$INFO_LINE"
read -r ROWS NOTES DIM_MIN DIM_MAX <<<"$(echo "$INFO_LINE" | tr -d ' \r' | tr '|' ' ')"
if [ "$ROWS" = "$CHUNKS_BEFORE" ]; then
    pass "行数一致（$ROWS）"
else
    fail "行数 $ROWS，期望 $CHUNKS_BEFORE"
fi
if [ "$DIM_MIN" = "1024" ] && [ "$DIM_MAX" = "1024" ]; then
    pass "向量维度均为 1024"
else
    fail "向量维度异常：min=$DIM_MIN max=$DIM_MAX"
fi

EXPORT="$(auth -X POST "$BASE_URL/api/ai/notes/index/backups" | jq -r '.data.fileName // empty')"
if [ -n "$EXPORT" ]; then
    pass "已从 pgvector 导出对照快照：$EXPORT"
else
    fail "从 pgvector 导出快照失败"
fi

BACKUP_DIR="$BACKEND_DIR/data/backups"
python - "$BACKUP_DIR/$SNAPSHOT" "$BACKUP_DIR/$EXPORT" <<'PYEOF' && PY_OK=0 || PY_OK=1
import json, sys
src = json.load(open(sys.argv[1], encoding='utf-8'))
dst = json.load(open(sys.argv[2], encoding='utf-8'))
assert set(src) == set(dst), 'id sets differ'
bad_text = [k for k in src if src[k].get('text') != dst[k].get('text')]
bad_meta = [k for k in src if src[k].get('metadata') != dst[k].get('metadata')]
bad_vec = [k for k in src
           if [float(x) for x in src[k]['embedding']] != [float(x) for x in dst[k]['embedding']]]
print('entries=%d text_mismatch=%d metadata_mismatch=%d embedding_mismatch=%d'
      % (len(src), len(bad_text), len(bad_meta), len(bad_vec)))
sys.exit(0 if not (bad_text or bad_meta or bad_vec) else 1)
PYEOF
if [ "$PY_OK" = "0" ]; then
    pass "语义 round-trip 完全一致（文本 / metadata / 向量）"
else
    fail "round-trip 存在差异，迁移未做到逐位无损"
fi

# ---------------------------------------------------------------- 步骤 3.5

section "步骤 3.5：pgvector 模式下的损坏快照保护"
ROWS_BEFORE="$(pg "SELECT count(*) FROM vector_store;" | tr -d ' \n')"
BAD="simple-vector-store-20200101-000009-deadbeef.json"
head -c 500 "$BACKUP_DIR/$EXPORT" > "$BACKUP_DIR/$BAD"

VERIFY="$(auth "$BASE_URL/api/ai/notes/index/backups/$BAD/verify")"
if [ "$(echo "$VERIFY" | jq -r '.data.valid')" = "false" ]; then
    pass "verify 把损坏快照判为不可恢复"
else
    fail "verify 未识别出损坏快照：$(echo "$VERIFY" | jq -c '.data')"
fi

BAD_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$BASE_URL/api/ai/notes/index/backups/$BAD/restore" -H "$AUTH")"
if [ "$BAD_CODE" = "400" ]; then
    pass "restore 损坏快照被拒绝（HTTP 400）"
else
    fail "restore 损坏快照返回 $BAD_CODE，期望 400"
fi

ROWS_AFTER="$(pg "SELECT count(*) FROM vector_store;" | tr -d ' \n')"
if [ "$ROWS_AFTER" = "$ROWS_BEFORE" ]; then
    pass "损坏快照未破坏向量库（仍是 $ROWS_AFTER 行）"
else
    fail "向量库被破坏：$ROWS_BEFORE -> $ROWS_AFTER 行"
fi

rm -f "$BACKUP_DIR/$BAD"

PRE_RESTORE="$(ls -t "$BACKUP_DIR"/*pre-restore*.json 2>/dev/null | head -1)"
if [ -n "$PRE_RESTORE" ]; then
    pass "恢复前自动留档存在（$(basename "$PRE_RESTORE")）"
else
    fail "没有找到恢复前留档"
fi

# ---------------------------------------------------------------- 步骤 4

section "步骤 4：pgvector 下的回归基线"
RUN="$(auth -X POST "$BASE_URL/api/ai/evaluations/runs")"
info "$(echo "$RUN" | jq -c '{caseCount:.data.caseCount, passRate:.data.passRate, hitRate:.data.hitRate, rejectionAccuracy:.data.rejectionAccuracy, wrongReferenceCount:.data.wrongReferenceCount}')"
if [ "$(echo "$RUN" | jq -r '.data.passRate')" = "1.0" ] \
    && [ "$(echo "$RUN" | jq -r '.data.rejectionAccuracy')" = "1.0" ] \
    && [ "$(echo "$RUN" | jq -r '.data.wrongReferenceCount')" = "0" ]; then
    pass "pgvector 下 14 个固定用例仍为满分"
else
    fail "pgvector 下回归指标与基线不一致，需逐条人工确认"
fi

# ---------------------------------------------------------------- 步骤 5

section "步骤 5：切回 simple，确认可回退"
restart_backend
login
STATUS="$(auth "$BASE_URL/api/ai/notes/index/status")"
info "$(echo "$STATUS" | jq -c '{storeType:.data.storeType, state:.data.state, chunkCount:.data.chunkCount}')"
if [ "$(echo "$STATUS" | jq -r '.data.storeType')" = "simple" ] \
    && [ "$(echo "$STATUS" | jq -r '.data.state')" = "HEALTHY" ] \
    && [ "$(echo "$STATUS" | jq -r '.data.chunkCount')" = "$CHUNKS_BEFORE" ]; then
    pass "已回退到 simple 且索引完好（$CHUNKS_BEFORE 个片段）"
else
    fail "回退后状态异常"
fi

# ---------------------------------------------------------------- 汇总

section "汇总"
echo "  通过 $PASSED 项，失败 $FAILED 项"
[ "$FAILED" -eq 0 ] || exit 1
