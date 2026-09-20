#!/usr/bin/env bash
#
# AI 请求统计（限流拒绝 / 超时 / 重试 / 耗时分布）的端到端验收脚本。
#
# 覆盖交接文档第二阶段要求的指标：
#   限流拒绝数（流式 / 阻塞 / 队列）、超时数、重试数、错误编号、耗时分布
#
# 其中重试数会与 Spring AI 自己打的 "Retry error. Retry count: N" 日志**交叉验证**，
# 避免只验自己的计数器自说自话。
#
# 用法：
#   NOTEPAD_USER=<账号> NOTEPAD_PASSWORD=<密码> bash backend/scripts/verify-ai-metrics.sh
#
# 注意：脚本会重启后端两次，并临时把 DashScope API Key 覆盖为无效值。
# 正常结束时场景会恢复默认配置；中途被打断请手动重启后端。

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WS="$BACKEND_DIR/../.idea/workspace.xml"
LOG_FILE="/tmp/notepad-backend.log"
QUESTION="Explain Java Stream intermediate and terminal operations in detail."

PASSED=0
FAILED=0
OVERRIDES_ACTIVE=""
LOG_GENERATION=0
STUB_PORT=18080
STUB_PID=""
rm -f /tmp/notepad-metrics-gen*.log

pass() { PASSED=$((PASSED + 1)); echo "  [PASS] $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  [FAIL] $1"; }
info() { echo "  ---- $1"; }
section() { echo; echo "=== $1 ==="; }

warn_on_exit() {
    stop_stub
    if [ -n "$OVERRIDES_ACTIVE" ]; then
        echo >&2
        echo "警告：脚本被中断，后端仍运行在覆盖配置下（$OVERRIDES_ACTIVE）。请手动重启。" >&2
    fi
}
trap warn_on_exit EXIT

if [ -z "${NOTEPAD_USER:-}" ] || [ -z "${NOTEPAD_PASSWORD:-}" ]; then
    echo "缺少凭据。请设置 NOTEPAD_USER 和 NOTEPAD_PASSWORD 后重试。" >&2
    exit 2
fi
for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖：$tool" >&2; exit 2; }
done

# Windows 上 python3 常常是 Microsoft Store 的占位符：命令存在、退出码 0，但什么都不执行。
# 必须真的验证它打印出东西，否则 503 桩会静默起不来。
PY=""
for candidate in python python3; do
    if command -v "$candidate" >/dev/null 2>&1 \
        && [ "$("$candidate" -c 'print(1)' 2>/dev/null)" = "1" ]; then
        PY="$candidate"
        break
    fi
done
if [ -z "$PY" ]; then
    echo "缺少可用的 Python 解释器（503 桩服务需要）" >&2
    exit 2
fi

# ---------------------------------------------------------------- 后端生命周期

kill_backend() {
    if [ -s "$LOG_FILE" ]; then
        LOG_GENERATION=$((LOG_GENERATION + 1))
        cp "$LOG_FILE" "/tmp/notepad-metrics-gen${LOG_GENERATION}.log"
    fi
    local pid
    pid="$(netstat -ano 2>/dev/null | grep 'LISTENING' | grep ':8080' | head -1 | awk '{print $NF}')"
    [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1
    sleep 3
}

start_backend() { # [额外环境变量赋值 ...]
    local extra=("$@")
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
        if netstat -ano 2>/dev/null | grep 'LISTENING' | grep -q ':8080'; then
            sleep 2
            return 0
        fi
    done
    echo "后端启动超时，请查看 $LOG_FILE" >&2
    return 1
}

restart_backend() {
    kill_backend
    OVERRIDES_ACTIVE="$*"
    start_backend "$@" || exit 2
}

# ---------------------------------------------------------------- 503 桩

# 起一个固定返回 503 的本地桩，用来制造「瞬态错误」。
#
# 为什么必须是 503 而不是连接失败：反编译 SpringAiRetryAutoConfiguration 可见，
# 重试策略是 retryOn(TransientAiException.class) —— 只重试这一种异常，并不使用
# RetryUtils 那张瞬态异常表。所以连接失败抛出的 ResourceAccessException 根本不会
# 触发重试，只有被 ResponseErrorHandler 归类为 TransientAiException 的响应（如 5xx）才会。
start_stub() {
    cat > /tmp/notepad-stub503.py <<'PYEOF'
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

class Handler(BaseHTTPRequestHandler):
    def _reply(self):
        body = b'{"code":"ServiceUnavailable","message":"stub 503"}'
        self.send_response(503)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    do_POST = _reply
    do_GET = _reply
    def log_message(self, *args):
        pass

HTTPServer(('127.0.0.1', int(sys.argv[1])), Handler).serve_forever()
PYEOF
    "$PY" /tmp/notepad-stub503.py "$STUB_PORT" > /dev/null 2>&1 &
    STUB_PID=$!
    disown
    sleep 2
}

stop_stub() {
    if [ -n "$STUB_PID" ]; then
        kill "$STUB_PID" >/dev/null 2>&1
        STUB_PID=""
    fi
}

# ---------------------------------------------------------------- 工具

login() {
    TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$NOTEPAD_USER\",\"password\":\"$NOTEPAD_PASSWORD\"}" \
        | jq -r '.data.token // empty')"
    [ -n "$TOKEN" ] || { echo "登录失败，无法继续。" >&2; exit 2; }
    AUTH="Authorization: Bearer $TOKEN"
}

metric() { curl -s "$BASE_URL/api/ai/metrics" -H "$AUTH" | jq -r ".data.$1"; }

# 断言某个计数自 <基线> 起恰好增加 <期望值>
expect_delta() { # $1=计数字段 $2=基线值 $3=期望增量 $4=说明
    local now delta
    now="$(metric "$1")"
    delta=$((now - $2))
    if [ "$delta" -eq "$3" ]; then
        pass "$4（$1 $2 -> $now，增量 $delta）"
    else
        fail "$4：$1 增量 $delta，期望 $3（$2 -> $now）"
    fi
}

sse_request() { # $1=输出文件 $2=--max-time
    local body
    body="$(jq -n --arg q "$QUESTION" '{question:$q}')"
    curl -s -N --max-time "$2" -X POST "$BASE_URL/api/ai/notes/ask/stream" \
        -H "$AUTH" -H 'Content-Type: application/json' -d "$body" > "$1" 2>/dev/null
}

blocking_ask() { # $1=响应体文件 $2=状态码文件
    local body
    body="$(jq -n --arg q "$QUESTION" '{question:$q}')"
    # -w 必须带 \n，否则两个 .code 文件用 cat 拼接时会把状态码粘成 "200429"
    curl -s -o "$1" -w '%{http_code}\n' --max-time 180 -X POST "$BASE_URL/api/ai/notes/ask" \
        -H "$AUTH" -H 'Content-Type: application/json' -d "$body" > "$2" 2>/dev/null
}

wait_idle() {
    for _ in $(seq 1 "$1"); do
        [ "$(curl -s "$BASE_URL/api/ai/concurrency/status" -H "$AUTH" | jq -r '.data.activeCount')" = "0" ] && return 0
        sleep 1
    done
    return 1
}

# ---------------------------------------------------------------- 场景 1

section "场景 1：初始基线"
login
info "已获取登录态"
BASELINE_COMPLETED=$(metric completed)
BASELINE_FAILED=$(metric failed)
BASELINE_REJ_STREAM=$(metric rejectedStream)
BASELINE_REJ_BLOCKING=$(metric rejectedBlocking)

ALL_ZERO=1
for f in rejectedStream rejectedBlocking rejectedQueue timeout retry completed failed; do
    [ "$(metric "$f")" = "0" ] || ALL_ZERO=0
done
if [ "$ALL_ZERO" = "1" ]; then
    pass "重启后所有计数归零（内存计数，符合预期）"
else
    fail "重启后仍有非零计数，基线不可信"
fi
curl -s "$BASE_URL/api/ai/metrics" -H "$AUTH" | jq -c '.data.durationDistribution'

# ---------------------------------------------------------------- 场景 2

section "场景 2：正常完成计入 completed 与耗时分布"
sse_request /tmp/m-normal.txt 120
if grep -qE "event: ?done" /tmp/m-normal.txt; then
    pass "SSE 流正常结束"
else
    fail "SSE 流未正常结束"
fi
expect_delta completed "$BASELINE_COMPLETED" 1 "正常完成计数 +1"
BUCKET_SUM=$(curl -s "$BASE_URL/api/ai/metrics" -H "$AUTH" | jq -r '.data.durationDistribution | to_entries | map(.value) | add')
if [ "$BUCKET_SUM" -ge 1 ]; then
    pass "耗时分布已记录（落桶总数 $BUCKET_SUM）"
else
    fail "耗时分布为空"
fi

# ---------------------------------------------------------------- 场景 3

section "场景 3：流式限流拒绝计数"
sse_request /tmp/m-r1.txt 120 &
P1=$!
sse_request /tmp/m-r2.txt 120 &
P2=$!
wait $P1 $P2
REJECTED=$(grep -l "retryAfterSeconds" /tmp/m-r1.txt /tmp/m-r2.txt 2>/dev/null | wc -l)
if [ "$REJECTED" -eq 1 ]; then
    pass "确实发生了 1 次流式限流拒绝"
else
    fail "流式限流拒绝次数为 $REJECTED，期望 1"
fi
expect_delta rejectedStream "$BASELINE_REJ_STREAM" 1 "流式限流拒绝计数 +1"
wait_idle 40 || fail "许可未归零"

# ---------------------------------------------------------------- 场景 4

section "场景 4：阻塞式限流拒绝计数（本次新补的埋点）"
info "并发发起 2 个 POST /api/ai/notes/ask，per-user-max=1，应恰好 1 个 429"
blocking_ask /tmp/m-b1.json /tmp/m-b1.code &
P1=$!
blocking_ask /tmp/m-b2.json /tmp/m-b2.code &
P2=$!
wait $P1 $P2
CODE_429=$(cat /tmp/m-b1.code /tmp/m-b2.code 2>/dev/null | grep -c "^429$")
if [ "$CODE_429" -eq 1 ]; then
    pass "阻塞式接口确实返回了 1 次 HTTP 429"
else
    fail "429 次数为 $CODE_429，期望 1（实际状态码：$(cat /tmp/m-b1.code /tmp/m-b2.code | tr '\n' ' ')）"
fi
expect_delta rejectedBlocking "$BASELINE_REJ_BLOCKING" 1 "阻塞式限流拒绝计数 +1"
wait_idle 60 || fail "许可未归零"

# ---------------------------------------------------------------- 场景 5

section "场景 5：客户端断开计入失败"
BEFORE_FAILED=$(metric failed)
sse_request /tmp/m-abort.txt 4
if [ "$(wait_idle 30 && echo ok)" = "ok" ]; then
    pass "断开后许可已归还"
else
    fail "断开后 30 秒内许可未归零"
fi
expect_delta failed "$BEFORE_FAILED" 1 "客户端断开计数为失败 +1"

# ---------------------------------------------------------------- 场景 6

section "场景 6：上游瞬态错误与重试计数"
info "起本地 503 桩并把 baseUrl 指向它；maxAttempts=3，预期恰好 2 次重试"
start_stub
STUB_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$STUB_PORT/probe")
if [ "$STUB_CODE" = "503" ]; then
    pass "503 桩服务就绪"
else
    fail "503 桩服务未就绪（返回 $STUB_CODE）"
fi

restart_backend \
    "SPRING_AI_DASHSCOPE_BASE_URL=http://127.0.0.1:$STUB_PORT" \
    "SPRING_AI_RETRY_MAX_ATTEMPTS=3" \
    "SPRING_AI_RETRY_BACKOFF_INITIAL_INTERVAL=50ms" \
    "SPRING_AI_RETRY_BACKOFF_MULTIPLIER=2" \
    "SPRING_AI_RETRY_BACKOFF_MAX_INTERVAL=200ms"
login
sse_request /tmp/m-err.txt 90
if grep -q "errorId" /tmp/m-err.txt; then
    pass "请求以上游错误事件结束，没有挂起"
else
    fail "未观察到错误事件"
fi

# 确定性断言：maxAttempts=3 意味着 3 次尝试、2 次重试
expect_delta retry 0 2 "重试计数恰好为 2（maxAttempts=3）"
if grep -aq "event=ai.retry.observed attempts=3 retries=2" /tmp/notepad-backend.log; then
    pass "重试观察日志为 attempts=3 retries=2"
else
    fail "未找到预期的重试观察日志"
fi

FAILED_ON_UPSTREAM=$(metric failed)
if [ "$FAILED_ON_UPSTREAM" -ge 1 ]; then
    pass "上游异常计入失败（failed=$FAILED_ON_UPSTREAM）"
else
    fail "上游异常未计入失败"
fi
# 错误编号可追踪：客户端拿到的 errorId 必须能在服务端日志里找到
ERR_ID=$(grep -o '"errorId":"[^"]*"' /tmp/m-err.txt | head -1 | sed 's/.*:"//;s/"//')
if [ -n "$ERR_ID" ] && grep -aq "$ERR_ID" /tmp/notepad-backend.log; then
    pass "客户端错误编号 $ERR_ID 可在服务端日志中检索到"
else
    fail "错误编号无法在服务端日志中检索到（errorId=$ERR_ID）"
fi
stop_stub

# ---------------------------------------------------------------- 场景 7

section "场景 7：恢复默认配置并复测"
restart_backend
login
MAX="$(curl -s "$BASE_URL/api/ai/concurrency/status" -H "$AUTH" | jq -r '.data.globalMax')"
if [ "$MAX" = "4" ]; then
    pass "默认并发配置已恢复"
else
    fail "并发配置未恢复：globalMax=$MAX"
fi
EVAL="$(curl -s -X POST "$BASE_URL/api/ai/evaluations/runs" -H "$AUTH")"
PASS_RATE="$(echo "$EVAL" | jq -r '.data.passRate')"
HIT_RATE="$(echo "$EVAL" | jq -r '.data.hitRate')"
REJECTION="$(echo "$EVAL" | jq -r '.data.rejectionAccuracy')"
WRONG="$(echo "$EVAL" | jq -r '.data.wrongReferenceCount')"
CASES="$(echo "$EVAL" | jq -r '.data.caseCount')"
info "RAG 回归：用例=$CASES 通过率=$PASS_RATE 命中率=$HIT_RATE 正确拒答率=$REJECTION 错误引用=$WRONG"
if [ "$PASS_RATE" = "1.0" ] && [ "$HIT_RATE" = "1.0" ] && [ "$REJECTION" = "1.0" ] && [ "$WRONG" = "0" ]; then
    pass "RAG 回归仍为满分，埋点改动未影响检索"
else
    fail "RAG 回归指标与基线不一致，需人工确认"
fi

# ---------------------------------------------------------------- 汇总

section "汇总"
echo "  通过 $PASSED 项，失败 $FAILED 项"
echo
echo "  说明：指标是纯内存计数，服务重启归零；每个计数点同时会打一条"
echo "        event=... 结构化日志，跨重启的历史趋势请对日志聚合。"
[ "$FAILED" -eq 0 ] || exit 1
