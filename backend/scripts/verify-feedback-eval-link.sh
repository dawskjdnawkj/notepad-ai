#!/usr/bin/env bash
#
# 「没帮助反馈 ↔ RAG 回归测试报告」双向可追溯的端到端验收脚本。
#
# 验证两个方向：
#   正向：回归报告里由反馈转换来的用例，能追溯到原始反馈（原因 / 补充说明 / 时间）
#   反向：反馈统计里每个高频问题，能看到它的用例在最近一次回归报告里的状态
#         （passed / failed / not_run，失败时带原因）
#
# 脚本会临时创建一条会话 + 一条没帮助反馈 + 一个回归用例，退出时全部清理，
# 并复跑一次回归确认 14 个固定用例的基线复原。**即使中途失败也会清理**（见 trap）。
#
# 用法：
#   NOTEPAD_USER=<账号> NOTEPAD_PASSWORD=<密码> bash backend/scripts/verify-feedback-eval-link.sh

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"

PASSED=0
FAILED=0
TOKEN=""
CREATED_CASE_ID=""
CREATED_CONVERSATION_ID=""

pass() { PASSED=$((PASSED + 1)); echo "  [PASS] $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  [FAIL] $1"; }
info() { echo "  ---- $1"; }
section() { echo; echo "=== $1 ==="; }

# 清理必须在任何退出路径上执行：这个脚本会往用户的回归基线里临时加一个用例，
# 不清理就会把 14 个用例的基线改成 15 个。
cleanup() {
    if [ -n "$TOKEN" ]; then
        if [ -n "$CREATED_CASE_ID" ]; then
            curl -s -X DELETE "$BASE_URL/api/ai/evaluations/cases/$CREATED_CASE_ID" \
                -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
            echo "  ---- 已删除临时用例 $CREATED_CASE_ID"
        fi
        if [ -n "$CREATED_CONVERSATION_ID" ]; then
            # 会话删除会级联清掉消息与反馈
            curl -s -X DELETE "$BASE_URL/api/ai/conversations/$CREATED_CONVERSATION_ID" \
                -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
            echo "  ---- 已删除临时会话 $CREATED_CONVERSATION_ID（含消息与反馈）"
        fi
    fi
}
trap cleanup EXIT

if [ -z "${NOTEPAD_USER:-}" ] || [ -z "${NOTEPAD_PASSWORD:-}" ]; then
    echo "缺少凭据。请设置 NOTEPAD_USER 和 NOTEPAD_PASSWORD 后重试。" >&2
    exit 2
fi
for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖：$tool" >&2; exit 2; }
done

# 请求体一律只用 ASCII：Windows 上 shell 可能按 GBK 传递中文，
# 服务端会以 Invalid UTF-8 直接拒绝。
STAMP=$(date +%s)
QUESTION="zzz-linkage-probe-$STAMP"
MARKER="trace-marker-$STAMP"
CONVERSATION_ID="linkage-check-$STAMP"
ASSISTANT_MESSAGE_ID="msg-a-$STAMP"

auth() { curl -s "$@" -H "Authorization: Bearer $TOKEN"; }
stats_for_question() {
    auth "$BASE_URL/api/ai/feedback/statistics" \
        | jq -c --arg q "$QUESTION" '.data.commonFailureQuestions[] | select(.question==$q)'
}

section "登录"
TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$NOTEPAD_USER\",\"password\":\"$NOTEPAD_PASSWORD\"}" \
    | jq -r '.data.token // empty')"
if [ -z "$TOKEN" ]; then
    echo "登录失败，无法继续。" >&2
    exit 2
fi
pass "已获取登录态"

NOTE_ID="$(auth "$BASE_URL/api/notes?page=1&pageSize=1" | jq -r '.data.records[0].id // empty')"
if [ -z "$NOTE_ID" ]; then
    echo "该账号下没有笔记，无法构造回归用例。" >&2
    exit 2
fi
info "借用笔记 ID=$NOTE_ID 作为预期笔记"

# ---------------------------------------------------------------- 造一条没帮助反馈

section "步骤 1：制造一条「没帮助」反馈"

# question 快照取自助手消息，所以必须挂在助手消息上，否则统计里会显示为「未记录问题」
CONVERSATION_BODY="$(jq -n \
    --arg q "$QUESTION" \
    --arg mid "$ASSISTANT_MESSAGE_ID" \
    '{title:"linkage check", messages:[
        {role:"user", content:"original question", clientMessageId:"msg-u"},
        {role:"assistant", content:"original unhelpful answer", question:$q, clientMessageId:$mid}
    ]}')"
CODE="$(auth -X PUT "$BASE_URL/api/ai/conversations/$CONVERSATION_ID" \
    -H 'Content-Type: application/json' -d "$CONVERSATION_BODY" | jq -r '.code')"
if [ "$CODE" = "200" ]; then
    CREATED_CONVERSATION_ID="$CONVERSATION_ID"
    pass "临时会话已创建"
else
    fail "创建会话失败（code=$CODE）"
    exit 1
fi

CODE="$(auth -X PUT \
    "$BASE_URL/api/ai/conversations/$CONVERSATION_ID/messages/$ASSISTANT_MESSAGE_ID/feedback" \
    -H 'Content-Type: application/json' \
    -d "{\"rating\":\"unhelpful\",\"reason\":\"incomplete\",\"comment\":\"$MARKER\"}" \
    | jq -r '.code')"
if [ "$CODE" = "200" ]; then
    pass "已标记为没帮助（唯一标记 $MARKER）"
else
    fail "标记没帮助失败（code=$CODE）"
    exit 1
fi

BEFORE="$(stats_for_question)"
if [ "$(echo "$BEFORE" | jq -r '.convertedCaseCount')" = "0" ] \
    && [ "$(echo "$BEFORE" | jq -r '.evalStatus')" = "not_run" ]; then
    pass "尚未转换时：convertedCaseCount=0、evalStatus=not_run"
else
    fail "尚未转换时的统计不符合预期：$BEFORE"
fi

# ---------------------------------------------------------------- 转换为回归用例

section "步骤 2：转换为回归用例"
info "故意构造成「应命中但检索不到」的失败用例，以便同时验证 failed 状态"

CASE_BODY="$(jq -n --arg q "$QUESTION" --argjson nid "$NOTE_ID" \
    '{name:"linkage check case", question:$q, scopeType:"all", scopeId:null,
      expectAnswer:true, expectedNoteIds:[$nid], matchMode:"any", minScore:0.45, enabled:true}')"
CASE="$(auth -X POST "$BASE_URL/api/ai/evaluations/cases/from-feedback/$CONVERSATION_ID/$ASSISTANT_MESSAGE_ID" \
    -H 'Content-Type: application/json' -d "$CASE_BODY")"
CREATED_CASE_ID="$(echo "$CASE" | jq -r '.data.id // empty')"
if [ -n "$CREATED_CASE_ID" ]; then
    pass "已由反馈转换为用例 caseId=$CREATED_CASE_ID"
else
    fail "转换失败：$(echo "$CASE" | jq -c '.message')"
    exit 1
fi

AFTER_CONVERT="$(stats_for_question)"
if [ "$(echo "$AFTER_CONVERT" | jq -r '.convertedCaseCount')" = "1" ] \
    && [ "$(echo "$AFTER_CONVERT" | jq -r '.evalStatus')" = "not_run" ]; then
    pass "转换后、尚未跑回归：convertedCaseCount=1、evalStatus=not_run"
else
    fail "转换后的统计不符合预期：$AFTER_CONVERT"
fi

# ---------------------------------------------------------------- 正向追溯

section "步骤 3：跑回归并验证「报告 → 反馈」正向追溯"
RUN="$(auth -X POST "$BASE_URL/api/ai/evaluations/runs")"
RUN_CASES="$(echo "$RUN" | jq -r '.data.caseCount')"
info "本次回归用例数=$RUN_CASES（基线 14 + 临时 1）"

RESULT="$(echo "$RUN" | jq -c --argjson cid "$CREATED_CASE_ID" \
    '.data.results[] | select(.caseId==$cid)')"
if [ -n "$RESULT" ]; then
    pass "报告里找到了临时用例"
else
    fail "报告里没有找到 caseId=$CREATED_CASE_ID"
fi

SOURCE_COMMENT="$(echo "$RESULT" | jq -r '.sourceFeedback.comment // empty')"
SOURCE_REASON="$(echo "$RESULT" | jq -r '.sourceFeedback.reason // empty')"
SOURCE_ID="$(echo "$RESULT" | jq -r '.sourceFeedback.feedbackId // empty')"
if [ "$SOURCE_COMMENT" = "$MARKER" ]; then
    pass "sourceFeedback 指向的正是这条反馈（comment 命中唯一标记）"
else
    fail "sourceFeedback 未指向预期反馈：comment=$SOURCE_COMMENT，期望 $MARKER"
fi
if [ "$SOURCE_REASON" = "incomplete" ]; then
    pass "sourceFeedback 携带原始反馈原因（incomplete）"
else
    fail "sourceFeedback 原因不符：$SOURCE_REASON"
fi
if [ -n "$SOURCE_ID" ]; then
    pass "sourceFeedback 携带反馈 ID（$SOURCE_ID）"
else
    fail "sourceFeedback 缺少反馈 ID"
fi

# 反向断言：不能给手工创建的用例凭空加上来源
STRAY="$(echo "$RUN" | jq -r --arg m "$MARKER" \
    '[.data.results[] | select(.sourceFeedback != null and .sourceFeedback.comment == $m)] | length')"
if [ "$STRAY" = "1" ]; then
    pass "唯一标记只出现在这一条用例上，没有串到别的用例"
else
    fail "唯一标记出现在 $STRAY 条用例上，期望 1"
fi

# ---------------------------------------------------------------- 反向追溯

section "步骤 4：验证「反馈 → 回归状态」反向追溯"
AFTER_RUN="$(stats_for_question)"
STATUS="$(echo "$AFTER_RUN" | jq -r '.evalStatus')"
if [ "$STATUS" = "failed" ]; then
    pass "反馈统计显示 evalStatus=failed"
else
    fail "反馈统计的 evalStatus=$STATUS，期望 failed"
fi

REPORT_REASON="$(echo "$RESULT" | jq -r '.failureReason // empty')"
STATS_REASON="$(echo "$AFTER_RUN" | jq -r '.evalFailureReason // empty')"
if [ -n "$STATS_REASON" ] && [ "$STATS_REASON" = "$REPORT_REASON" ]; then
    pass "失败原因与回归报告一致（$STATS_REASON）"
else
    fail "失败原因不一致：统计「$STATS_REASON」 vs 报告「$REPORT_REASON」"
fi

if [ -n "$(echo "$AFTER_RUN" | jq -r '.lastEvalAt // empty')" ]; then
    pass "反馈统计携带最近回归时间"
else
    fail "反馈统计缺少最近回归时间"
fi

# 真实数据里应当存在「已通过」的反馈用例，用它验证第三种状态
PASSED_COUNT="$(auth "$BASE_URL/api/ai/feedback/statistics" \
    | jq -r '[.data.commonFailureQuestions[] | select(.evalStatus=="passed")] | length')"
info "当前统计中 evalStatus=passed 的高频问题数：$PASSED_COUNT"
if [ "$PASSED_COUNT" -ge 1 ]; then
    pass "passed 状态在真实数据上可见"
else
    info "本次没有已通过的反馈用例，passed 状态未覆盖（不算失败）"
fi

# ---------------------------------------------------------------- 清理与基线复原

section "步骤 5：清理并按基线复测"
cleanup
CREATED_CASE_ID=""
CREATED_CONVERSATION_ID=""

CASE_TOTAL="$(auth "$BASE_URL/api/ai/evaluations/cases" | jq -r '.data | length')"
if [ "$CASE_TOTAL" = "14" ]; then
    pass "用例总数已回到 14"
else
    fail "用例总数为 $CASE_TOTAL，期望 14（临时用例没清干净）"
fi

RESIDUAL="$(auth "$BASE_URL/api/ai/feedback/statistics" \
    | jq -r --arg q "$QUESTION" '[.data.commonFailureQuestions[] | select(.question==$q)] | length')"
if [ "$RESIDUAL" = "0" ]; then
    pass "临时反馈已随会话级联删除"
else
    fail "仍有 $RESIDUAL 条临时反馈残留"
fi

FINAL="$(auth -X POST "$BASE_URL/api/ai/evaluations/runs")"
info "基线复测：$(echo "$FINAL" | jq -c '{caseCount:.data.caseCount, passRate:.data.passRate, hitRate:.data.hitRate, rejectionAccuracy:.data.rejectionAccuracy, wrongReferenceCount:.data.wrongReferenceCount}')"
if [ "$(echo "$FINAL" | jq -r '.data.passRate')" = "1.0" ] \
    && [ "$(echo "$FINAL" | jq -r '.data.rejectionAccuracy')" = "1.0" ] \
    && [ "$(echo "$FINAL" | jq -r '.data.wrongReferenceCount')" = "0" ] \
    && [ "$(echo "$FINAL" | jq -r '.data.caseCount')" = "14" ]; then
    pass "14 个固定用例基线完全复原"
else
    fail "基线未复原，请人工检查"
fi

# ---------------------------------------------------------------- 汇总

section "汇总"
echo "  通过 $PASSED 项，失败 $FAILED 项"
[ "$FAILED" -eq 0 ] || exit 1
