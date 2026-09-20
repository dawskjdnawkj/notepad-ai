#!/usr/bin/env bash
#
# AI 并发许可释放的端到端验收脚本。
#
# 核心断言是 AiConcurrencyLimiter 的守恒律：
#
#     activeCount() + availableGlobalPermits() == globalMax()
#
# 静止时刻这条等式必须成立。任何一条请求路径漏掉 Permit.close()，等式立刻不成立，
# 而且能区分是全局信号量泄漏还是单用户计数泄漏。
#
# 覆盖交接文档要求的五种终止路径：正常完成 / 客户端断开 / 超时 / 异常 / 服务重启。
# 其中「超时」（SseEmitter.onTimeout）见文件末尾「未覆盖项」说明。
#
# 用法：
#   NOTEPAD_USER=<账号> NOTEPAD_PASSWORD=<密码> bash backend/scripts/verify-ai-concurrency.sh
#
# 注意：本脚本会多次重启后端，并在场景 5/6 用环境变量临时覆盖并发上限和
# DashScope API Key。正常结束时场景 7 会恢复默认配置。**如果中途被打断，
# 后端可能停留在被覆盖的配置上，请手动重启一次。**

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WS="$BACKEND_DIR/../.idea/workspace.xml"
LOG_FILE="/tmp/notepad-backend.log"
# 纯 ASCII，避免 Windows 上 shell 按 GBK 传递导致服务端 Invalid UTF-8
QUESTION="Explain Java Stream intermediate and terminal operations in detail."

PASSED=0
FAILED=0
OVERRIDES_ACTIVE=""
LOG_GENERATION=0
rm -f /tmp/notepad-backend-gen*.log

pass() { PASSED=$((PASSED + 1)); echo "  [PASS] $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  [FAIL] $1"; }
info() { echo "  ---- $1"; }
section() { echo; echo "=== $1 ==="; }

warn_on_exit() {
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

# ---------------------------------------------------------------- 后端生命周期

kill_backend() {
    # 先把本次运行的日志归档。start_backend 用 > 覆盖写，
    # 不归档的话上一个场景的服务端证据会被冲掉。
    if [ -s "$LOG_FILE" ]; then
        LOG_GENERATION=$((LOG_GENERATION + 1))
        cp "$LOG_FILE" "/tmp/notepad-backend-gen${LOG_GENERATION}.log"
    fi
    local pid
    pid="$(netstat -ano 2>/dev/null | grep 'LISTENING' | grep ':8080' | head -1 | awk '{print $NF}')"
    if [ -n "$pid" ]; then
        taskkill //PID "$pid" //F >/dev/null 2>&1
    fi
    sleep 3
}

# start_backend [额外环境变量赋值 ...] —— 例如 K=V
start_backend() {
    local extra=("$@")
    cd "$BACKEND_DIR" || exit 2
    (
        # 从 IDE 配置取基础环境变量；已在当前 shell 中的则不覆盖
        get() { sed -n "s/.*<env name=\"$1\" value=\"\([^\"]*\)\".*/\1/p" "$WS" | head -1; }
        for name in DB_USERNAME DB_PASSWORD JWT_SECRET AI_DASHSCOPE_API_KEY MAIL_USERNAME MAIL_PASSWORD; do
            if [ -z "$(eval echo "\${$name:-}")" ]; then
                export "$name=$(get "$name")"
            fi
        done
        # 应用本次启动的额外覆盖
        for assignment in "${extra[@]:-}"; do
            [ -n "$assignment" ] && export "$assignment"
        done
        nohup mvn spring-boot:run > "$LOG_FILE" 2>&1 &
        disown
    )
    # mvn 启动 + Spring 上下文大约 25-40 秒
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
    if [ "$#" -gt 0 ]; then
        OVERRIDES_ACTIVE="$*"
    else
        OVERRIDES_ACTIVE=""
    fi
    start_backend "$@" || exit 2
}

# ---------------------------------------------------------------- HTTP 工具

login() {
    TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$NOTEPAD_USER\",\"password\":\"$NOTEPAD_PASSWORD\"}" \
        | jq -r '.data.token // empty')"
    [ -n "$TOKEN" ] || { echo "登录失败，无法继续。" >&2; exit 2; }
    AUTH="Authorization: Bearer $TOKEN"
}

status_json() {
    curl -s "$BASE_URL/api/ai/concurrency/status" -H "$AUTH"
}

# 发一次 SSE 请求，把输出写到文件。$1=输出文件 $2=--max-time 秒数
sse_request() {
    local out="$1" max_time="$2" body
    body="$(jq -n --arg q "$QUESTION" '{question:$q}')"
    curl -s -N --max-time "$max_time" -X POST "$BASE_URL/api/ai/notes/ask/stream" \
        -H "$AUTH" -H 'Content-Type: application/json' -d "$body" > "$out" 2>/dev/null
}

# 轮询等待许可全部归还。返回 0 表示已归零，1 表示超时（疑似泄漏）。
wait_idle() {
    local timeout="$1"
    for _ in $(seq 1 "$timeout"); do
        local current
        current="$(status_json | jq -r '.data.activeCount // -1')"
        [ "$current" = "0" ] && return 0
        sleep 1
    done
    return 1
}

# 断言守恒律与静止状态。$1=场景名
assert_idle() {
    local label="$1" raw active available max sum
    raw="$(status_json)"
    active="$(echo "$raw" | jq -r '.data.activeCount')"
    available="$(echo "$raw" | jq -r '.data.availableGlobalPermits')"
    max="$(echo "$raw" | jq -r '.data.globalMax')"
    sum=$((active + available))

    info "$label 状态：activeCount=$active availableGlobalPermits=$available globalMax=$max"

    if [ "$sum" -ne "$max" ]; then
        fail "$label 守恒律被打破：$active + $available = $sum，应等于 $max（有许可泄漏）"
        return
    fi
    if [ "$active" -ne 0 ]; then
        fail "$label activeCount=$active，应为 0（有请求占着许可没有归还）"
        return
    fi
    pass "$label 守恒律成立且许可全部归还（$active + $available = $max）"
}

# ---------------------------------------------------------------- 场景 1

section "场景 1：静默基线"
login
info "已获取登录态"
assert_idle "基线"

# ---------------------------------------------------------------- 场景 2

section "场景 2：正常完成"
sse_request /tmp/conc-normal.txt 120
if grep -qE "event: ?done" /tmp/conc-normal.txt 2>/dev/null; then
    pass "SSE 流正常结束（收到 done 事件）"
else
    fail "SSE 流未正常结束，输出末尾：$(tail -c 200 /tmp/conc-normal.txt 2>/dev/null)"
fi
if wait_idle 20; then
    assert_idle "正常完成后"
else
    fail "正常完成后 20 秒内许可未归零"
    status_json | jq -c '.data'
fi

# ---------------------------------------------------------------- 场景 3

section "场景 3：客户端中途断开"
info "发起请求并在 4 秒后掐断连接"
sse_request /tmp/conc-abort.txt 4
info "已断开，等待服务端察觉连接断开并归还许可"
if wait_idle 30; then
    assert_idle "客户端断开后"
else
    fail "客户端断开后 30 秒内许可未归零（疑似泄漏）"
    status_json | jq -c '.data'
fi

# ---------------------------------------------------------------- 场景 4

section "场景 4：单用户并发拒绝"
info "同一账号并发发起 2 个请求；per-user-max 默认为 1，应恰好拒绝 1 个"
sse_request /tmp/conc-p1.txt 120 &
PID1=$!
sse_request /tmp/conc-p2.txt 120 &
PID2=$!
wait $PID1 $PID2
# retryAfterSeconds 只在限流拒绝的事件体里出现，是纯 ASCII 的可靠标记
REJECTED=$(grep -l "retryAfterSeconds" /tmp/conc-p1.txt /tmp/conc-p2.txt 2>/dev/null | wc -l)
if [ "$REJECTED" -eq 1 ]; then
    pass "恰好 1 个请求被并发限制拒绝（另 1 个正常完成）"
else
    fail "被拒绝的请求数为 $REJECTED，期望 1"
fi
if wait_idle 40; then
    assert_idle "并发拒绝后"
else
    fail "并发拒绝后 40 秒内许可未归零"
    status_json | jq -c '.data'
fi

# ---------------------------------------------------------------- 场景 5

section "场景 5：全局满载拒绝"
info "重启后端，global-max 覆盖为 1、per-user-max 覆盖为 2"
restart_backend "NOTEPAD_AI_CONCURRENCY_GLOBAL_MAX=1" "NOTEPAD_AI_CONCURRENCY_PER_USER_MAX=2"
login
OVERRIDE_MAX="$(status_json | jq -r '.data.globalMax')"
if [ "$OVERRIDE_MAX" = "1" ]; then
    pass "环境变量覆盖生效（globalMax=1）"
else
    fail "覆盖未生效，globalMax=$OVERRIDE_MAX，本场景结论无效"
fi
assert_idle "覆盖配置后基线"

info "同账号并发发起 2 个请求；此时 per-user-max=2，应由全局上限拒绝 1 个"
sse_request /tmp/conc-g1.txt 120 &
PID1=$!
sse_request /tmp/conc-g2.txt 120 &
PID2=$!
wait $PID1 $PID2
REJECTED=$(grep -l "retryAfterSeconds" /tmp/conc-g1.txt /tmp/conc-g2.txt 2>/dev/null | wc -l)
if [ "$REJECTED" -eq 1 ]; then
    pass "全局满载时恰好拒绝 1 个请求"
else
    fail "被拒绝的请求数为 $REJECTED，期望 1"
fi
if wait_idle 40; then
    assert_idle "全局满载拒绝后"
else
    fail "全局满载拒绝后 40 秒内许可未归零"
    status_json | jq -c '.data'
fi

# ---------------------------------------------------------------- 场景 6

section "场景 6：上游异常"
info "重启后端，把 DashScope API Key 覆盖为无效值，制造上游调用失败"
restart_backend "AI_DASHSCOPE_API_KEY=invalid-key-for-concurrency-verification"
login
sse_request /tmp/conc-err.txt 90
if grep -q "errorId" /tmp/conc-err.txt 2>/dev/null; then
    pass "请求以上游错误事件结束（收到 errorId），没有挂起"
else
    fail "未观察到错误事件，输出末尾：$(tail -c 200 /tmp/conc-err.txt 2>/dev/null)"
fi
if wait_idle 30; then
    assert_idle "上游异常后"
else
    fail "上游异常后 30 秒内许可未归零（疑似泄漏）"
    status_json | jq -c '.data'
fi
# ---------------------------------------------------------------- 场景 7

section "场景 7：服务重启 + 恢复默认配置"
restart_backend
login
assert_idle "重启后"
DEFAULT_MAX="$(status_json | jq -r '.data.globalMax')"
DEFAULT_PER_USER="$(status_json | jq -r '.data.perUserMax')"
if [ "$DEFAULT_MAX" = "4" ] && [ "$DEFAULT_PER_USER" = "1" ]; then
    pass "默认并发配置已恢复（globalMax=4, perUserMax=1）"
else
    fail "默认配置未恢复：globalMax=$DEFAULT_MAX, perUserMax=$DEFAULT_PER_USER"
fi

# ---------------------------------------------------------------- 汇总

section "汇总"
echo "  通过 $PASSED 项，失败 $FAILED 项"
echo
echo "  未覆盖项（不计入通过数，也不假装测过）："
echo "    - SseEmitter.onTimeout：STREAM_TIMEOUT_MILLIS 是 static final 180 秒，"
echo "      要触发必须让上游挂起 3 分钟，当前没有故障注入手段。"
echo "    - 线程池队列满（RejectedExecutionException）：队列容量 16，"
echo "      需要压出 16 个排队请求，成本高且会与上游超时纠缠。"
[ "$FAILED" -eq 0 ] || exit 1
