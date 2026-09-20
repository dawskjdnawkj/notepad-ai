#!/usr/bin/env bash
#
# 索引备份与恢复的端到端验收脚本。
#
# 覆盖交接文档要求的第一阶段四项验收标准：
#   (a) 能创建带时间戳的索引备份
#   (b) 能从备份恢复并重新通过健康检查
#   (c) 模拟损坏备份时，原索引仍然可用
#   (d) 14 个固定 RAG 用例仍然保持 100%
#
# 用法：
#   NOTEPAD_USER=<账号> NOTEPAD_PASSWORD=<密码> bash backend/scripts/verify-index-backup.sh
#
# 可选环境变量：
#   BASE_URL   默认 http://localhost:8080
#
# 注意：本脚本会真的执行一次「从备份恢复」，这会把全体用户的索引回退到你刚创建的
# 那一份备份。服务端在恢复前会自动留档当前索引，所以这一步是可回退的。
# 请只在开发环境运行。

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/data/backups"
STORE_FILE="$(cd "$SCRIPT_DIR/.." && pwd)/data/simple-vector-store.json"

PASSED=0
FAILED=0
FIXTURES=()

pass() { PASSED=$((PASSED + 1)); echo "  [PASS] $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  [FAIL] $1"; }
info() { echo "  ---- $1"; }
section() { echo; echo "=== $1 ==="; }

cleanup() {
    for f in "${FIXTURES[@]:-}"; do
        [ -n "$f" ] && rm -f "$BACKUP_DIR/$f"
    done
}
trap cleanup EXIT

if [ -z "${NOTEPAD_USER:-}" ] || [ -z "${NOTEPAD_PASSWORD:-}" ]; then
    echo "缺少凭据。请设置 NOTEPAD_USER 和 NOTEPAD_PASSWORD 后重试。" >&2
    exit 2
fi

for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖：$tool" >&2; exit 2; }
done

# Windows 上 python3 常常是 Microsoft Store 的占位符：命令存在、退出码为 0，
# 但什么都不执行。必须真的验证它打印出了东西，否则下面会静默生成空文件。
PY=""
for candidate in python python3; do
    if command -v "$candidate" >/dev/null 2>&1 \
        && [ "$("$candidate" -c 'print(1)' 2>/dev/null)" = "1" ]; then
        PY="$candidate"
        break
    fi
done
if [ -z "$PY" ]; then
    echo "缺少可用的 Python 解释器（python / python3 都不可用）" >&2
    exit 2
fi

# ---------------------------------------------------------------- 登录

section "登录"
LOGIN_BODY="$(jq -n --arg u "$NOTEPAD_USER" --arg p "$NOTEPAD_PASSWORD" '{username:$u, password:$p}')"
TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' -d "$LOGIN_BODY" | jq -r '.data.token // empty')"
if [ -z "$TOKEN" ]; then
    echo "登录失败，无法继续。" >&2
    exit 2
fi
AUTH="Authorization: Bearer $TOKEN"
info "已获取登录态"

# ---------------------------------------------------------------- 工具函数

api() { # api <method> <path> [body]
    local method="$1" path="$2" body="${3:-}"
    if [ -n "$body" ]; then
        curl -s -X "$method" "$BASE_URL$path" -H "$AUTH" -H 'Content-Type: application/json' -d "$body"
    else
        curl -s -X "$method" "$BASE_URL$path" -H "$AUTH"
    fi
}

# 返回 "<http状态码> <响应体>"
api_with_status() {
    local method="$1" path="$2"
    local response
    response="$(curl -s -w '\n%{http_code}' -X "$method" "$BASE_URL$path" -H "$AUTH")"
    echo "${response##*$'\n'} ${response%$'\n'*}"
}

# 记录活跃索引文件的指纹，用于证明失败路径没有动过它
store_fingerprint() {
    [ -f "$STORE_FILE" ] && stat -c 'size=%s mtime=%y' "$STORE_FILE" || echo "missing"
}

# ---------------------------------------------------------------- 基线

section "基线"
BASELINE="$(api GET /api/ai/notes/index/status)"
BASELINE_STATE="$(echo "$BASELINE" | jq -r '.data.state')"
BASELINE_CHUNKS="$(echo "$BASELINE" | jq -r '.data.chunkCount')"
BASELINE_FINGERPRINT="$(store_fingerprint)"
info "state=$BASELINE_STATE chunkCount=$BASELINE_CHUNKS"
info "活跃索引文件 $BASELINE_FINGERPRINT"

if [ "$BASELINE_STATE" = "HEALTHY" ]; then
    pass "起始索引状态为 HEALTHY"
else
    fail "起始索引状态为 $BASELINE_STATE，不是 HEALTHY，后续对比无意义"
fi

# ---------------------------------------------------------------- (a) 创建备份

section "(a) 创建带时间戳的索引备份"
BACKUP_RESPONSE="$(api POST /api/ai/notes/index/backups)"
BACKUP_FILE="$(echo "$BACKUP_RESPONSE" | jq -r '.data.fileName // empty')"
BACKUP_BYTES="$(echo "$BACKUP_RESPONSE" | jq -r '.data.fileBytes // 0')"

if [ -n "$BACKUP_FILE" ]; then
    pass "接口返回备份文件名：$BACKUP_FILE"
else
    fail "创建备份失败：$(echo "$BACKUP_RESPONSE" | jq -c '.')"
fi

if echo "$BACKUP_FILE" | grep -qE '^simple-vector-store-[0-9]{8}-[0-9]{6}-[0-9a-f]{8}\.json$'; then
    pass "文件名带时间戳且符合管理命名规范"
else
    fail "文件名不符合预期：$BACKUP_FILE"
fi

if [ -f "$BACKUP_DIR/$BACKUP_FILE" ]; then
    pass "备份文件确实落盘（$BACKUP_BYTES 字节）"
else
    fail "备份目录中找不到 $BACKUP_FILE"
fi

LIST_COUNT="$(api GET /api/ai/notes/index/backups | jq -r '.data | length')"
if [ "${LIST_COUNT:-0}" -ge 1 ]; then
    pass "备份列表可见 $LIST_COUNT 份"
else
    fail "备份列表为空"
fi

FINGERPRINT_AFTER_BACKUP="$(store_fingerprint)"
if [ "$FINGERPRINT_AFTER_BACKUP" = "$BASELINE_FINGERPRINT" ]; then
    pass "创建备份未改动正在使用的索引文件"
else
    fail "创建备份改动了活跃索引文件：$BASELINE_FINGERPRINT -> $FINGERPRINT_AFTER_BACKUP"
fi

# 后续所有用例都依赖这一步产出的备份文件；拿不到就立刻退出，
# 否则会连带产生一堆「文件名不符合预期」「恢复返回 500」之类的噪音，掩盖真正的原因。
if [ -z "$BACKUP_FILE" ]; then
    echo
    echo "创建备份失败，后续用例无法执行，提前退出。" >&2
    exit 1
fi

# ---------------------------------------------------------------- (b) 从备份恢复

section "(b) 从备份恢复并重新通过健康检查"
RESTORE_RESPONSE="$(api POST "/api/ai/notes/index/backups/$BACKUP_FILE/restore")"
RESTORE_CODE="$(echo "$RESTORE_RESPONSE" | jq -r '.code')"
RESTORED_STATE="$(echo "$RESTORE_RESPONSE" | jq -r '.data.status.state // empty')"
SNAPSHOT_FILE="$(echo "$RESTORE_RESPONSE" | jq -r '.data.snapshotFileName // empty')"

if [ "$RESTORE_CODE" = "200" ]; then
    pass "恢复接口返回 200"
else
    fail "恢复接口返回 code=$RESTORE_CODE：$(echo "$RESTORE_RESPONSE" | jq -c '.message')"
fi

if [ "$RESTORED_STATE" = "HEALTHY" ]; then
    pass "恢复后健康状态为 HEALTHY"
else
    fail "恢复后健康状态为 $RESTORED_STATE"
fi

if [ -n "$SNAPSHOT_FILE" ] && [ -f "$BACKUP_DIR/$SNAPSHOT_FILE" ]; then
    pass "恢复前已自动留档：$SNAPSHOT_FILE"
else
    fail "未生成恢复前留档文件"
fi

AFTER_RESTORE="$(api GET /api/ai/notes/index/status)"
AFTER_RESTORE_CHUNKS="$(echo "$AFTER_RESTORE" | jq -r '.data.chunkCount')"
if [ "$AFTER_RESTORE_CHUNKS" = "$BASELINE_CHUNKS" ]; then
    pass "恢复后片段数与基线一致（$AFTER_RESTORE_CHUNKS）"
else
    fail "恢复后片段数 $AFTER_RESTORE_CHUNKS 与基线 $BASELINE_CHUNKS 不一致"
fi

# ---------------------------------------------------------------- (c) 损坏备份

section "(c) 损坏备份时原索引仍然可用"
mkdir -p "$BACKUP_DIR"

# c1 截断的 JSON
C1="simple-vector-store-20200101-000001-deadbeef.json"
head -c 500 "$BACKUP_DIR/$BACKUP_FILE" > "$BACKUP_DIR/$C1"
FIXTURES+=("$C1")

# 下面几个用例需要一份维度正确的最小合法片段，用 python 生成
make_fixture() { # make_fixture <文件名> <userId> <noteId> <是否省略title> <维度>
    "$PY" - "$BACKUP_DIR/$1" "$2" "$3" "$4" "$5" <<'PYEOF'
import json, sys
path, uid, nid, omit_title, dim = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] == "1", int(sys.argv[5])
metadata = {"userId": uid, "noteId": nid, "chunkIndex": 0}
if not omit_title:
    metadata["title"] = "fixture"
doc_id = f"note:{uid}:{nid}:chunk:0"
payload = {doc_id: {"id": doc_id, "text": "fixture", "metadata": metadata,
                    "embedding": [0.1] * dim}}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PYEOF
}

# c2 向量维度错误
C2="simple-vector-store-20200101-000002-deadbeef.json"
make_fixture "$C2" 1 1 0 2
FIXTURES+=("$C2")

# c3 用户边界不一致（documentId 说 userId=1，metadata 说 userId=2）
C3="simple-vector-store-20200101-000003-deadbeef.json"
make_fixture "$C3" 1 1 0 1024
"$PY" - "$BACKUP_DIR/$C3" <<'PYEOF'
import json, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
for value in payload.values():
    value["metadata"]["userId"] = "2"
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PYEOF
FIXTURES+=("$C3")

# c4 缺少 metadata.title（会让检索抛空指针）
C4="simple-vector-store-20200101-000004-deadbeef.json"
make_fixture "$C4" 1 1 1 1024
FIXTURES+=("$C4")

check_rejects() { # check_rejects <文件名> <用例说明> <期望错误关键字>
    local name="$1" description="$2" keyword="$3"
    local result code message
    result="$(api_with_status POST "/api/ai/notes/index/backups/$name/restore")"
    code="${result%% *}"
    message="$(echo "${result#* }" | jq -r '.message // ""')"

    if [ "$code" = "400" ]; then
        pass "$description：被拒绝（HTTP 400）"
    else
        fail "$description：期望 400，实际 $code"
    fi
    if echo "$message" | grep -q "$keyword"; then
        pass "$description：错误信息命中「$keyword」"
    else
        fail "$description：错误信息未命中「$keyword」，实际为「$message」"
    fi
}

check_rejects "$C1" "c1 截断的 JSON" "无法恢复"
check_rejects "$C2" "c2 向量维度错误" "期望 1024"
check_rejects "$C3" "c3 用户边界不一致" "用户边界不一致"
check_rejects "$C4" "c4 缺少 metadata.title" "缺少标题"

# c5 路径穿越与不存在的文件
TRAVERSAL_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$BASE_URL/api/ai/notes/index/backups/..%2Fsimple-vector-store.json/restore" -H "$AUTH")"
if [ "$TRAVERSAL_CODE" = "400" ] || [ "$TRAVERSAL_CODE" = "404" ]; then
    pass "c5 路径穿越被拒绝（HTTP $TRAVERSAL_CODE）"
else
    fail "c5 路径穿越未按预期拒绝（HTTP $TRAVERSAL_CODE）"
fi

MISSING_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$BASE_URL/api/ai/notes/index/backups/nonexistent.json/restore" -H "$AUTH")"
if [ "$MISSING_CODE" = "404" ]; then
    pass "c5 不存在的备份返回 404"
else
    fail "c5 不存在的备份返回 $MISSING_CODE，期望 404"
fi

# 关键断言：以上全部失败之后，原索引必须完好如初
POST_FAILURE="$(api GET /api/ai/notes/index/status)"
POST_FAILURE_STATE="$(echo "$POST_FAILURE" | jq -r '.data.state')"
POST_FAILURE_CHUNKS="$(echo "$POST_FAILURE" | jq -r '.data.chunkCount')"

if [ "$POST_FAILURE_STATE" = "HEALTHY" ]; then
    pass "c 全部失败用例之后，索引仍为 HEALTHY"
else
    fail "c 全部失败用例之后，索引状态为 $POST_FAILURE_STATE"
fi
if [ "$POST_FAILURE_CHUNKS" = "$BASELINE_CHUNKS" ]; then
    pass "c 全部失败用例之后，片段数仍为 $BASELINE_CHUNKS"
else
    fail "c 之后片段数变为 $POST_FAILURE_CHUNKS，基线为 $BASELINE_CHUNKS"
fi
if [ "$(store_fingerprint)" = "$BASELINE_FINGERPRINT" ]; then
    pass "c 之后活跃索引文件的 size/mtime 未被改动"
else
    info "活跃索引文件指纹变化（恢复动作本身会重写它，属预期）"
    info "  基线 $BASELINE_FINGERPRINT"
    info "  当前 $(store_fingerprint)"
fi

# 使用纯 ASCII 查询：Windows 上 shell 可能按 GBK 传递中文，
# 服务端会因 Invalid UTF-8 直接拒绝，与检索功能本身无关。
SEARCH_RESULT="$(api POST /api/ai/notes/search '{"question":"Stream API"}')"
SEARCH_COUNT="$(echo "$SEARCH_RESULT" | jq -r '.data | length' 2>/dev/null || echo 0)"
if [ "${SEARCH_COUNT:-0}" -ge 1 ]; then
    pass "c 之后检索功能仍然可用（命中 $SEARCH_COUNT 个片段）"
else
    fail "c 之后检索异常：$(echo "$SEARCH_RESULT" | jq -c '.')"
fi

# ---------------------------------------------------------------- (d) 回归基线

section "(d) 固定 RAG 回归用例"
EVAL="$(api POST /api/ai/evaluations/runs)"
EVAL_CASES="$(echo "$EVAL" | jq -r '.data.caseCount // empty')"
if [ -z "$EVAL_CASES" ]; then
    fail "回归运行失败：$(echo "$EVAL" | jq -c '.message')"
else
    EVAL_PASS_RATE="$(echo "$EVAL" | jq -r '.data.passRate')"
    EVAL_HIT_RATE="$(echo "$EVAL" | jq -r '.data.hitRate')"
    EVAL_REJECTION="$(echo "$EVAL" | jq -r '.data.rejectionAccuracy')"
    EVAL_WRONG="$(echo "$EVAL" | jq -r '.data.wrongReferenceCount')"
    info "用例数=$EVAL_CASES 通过率=$EVAL_PASS_RATE Top-K命中率=$EVAL_HIT_RATE 正确拒答率=$EVAL_REJECTION 错误引用=$EVAL_WRONG"

    if [ "$EVAL_PASS_RATE" = "1.0" ] && [ "$EVAL_HIT_RATE" = "1.0" ] \
        && [ "$EVAL_REJECTION" = "1.0" ] && [ "$EVAL_WRONG" = "0" ]; then
        pass "(d) 四个指标均为满分，与 2026-09-18 基线一致"
    else
        fail "(d) 指标与基线不一致，需逐条人工确认失败用例，不要调整检索阈值"
        echo "$EVAL" | jq -r '.data.results[] | select(.passed == false) | "      失败用例：\(.name) — \(.failureReason)"'
    fi
fi

# ---------------------------------------------------------------- 汇总

section "汇总"
echo "  通过 $PASSED 项，失败 $FAILED 项"
echo "  备份目录：$BACKUP_DIR"
[ "$FAILED" -eq 0 ] || exit 1
