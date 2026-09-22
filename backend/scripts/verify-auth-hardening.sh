#!/usr/bin/env bash
#
# 认证加固的端到端验收脚本：登录失败限流 + token 吊销。
#
# 覆盖两组能力，断言的都是「可观察的外部行为」，不依赖内部状态：
#
#   限流   —— 按「用户名+IP」计数、单 IP 兜底、锁定优先于验密、
#             不存在的账号同样计数（无枚举信号）、成功登录清零、锁定自然过期
#   吊销   —— 登出后旧 token 立即失效、只吊销旧 token、失败/未完成的改密请求不吊销
#
# 用法：
#   NOTEPAD_USER=<账号> NOTEPAD_PASSWORD=<密码> bash backend/scripts/verify-auth-hardening.sh
#
# 注意：限流计数存在进程内存里，脚本靠「重启后端 + 环境变量覆盖」把 15 分钟的
# 窗口压到 20 秒。正常结束时场景 D 会恢复默认配置。**如果中途被打断，
# 后端可能停留在被覆盖的配置上，请手动重启一次。**

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WS="$BACKEND_DIR/../.idea/workspace.xml"
LOG_FILE="/tmp/notepad-backend.log"

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
    if [ "$#" -gt 0 ]; then
        OVERRIDES_ACTIVE="$*"
    else
        OVERRIDES_ACTIVE=""
    fi
    start_backend "$@" || exit 2
}

# ---------------------------------------------------------------- HTTP 工具

# 返回登录接口的 HTTP 状态码。$1=用户名 $2=密码
login_status() {
    curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

# 返回登录接口的响应体。$1=用户名 $2=密码
login_body() {
    curl -s -X POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

# 用正确凭据登录并把 token 放进 TOKEN / AUTH
login() {
    TOKEN="$(login_body "$NOTEPAD_USER" "$NOTEPAD_PASSWORD" | jq -r '.data.token // empty')"
    [ -n "$TOKEN" ] || { echo "登录失败，无法继续。" >&2; exit 2; }
    AUTH="Authorization: Bearer $TOKEN"
}

me_status() {
    curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/auth/me" -H "$1"
}

# 断言一组连续登录尝试的状态码。$1=描述 $2=期望状态码 $3...=密码
# 用户名固定用 NOTEPAD_USER（存在的账号）
expect_logins() {
    local label="$1" expected="$2"; shift 2
    local i=0 actual ok=1 detail=""
    for pwd in "$@"; do
        i=$((i + 1))
        actual="$(login_status "$NOTEPAD_USER" "$pwd")"
        detail="$detail $actual"
        [ "$actual" = "$expected" ] || ok=0
    done
    if [ "$ok" -eq 1 ]; then
        pass "$label（期望 $expected，实际$detail）"
    else
        fail "$label（期望 $expected，实际$detail）"
    fi
}

# ---------------------------------------------------------------- 场景 A

section "场景 A：基线（默认配置）"
# 先重启一次：既保证本脚本不依赖「外面正好有个后端在跑」，
# 也保证限流计数是干净的（计数在进程内存里，重启即清零）
restart_backend
login
info "已获取登录态"
if [ "$(me_status "$AUTH")" = "200" ]; then
    pass "带有效 token 访问 /api/auth/me 返回 200"
else
    fail "带有效 token 访问 /api/auth/me 未返回 200"
fi

info "越权检查：全局向量索引的运维接口默认关闭，用户级接口不受影响"
# 索引用的是默认配置，没设 NOTEPAD_INDEX_ADMIN_USERNAMES，应 fail-closed
ADMIN_EP="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/ai/notes/index/backups" -H "$AUTH")"
USER_EP="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/ai/notes/index/status" -H "$AUTH")"
if [ "$ADMIN_EP" = "403" ] && [ "$USER_EP" = "200" ]; then
    pass "普通用户调运维接口返回 403，索引状态等用户级接口仍返回 200"
else
    fail "越权检查不符预期：/index/backups=$ADMIN_EP（期望 403）、/index/status=$USER_EP（期望 200）"
fi

# ---------------------------------------------------------------- 场景 B

section "场景 B：登录失败限流（用户名+IP）"
info "重启后端，窗口压到 20s、单用户名上限压到 3 次、IP 兜底放宽到 50 次"
restart_backend "NOTEPAD_AUTH_LOGIN_ATTEMPT_WINDOW=20s" \
    "NOTEPAD_AUTH_LOGIN_ATTEMPT_MAX_PER_USER_IP=3" \
    "NOTEPAD_AUTH_LOGIN_ATTEMPT_MAX_PER_IP=50"

info "连续 3 次错误密码：前 3 次是普通业务失败，应返回 401 而不是 429"
expect_logins "前 3 次错误密码返回 401" "401" "wrong-a" "wrong-b" "wrong-c"

info "第 4 次改用【正确】密码：应被锁定拦住，返回 429（锁定优先于验密）"
BODY="$(login_body "$NOTEPAD_USER" "$NOTEPAD_PASSWORD")"
CODE="$(echo "$BODY" | jq -r '.code')"
MSG="$(echo "$BODY" | jq -r '.message')"
if [ "$CODE" = "429" ]; then
    pass "锁定期内即使密码正确也返回 429（code=429）"
else
    fail "锁定期内正确密码未被拦住，code=$CODE message=$MSG"
fi
if echo "$MSG" | grep -q "次数过多"; then
    pass "429 的提示语包含「次数过多」：$MSG"
else
    fail "429 提示语不符合预期：$MSG"
fi

info "换一个【不存在】的用户名连试 3 次，第 4 次也应 429（不暴露账号是否存在）"
GHOST="no_such_user_$$"
G1="$(login_status "$GHOST" x)"; G2="$(login_status "$GHOST" x)"; G3="$(login_status "$GHOST" x)"
G4="$(login_status "$GHOST" x)"
if [ "$G1$G2$G3" = "401401401" ] && [ "$G4" = "429" ]; then
    pass "不存在的用户名同样会被锁定（$G1 $G2 $G3 $G4），无账号枚举信号"
else
    fail "不存在的用户名未被锁定（$G1 $G2 $G3 $G4），期望 401 401 401 429"
fi

info "等待 25 秒（> 20s 窗口），锁定应自然过期"
sleep 25
if [ "$(login_status "$NOTEPAD_USER" "$NOTEPAD_PASSWORD")" = "200" ]; then
    pass "窗口过后正确密码可以登录（锁定自然过期）"
else
    fail "窗口过后仍无法登录，锁定未过期"
fi

info "验证成功登录会清零计数：先失败 2 次 → 成功登录 1 次 → 再失败 2 次应仍是 401"
F1="$(login_status "$NOTEPAD_USER" bad1)"; F2="$(login_status "$NOTEPAD_USER" bad2)"
S1="$(login_status "$NOTEPAD_USER" "$NOTEPAD_PASSWORD")"
F3="$(login_status "$NOTEPAD_USER" bad3)"; F4="$(login_status "$NOTEPAD_USER" bad4)"
if [ "$F1$F2" = "401401" ] && [ "$S1" = "200" ] && [ "$F3$F4" = "401401" ]; then
    pass "成功登录确实清零了失败计数（$F1 $F2 → 成功 $S1 → $F3 $F4）"
else
    fail "清零行为不符预期（失败 $F1 $F2 → 成功 $S1 → 失败 $F3 $F4），期望 401 401 200 401 401"
fi
info "紧接着再失败 1 次即达到阈值，随后应 429"
F5="$(login_status "$NOTEPAD_USER" bad5)"
F6="$(login_status "$NOTEPAD_USER" "$NOTEPAD_PASSWORD")"
if [ "$F5" = "401" ] && [ "$F6" = "429" ]; then
    pass "清零后重新累积到阈值再次锁定（$F5 → $F6）"
else
    fail "再次锁定行为不符预期（$F5 → $F6），期望 401 → 429"
fi

# ---------------------------------------------------------------- 场景 C

section "场景 C：单 IP 兜底维度"
info "重启后端，单用户名上限放宽到 100、IP 上限压到 4"
restart_backend "NOTEPAD_AUTH_LOGIN_ATTEMPT_WINDOW=20s" \
    "NOTEPAD_AUTH_LOGIN_ATTEMPT_MAX_PER_USER_IP=100" \
    "NOTEPAD_AUTH_LOGIN_ATTEMPT_MAX_PER_IP=4"

info "4 个互不相同的用户名各失败 1 次，都应返回 401"
C1="$(login_status "ip_a_$$" x)"; C2="$(login_status "ip_b_$$" x)"
C3="$(login_status "ip_c_$$" x)"; C4="$(login_status "ip_d_$$" x)"
if [ "$C1$C2$C3$C4" = "401401401401" ]; then
    pass "4 个不同用户名各失败 1 次均返回 401（$C1 $C2 $C3 $C4）"
else
    fail "预期 4 次 401，实际 $C1 $C2 $C3 $C4"
fi

info "第 5 次换一个从未失败过的用户名 + 【正确】密码，应被 IP 维度拦住返回 429"
BODY="$(login_body "$NOTEPAD_USER" "$NOTEPAD_PASSWORD")"
CODE="$(echo "$BODY" | jq -r '.code')"
if [ "$CODE" = "429" ]; then
    pass "IP 维度跨用户名生效：全新用户名 + 正确密码仍被拦（code=429）"
else
    fail "IP 维度未生效，code=$CODE（期望 429）"
fi

# ---------------------------------------------------------------- 场景 D

section "场景 D：并发突发不能突破阈值"
info "重启后端，阈值压到 3、窗口 60s、IP 维度放宽到 1000（避免 IP 维度干扰）"
restart_backend "NOTEPAD_AUTH_LOGIN_ATTEMPT_WINDOW=60s" \
    "NOTEPAD_AUTH_LOGIN_ATTEMPT_MAX_PER_USER_IP=3" \
    "NOTEPAD_AUTH_LOGIN_ATTEMPT_MAX_PER_IP=1000"

info "并发打 15 个错误密码请求，期望只有 3 个真正进入口令校验"
# 这条断言守的是「计数必须在验密之前原子完成」：如果拆成先 check、验密后再 record，
# 15 个线程会全部通过 check（那时计数还是 0），一次突发就能拿到 15 次猜测机会。
CONC_DIR="$(mktemp -d)"
for i in $(seq 1 15); do
    (
        curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE_URL/api/auth/login" \
            -H 'Content-Type: application/json' \
            -d "{\"username\":\"$NOTEPAD_USER\",\"password\":\"concurrent-wrong\"}" \
            > "$CONC_DIR/$i.txt"
    ) &
done
wait
N401="$(cat "$CONC_DIR"/*.txt | grep -c '^401$')"
N429="$(cat "$CONC_DIR"/*.txt | grep -c '^429$')"
rm -rf "$CONC_DIR"
if [ "$N401" -eq 3 ] && [ "$N429" -eq 12 ]; then
    pass "并发 15 个请求仍只有 3 个拿到口令校验机会（401×3、429×12）"
else
    fail "并发下限额被突破：401×$N401、429×$N429（期望 401×3、429×12）"
fi

# ---------------------------------------------------------------- 场景 E

section "场景 E：token 吊销（默认配置）"
restart_backend

TOKEN_A="$(login_body "$NOTEPAD_USER" "$NOTEPAD_PASSWORD" | jq -r '.data.token // empty')"
if [ -n "$TOKEN_A" ] && [ "$(me_status "Authorization: Bearer $TOKEN_A")" = "200" ]; then
    pass "登录拿到 tokenA，可正常访问 /api/auth/me"
else
    fail "登录或 /api/auth/me 失败，后续吊销断言无效"
fi

info "调用登出，并检查响应头里的 Set-Cookie"
LOGOUT_HEADERS="$(curl -s -D - -o /dev/null -X POST "$BASE_URL/api/auth/logout" \
    -H "Authorization: Bearer $TOKEN_A")"
LOGOUT_STATUS="$(echo "$LOGOUT_HEADERS" | head -1 | awk '{print $2}')"
COOKIE_COUNT="$(echo "$LOGOUT_HEADERS" | grep -ci '^set-cookie')"
if [ "$LOGOUT_STATUS" = "200" ]; then
    pass "登出返回 200"
else
    fail "登出返回 $LOGOUT_STATUS，期望 200"
fi

# preHandle 会先 addHeader 刷新一次 notepad_access，controller 必须用 setHeader
# 覆盖它，否则响应里会出现两个同名 Set-Cookie，靠浏览器「取最后一个」兜着
if [ "$COOKIE_COUNT" = "1" ]; then
    pass "响应头里只有 1 个 Set-Cookie（preHandle 的刷新 cookie 已被 setHeader 覆盖）"
else
    fail "响应头里有 $COOKIE_COUNT 个 Set-Cookie，期望 1"
    echo "$LOGOUT_HEADERS" | grep -i '^set-cookie' | sed 's/^/      /'
fi

AFTER="$(me_status "Authorization: Bearer $TOKEN_A")"
if [ "$AFTER" = "401" ]; then
    pass "登出后旧 tokenA 立即失效（/api/auth/me 返回 401）"
else
    fail "登出后旧 tokenA 仍可用（返回 $AFTER），吊销未生效"
fi

info "重新登录取 tokenB，应不受影响"
TOKEN_B="$(login_body "$NOTEPAD_USER" "$NOTEPAD_PASSWORD" | jq -r '.data.token // empty')"
if [ -n "$TOKEN_B" ] && [ "$(me_status "Authorization: Bearer $TOKEN_B")" = "200" ]; then
    pass "重新登录的 tokenB 正常可用（只吊销了旧 token）"
else
    fail "重新登录后的 tokenB 不可用，吊销范围过宽"
fi

info "负向断言：用 tokenB 发起一次【验证码错误】的改密，应 400 且绝不吊销 tokenB"
CH_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/password/change" \
    -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
    -d '{"code":"000000","newPassword":"WhateverPass123"}')"
STILL="$(me_status "Authorization: Bearer $TOKEN_B")"
if [ "$CH_STATUS" = "400" ] && [ "$STILL" = "200" ]; then
    pass "改密失败（$CH_STATUS）后 tokenB 仍有效（$STILL），自增只发生在验证码校验通过之后"
else
    fail "改密失败后 tokenB 状态异常：改密=$CH_STATUS，随后 /me=$STILL（期望 400 与 200）"
fi

info "无效 token 调登出应 401"
BAD="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/logout" \
    -H "Authorization: Bearer not-a-real-token")"
if [ "$BAD" = "401" ]; then
    pass "无效 token 调登出返回 401"
else
    fail "无效 token 调登出返回 $BAD，期望 401"
fi

# ---------------------------------------------------------------- 汇总

section "汇总"
echo "  通过 $PASSED 项，失败 $FAILED 项"
echo
echo "  未覆盖项（不计入通过数，也不假装测过）："
echo "    - 改密/重置密码成功后的端到端吊销：需要邮箱验证码，脚本没有收信手段。"
echo "      手工步骤：登录 → POST /api/auth/password/change-code 取验证码 →"
echo "      POST /api/auth/password/change → 用【改密前】的 token 请求 /api/auth/me，"
echo "      预期 401 且文案为「登录状态已失效，请重新登录」。"
echo "    - 升级前签发的存量 token 兼容性：需要一个不带 tv claim 的 token，"
echo "      当前没有升级前的 token 可用。设计上按版本号 0 处理，与库里的默认值一致。"
echo "    - 多实例/重启后限流计数丢失：内存方案的设计取舍，不是缺陷。"
[ "$FAILED" -eq 0 ] || exit 1
