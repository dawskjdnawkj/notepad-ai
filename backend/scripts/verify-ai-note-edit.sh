#!/usr/bin/env bash
#
# AI 编辑笔记（摘要 / 改写 / 续写 / 提取待办）的端到端验收脚本。
#
# 覆盖交接文档第四阶段的硬性要求：读取笔记 → 生成预览 → 用户确认 → 写回 → 重新同步向量索引，
# 以及「模型不得直接覆盖笔记」「写回失败不能丢失原文」。
#
# 脚本会临时建一篇笔记和一个标签，退出时全部清理（含快照行）。
#
# 用法：
#   NOTEPAD_USER=<账号> NOTEPAD_PASSWORD=<密码> bash backend/scripts/verify-ai-note-edit.sh

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP_DIR="${TMPDIR:-/tmp}"
WORK_DIR="$(mktemp -d)"

PASSED=0
FAILED=0
TOKEN=""
NOTE_ID=""
TAG_ID=""
STAMP="$(date +%s)"

pass() { PASSED=$((PASSED + 1)); echo "  [PASS] $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  [FAIL] $1"; }
info() { echo "  ---- $1"; }
section() { echo; echo "=== $1 ==="; }

cleanup() {
    if [ -n "$TOKEN" ] && [ -n "$NOTE_ID" ]; then
        curl -s -X DELETE "$BASE_URL/api/notes/$NOTE_ID" -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
        curl -s -X DELETE "$BASE_URL/api/trash/notes/$NOTE_ID" -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
    fi
    if [ -n "$TOKEN" ] && [ -n "$TAG_ID" ]; then
        curl -s -X DELETE "$BASE_URL/api/tags/$TAG_ID" -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
    fi
    if [ -n "$MYSQL_BIN" ] && [ -f "$MYSQL_BIN" ] && [ -n "$NOTE_ID" ]; then
        "$MYSQL_BIN" -h 127.0.0.1 -P 3306 -u"$DB_USER" -p"$DB_PASS" cloud_notepad \
            -e "DELETE FROM ai_note_revision WHERE note_id=$NOTE_ID;" > /dev/null 2>&1
    fi
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

if [ -z "${NOTEPAD_USER:-}" ] || [ -z "${NOTEPAD_PASSWORD:-}" ]; then
    echo "缺少凭据。请设置 NOTEPAD_USER 和 NOTEPAD_PASSWORD。" >&2
    exit 2
fi
for tool in curl jq python; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖：$tool" >&2; exit 2; }
done

# 清理快照行需要直连数据库；取不到就退化为不清理（脚本仍会提示）
WS="$BACKEND_DIR/../.idea/workspace.xml"
get_env() { sed -n "s/.*<env name=\"$1\" value=\"\([^\"]*\)\".*/\1/p" "$WS" 2>/dev/null | head -1; }
DB_USER="$(get_env DB_USERNAME)"
DB_PASS="$(get_env DB_PASSWORD)"
MYSQL_BIN="D:/JAVA-Study/mysql-8.0.46-winx64/bin/mysql.exe"

# ---------------------------------------------------------------- SSE 解析

# 从 SSE 输出里重建 AI 文本并打印事件统计
parse_sse() { # $1=SSE 文件 $2=输出文本文件
    python - "$1" "$2" <<'PYEOF'
import json, re, sys
raw = open(sys.argv[1], encoding='utf-8', errors='replace').read()
parts, done_obj, error_obj = [], None, None
for line in raw.split('\n'):
    line = line.rstrip('\r')
    if not line.startswith('data:'):
        continue
    try:
        obj = json.loads(line[5:].lstrip())
    except (json.JSONDecodeError, ValueError):
        continue
    if 'text' in obj:
        parts.append(obj['text'])
    if 'contentHash' in obj:
        done_obj = obj
    if 'message' in obj and 'errorId' in obj:
        error_obj = obj
text = ''.join(parts)
open(sys.argv[2], 'w', encoding='utf-8').write(text)
print('delta_chars=%d' % len(text))
print('has_done=%s' % ('1' if done_obj else '0'))
print('content_hash=%s' % ((done_obj or {}).get('contentHash', '')))
print('capability=%s' % ((done_obj or {}).get('capability', '')))
print('error=%s' % ((error_obj or {}).get('message', '')))
PYEOF
}

stream_preview() { # $1=capability $2=输出文件前缀 → 打印解析结果
    local cap="$1" prefix="$2"
    curl -s -N --max-time 150 -X POST "$BASE_URL/api/ai/notes/$NOTE_ID/ai-edit/preview/stream" \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d "{\"capability\":\"$cap\"}" > "$prefix.sse" 2>&1
    parse_sse "$prefix.sse" "$prefix.txt" > "$prefix.meta"
    cat "$prefix.meta"
}

build_apply_body() { # $1=meta 文件 $2=文本文件 $3=输出 json
    python - "$1" "$2" "$3" <<'PYEOF'
import json, sys
meta = {}
for line in open(sys.argv[1], encoding='utf-8'):
    if '=' in line:
        k, v = line.strip().split('=', 1)
        meta[k] = v
text = open(sys.argv[2], encoding='utf-8').read()
json.dump({'capability': meta.get('capability'), 'text': text,
           'contentHash': meta.get('content_hash')},
          open(sys.argv[3], 'w', encoding='utf-8'), ensure_ascii=False)
PYEOF
}

auth() { curl -s "$@" -H "Authorization: Bearer $TOKEN"; }

# ---------------------------------------------------------------- 准备

section "准备：登录并建一篇带标签的临时笔记"
TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$NOTEPAD_USER\",\"password\":\"$NOTEPAD_PASSWORD\"}" \
    | jq -r '.data.token // empty')"
[ -n "$TOKEN" ] || { echo "登录失败。" >&2; exit 2; }
pass "已获取登录态"

# 中文内容通过 Python 生成 UTF-8 请求体：Windows 上 shell 直接传中文会变成 GBK，
# 服务端会以 Invalid UTF-8 拒绝。
python - "$WORK_DIR" <<'PYEOF'
import json, sys, os
content = ('<p>今天学习了 Java Stream API 的中间操作和终端操作。</p>'
           '<p>中间操作返回新的 Stream，可以链式调用，比如 filter、map、sorted。</p>'
           '<p>终端操作会关闭流并触发计算，比如 collect、forEach、count。</p>'
           '<p>还记了：Stream 只能消费一次，不能重复使用。</p>')
work = sys.argv[1]
json.dump({'title': 'AI 编辑验收笔记', 'content': content, 'notebookId': 11},
          open(os.path.join(work, 'create.json'), 'w', encoding='utf-8'), ensure_ascii=False)
open(os.path.join(work, 'original.html'), 'w', encoding='utf-8').write(content)
PYEOF

NOTE_ID="$(auth -X POST "$BASE_URL/api/notes" -H 'Content-Type: application/json' \
    --data-binary @"$WORK_DIR/create.json" | jq -r '.data.id // empty')"
[ -n "$NOTE_ID" ] || { echo "创建临时笔记失败。" >&2; exit 2; }
info "临时笔记 ID=$NOTE_ID"

# 标签名带上时间戳：标签是软删除，重名唯一约束对已删除的行仍然生效，
# 用固定名字会让脚本第二次运行就失败。
TAG_ID="$(auth -X POST "$BASE_URL/api/tags" -H 'Content-Type: application/json' \
    -d "{\"name\":\"ai-edit-verify-$STAMP\"}" | jq -r '.data.id // .data // empty')"
python - "$WORK_DIR" "$TAG_ID" <<'PYEOF'
import json, sys, os
path = os.path.join(sys.argv[1], 'create.json')
body = json.load(open(path, encoding='utf-8'))
body['tagIds'] = [int(sys.argv[2])]
json.dump(body, open(os.path.join(sys.argv[1], 'tagged.json'), 'w', encoding='utf-8'), ensure_ascii=False)
PYEOF
auth -X PUT "$BASE_URL/api/notes/$NOTE_ID" -H 'Content-Type: application/json' \
    --data-binary @"$WORK_DIR/tagged.json" > /dev/null
TAGS_BEFORE="$(auth "$BASE_URL/api/notes/$NOTE_ID" | jq -c '[.data.tags[].id] | sort')"
pass "已给笔记打上标签 $TAGS_BEFORE"

# ---------------------------------------------------------------- 四种能力

section "步骤 1：四种能力都能生成非空预览"
for cap in summarize rewrite continue todos; do
    RESULT="$(stream_preview "$cap" "$WORK_DIR/$cap")"
    CHARS="$(echo "$RESULT" | grep '^delta_chars=' | cut -d= -f2)"
    HAS_DONE="$(echo "$RESULT" | grep '^has_done=' | cut -d= -f2)"
    if [ "${CHARS:-0}" -gt 0 ] && [ "$HAS_DONE" = "1" ]; then
        pass "$cap 预览生成成功（$CHARS 字，收到 done）"
    else
        fail "$cap 预览失败：$(echo "$RESULT" | tr '\n' ' ')"
    fi
done

# ---------------------------------------------------------------- 写回

section "步骤 2：摘要写回（替换语义之外的能力，验证插入与标签保留）"
build_apply_body "$WORK_DIR/summarize.meta" "$WORK_DIR/summarize.txt" "$WORK_DIR/apply-summarize.json"
APPLY="$(auth -X POST "$BASE_URL/api/ai/notes/$NOTE_ID/ai-edit/apply" \
    -H 'Content-Type: application/json' --data-binary @"$WORK_DIR/apply-summarize.json")"
REVISION_ID="$(echo "$APPLY" | jq -r '.data.revisionId // empty')"
CONTENT_AFTER="$(echo "$APPLY" | jq -r '.data.note.content // ""')"
TAGS_AFTER="$(echo "$APPLY" | jq -c '[.data.note.tags[].id] | sort')"

if [ -n "$REVISION_ID" ]; then
    pass "写回成功，快照 ID=$REVISION_ID"
else
    fail "写回失败：$(echo "$APPLY" | jq -c '.message')"
fi

if [ "$CONTENT_AFTER" != "$(cat "$WORK_DIR/original.html")" ]; then
    pass "正文确实被改写（长度 $(cat "$WORK_DIR/original.html" | wc -c) → $(echo -n "$CONTENT_AFTER" | wc -c)）"
else
    fail "正文没有变化"
fi

# 这一条是最容易踩的坑：NoteService.update 会先删光标签再按传入值重建
if [ "$TAGS_BEFORE" = "$TAGS_AFTER" ]; then
    pass "标签没有被清空（$TAGS_AFTER）"
else
    fail "标签被改动：写回前 $TAGS_BEFORE，写回后 $TAGS_AFTER"
fi

# ---------------------------------------------------------------- 向量同步

section "步骤 3：写回后向量索引重新同步"
info "等待防抖同步（5 秒静默期 + 余量）"
sleep 12
STATUS="$(auth "$BASE_URL/api/ai/notes/index/status")"
if [ "$(echo "$STATUS" | jq -r '.data.state')" = "HEALTHY" ] \
    && [ "$(echo "$STATUS" | jq -r '.data.missingNoteCount')" = "0" ]; then
    pass "索引状态 HEALTHY 且无缺失笔记"
else
    fail "索引状态异常：$(echo "$STATUS" | jq -c '.data')"
fi

# contentText 会随写回一起更新，索引也该跟着变。
#
# 这里不拿「检索能否命中」当判据：默认 topK=5，语料里同主题的笔记一多，
# 正确的片段也未必挤得进前五 —— 那验的是排序，不是同步。
# 直接读向量文件确认该笔记的片段里已经含有写回后的文字，才是权威证据。
#
# 中文只经文件传递，不经 argv 或 stdout：Windows 上 python 用 GBK 解码 argv，
# 会把 UTF-8 字节搞坏。
STORE_TYPE="$(auth "$BASE_URL/api/ai/notes/index/status" | jq -r '.data.storeType')"
if [ "$STORE_TYPE" = "simple" ]; then
    if python - "$BACKEND_DIR/data/simple-vector-store.json" "$NOTE_ID" "$WORK_DIR/summarize.txt" <<'PYEOF'
import json, sys
store_path, note_id, ai_text = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(store_path, encoding='utf-8'))
chunks = [v.get('text', '') for v in data.values()
          if v.get('metadata', {}).get('noteId') == note_id]
probe = open(ai_text, encoding='utf-8').read().strip()[:12]
hit = any(probe in chunk for chunk in chunks)
print('note_chunks=%d probe_hit=%s' % (len(chunks), hit))
sys.exit(0 if (chunks and hit) else 1)
PYEOF
    then
        pass "向量文件里该笔记的片段已含写回后的新内容（索引真的同步了）"
    else
        fail "向量文件里该笔记的片段没有更新，索引可能未同步"
    fi
else
    info "当前存储是 $STORE_TYPE，跳过向量文件断言"
fi

# 另外确认检索链路本身仍然可用（不断言一定命中本笔记）
SEARCH_HITS="$(auth -X POST "$BASE_URL/api/ai/notes/search" -H 'Content-Type: application/json' \
    -d '{"question":"Stream API"}' | jq -r '.data | length')"
if [ "${SEARCH_HITS:-0}" -ge 1 ]; then
    pass "写回后检索功能仍然可用（返回 $SEARCH_HITS 条）"
else
    fail "写回后检索异常"
fi

# ---------------------------------------------------------------- 回退

section "步骤 4：回退原文"
RESTORED="$(auth -X POST "$BASE_URL/api/ai/notes/$NOTE_ID/ai-edit/revisions/$REVISION_ID/restore")"
RESTORED_CONTENT="$(echo "$RESTORED" | jq -r '.data.content // ""')"
if [ "$RESTORED_CONTENT" = "$(cat "$WORK_DIR/original.html")" ]; then
    pass "回退后正文与写回前逐字相同"
else
    fail "回退后正文与写回前不一致"
fi
RESTORED_TAGS="$(echo "$RESTORED" | jq -c '[.data.tags[].id] | sort')"
if [ "$TAGS_BEFORE" = "$RESTORED_TAGS" ]; then
    pass "回退后标签仍然完好"
else
    fail "回退后标签被改动：$RESTORED_TAGS"
fi

# ---------------------------------------------------------------- 竞态

section "步骤 5：预览期间用户改了笔记"
build_apply_body "$WORK_DIR/rewrite.meta" "$WORK_DIR/rewrite.txt" "$WORK_DIR/apply-stale.json"

python - "$WORK_DIR" <<'PYEOF'
import json, sys, os
path = os.path.join(sys.argv[1], 'create.json')
body = json.load(open(path, encoding='utf-8'))
body['content'] = body['content'] + '<p>用户在预览期间手动补充的一句。</p>'
json.dump(body, open(os.path.join(sys.argv[1], 'raced.json'), 'w', encoding='utf-8'), ensure_ascii=False)
PYEOF
auth -X PUT "$BASE_URL/api/notes/$NOTE_ID" -H 'Content-Type: application/json' \
    --data-binary @"$WORK_DIR/raced.json" > /dev/null

STALE_CODE="$(auth -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/ai/notes/$NOTE_ID/ai-edit/apply" \
    -H 'Content-Type: application/json' --data-binary @"$WORK_DIR/apply-stale.json")"
if [ "$STALE_CODE" = "409" ]; then
    pass "用过期指纹写回被拒绝（HTTP 409）"
else
    fail "过期指纹写回返回 $STALE_CODE，期望 409"
fi

RACE_CONTENT="$(auth "$BASE_URL/api/notes/$NOTE_ID" | jq -r '.data.content')"
case "$RACE_CONTENT" in
    *"用户在预览期间手动补充的一句"*)
        pass "用户的手动修改没有被覆盖" ;;
    *)
        fail "用户的手动修改被覆盖了" ;;
esac

# ---------------------------------------------------------------- XSS

section "步骤 6：AI 输出里的 HTML 必须被转义"
XSS_PREVIEW="$(stream_preview rewrite "$WORK_DIR/xss")"
python - "$WORK_DIR" <<'PYEOF'
import json, sys, os
path = os.path.join(sys.argv[1], 'xss.meta')
meta = {}
for line in open(path, encoding='utf-8'):
    if '=' in line:
        k, v = line.strip().split('=', 1)
        meta[k] = v
json.dump({'capability': meta.get('capability'), 'contentHash': meta.get('content_hash'),
           'text': 'normal line\n<script>alert(1)</script>\n<img src=x onerror=alert(2)>'},
          open(os.path.join(sys.argv[1], 'apply-xss.json'), 'w', encoding='utf-8'), ensure_ascii=False)
PYEOF
auth -X POST "$BASE_URL/api/ai/notes/$NOTE_ID/ai-edit/apply" \
    -H 'Content-Type: application/json' --data-binary @"$WORK_DIR/apply-xss.json" > /dev/null

auth "$BASE_URL/api/notes/$NOTE_ID" | jq -r '.data.content' > "$WORK_DIR/after-xss.html"
# 同样只传 ASCII 路径：正文是中文，经 argv 会被 Windows 上用 GBK 解码而损坏。
python - "$WORK_DIR/after-xss.html" <<'PYEOF'
import re, sys
content = open(sys.argv[1], encoding='utf-8').read()
tags = re.findall(r'<[^>]*>', content)
bad = [t for t in tags if t not in ('<p>', '</p>')]
if bad:
    print('FAIL: 正文里出现了非预期标签 %s' % bad[:3])
    sys.exit(1)
if '&lt;script&gt;' not in content:
    print('FAIL: script 未被转义')
    sys.exit(1)
print('PASS: 真实标签只有 <p>，危险尖括号已全部转义')
PYEOF
if [ $? -eq 0 ]; then
    pass "AI 输出里的 HTML 已被转义，无可执行标签"
else
    fail "XSS 防护未生效"
fi

# ---------------------------------------------------------------- 归属

section "步骤 7：归属校验"
NOT_FOUND="$(auth -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/ai/notes/99999999/ai-edit/apply" \
    -H 'Content-Type: application/json' \
    -d '{"capability":"summarize","text":"x","contentHash":"y"}')"
if [ "$NOT_FOUND" = "404" ]; then
    pass "对不存在的笔记写回返回 404（不泄露存在性）"
else
    fail "对不存在的笔记写回返回 $NOT_FOUND，期望 404"
fi
REV_NOT_FOUND="$(auth -o /dev/null -w '%{http_code}' "$BASE_URL/api/ai/notes/$NOTE_ID/ai-edit/revisions")"
if [ "$REV_NOT_FOUND" = "200" ]; then
    pass "自己笔记的快照列表可读"
else
    fail "自己笔记的快照列表返回 $REV_NOT_FOUND"
fi

# ---------------------------------------------------------------- 并发

section "步骤 8：并发许可与统计"
CONC="$(auth "$BASE_URL/api/ai/concurrency/status")"
ACTIVE="$(echo "$CONC" | jq -r '.data.activeCount')"
AVAILABLE="$(echo "$CONC" | jq -r '.data.availableGlobalPermits')"
MAX="$(echo "$CONC" | jq -r '.data.globalMax')"
if [ "$ACTIVE" = "0" ] && [ $((ACTIVE + AVAILABLE)) -eq "$MAX" ]; then
    pass "并发守恒律成立（$ACTIVE + $AVAILABLE = $MAX），AI 编辑的许可已归还"
else
    fail "并发许可未归还：$CONC"
fi

METRICS="$(auth "$BASE_URL/api/ai/metrics")"
COMPLETED="$(echo "$METRICS" | jq -r '.data.completed')"
FAILED_COUNT="$(echo "$METRICS" | jq -r '.data.failed')"
if [ $((COMPLETED + FAILED_COUNT)) -gt 0 ]; then
    pass "AI 编辑已计入指标（completed=$COMPLETED failed=$FAILED_COUNT）"
else
    fail "指标里没有 AI 编辑的记录"
fi

# ---------------------------------------------------------------- 汇总

section "汇总"
echo "  通过 $PASSED 项，失败 $FAILED 项"
[ "$FAILED" -eq 0 ] || exit 1
