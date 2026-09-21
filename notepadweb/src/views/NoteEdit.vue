<template>
  <div class="note-edit-page">
    <div class="writing-stage">
      <div ref="writingSurfaceRef" class="writing-surface">
        <el-input
          v-model="title"
          placeholder="无标题"
          class="title-input"
          @input="onTitleInput"
          @keydown.enter.prevent="focusEditor"
        />

        <div class="editor-wrap">
          <Toolbar
            v-if="editorRef"
            v-show="formatToolbarVisible"
            class="editor-toolbar"
            :editor="editorRef"
            :defaultConfig="toolbarConfig"
            mode="default"
          />
          <Editor
            v-if="noteId !== null && editingNoteId === noteId"
            :modelValue="content"
            class="editor-body"
            mode="default"
            :defaultConfig="editorConfig"
            @onCreated="handleCreated"
            @onChange="onEditorChange"
          />
        </div>
      </div>

      <!-- 低频能力集中到悬浮工具条，保持正文区域纯净 -->
      <div class="floating-tools" aria-label="编辑工具">
        <el-popover placement="left-start" :width="230" trigger="click">
          <template #reference>
            <el-button text circle :icon="InfoFilled" aria-label="查看笔记信息" />
          </template>
          <div class="floating-meta">
            <div class="floating-meta-row">
              <span>笔记本</span>
              <strong>{{ currentNotebookName }}</strong>
            </div>
            <div class="floating-meta-row">
              <span>时间</span>
              <strong>{{ reminder ? `提醒 ${reminderText}` : '今天' }}</strong>
            </div>
            <div class="floating-meta-row">
              <span>状态</span>
              <strong :class="['save-status', saving ? 'saving' : 'saved']">
                {{ saving ? '正在保存' : (lastSaveTime ? `已保存 · ${lastSaveTime}` : '自动保存') }}
              </strong>
            </div>
          </div>
        </el-popover>

        <el-tooltip content="文字格式" placement="left">
          <el-button
            text
            circle
            :type="formatToolbarVisible ? 'primary' : 'default'"
            :icon="EditPen"
            aria-label="显示或隐藏文字格式"
            @click="formatToolbarVisible = !formatToolbarVisible"
          />
        </el-tooltip>

        <el-tooltip content="AI 编辑" placement="left">
          <el-button
            text
            circle
            :icon="MagicStick"
            aria-label="AI 编辑笔记"
            @click="openAiEditDialog"
          />
        </el-tooltip>

        <el-popover placement="left-start" :width="270" trigger="click">
          <template #reference>
            <el-button text circle :icon="PriceTag" aria-label="管理标签" />
          </template>
          <div class="tool-popover">
            <strong>标签</strong>
            <el-select
              v-model="selectedTagIds"
              multiple
              filterable
              allow-create
              collapse-tags
              :max-collapse-tags="2"
              placeholder="添加标签"
              @change="onTagsChange"
            >
              <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
          </div>
        </el-popover>

        <el-popover placement="left-start" :width="230" trigger="click">
          <template #reference>
            <el-button text circle :icon="FolderOpened" aria-label="移动笔记本" />
          </template>
          <div class="tool-popover">
            <strong>所在笔记本</strong>
            <el-select v-model="currentNotebookId" @change="handleMoveNotebook">
              <el-option v-for="nb in notebooks" :key="nb.id" :label="nb.name" :value="nb.id" />
            </el-select>
          </div>
        </el-popover>

        <el-popover :visible="reminderVisible" placement="left-start" :width="390">
          <template #reference>
            <el-button
              text
              circle
              aria-label="设置提醒"
              :type="reminder ? 'warning' : 'default'"
              :icon="AlarmClock"
              @click="reminderVisible = !reminderVisible"
            />
          </template>
          <div class="reminder-form">
            <div class="reminder-row">
              <el-input-number v-model="reminderDays" :min="0" :max="30" controls-position="right" />
              <span class="reminder-label">天</span>
              <el-input-number v-model="reminderHours" :min="0" :max="23" controls-position="right" />
              <span class="reminder-label">时</span>
              <el-input-number v-model="reminderMinutes" :min="0" :max="59" controls-position="right" />
              <span class="reminder-label">分</span>
            </div>
            <p v-if="totalMinutes > 0" class="reminder-preview">≈ {{ humanize() }}</p>
            <p v-else class="reminder-tip">请至少设置 1 分钟</p>
            <div class="reminder-actions">
              <el-button size="small" type="primary" :disabled="totalMinutes <= 0" @click="handleSetReminder">设置</el-button>
              <el-button v-if="reminder" size="small" type="danger" text @click="handleCancelReminder">取消提醒</el-button>
            </div>
          </div>
        </el-popover>

        <el-dropdown trigger="click" placement="bottom-end" @command="handleMoreCommand">
          <el-button text circle :icon="MoreFilled" aria-label="更多操作" />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="save">立即保存</el-dropdown-item>
              <el-dropdown-item command="export">导出 Markdown</el-dropdown-item>
              <el-dropdown-item command="delete" divided class="danger-command">移入回收站</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <el-dialog
      v-model="aiEditDialogVisible"
      title="AI 编辑"
      width="min(560px, calc(100vw - 28px))"
      append-to-body
      destroy-on-close
      @closed="resetAiEdit"
    >
      <div class="ai-edit-panel">
        <div class="ai-edit-caps">
          <el-button
            v-for="cap in AI_NOTE_EDIT_CAPABILITIES"
            :key="cap.value"
            size="small"
            :type="aiEditCapability === cap.value ? 'primary' : 'default'"
            :disabled="aiEditStreaming || aiEditApplying"
            @click="startAiEdit(cap.value)"
          >
            {{ cap.label }}
          </el-button>
        </div>
        <p class="ai-edit-hint">{{ aiEditHint }}</p>

        <div v-if="aiEditStreaming || aiEditText" class="ai-edit-preview">
          <div class="ai-edit-preview__head">
            <strong>写回后的正文预览</strong>
            <el-button v-if="aiEditStreaming" size="small" text type="danger" @click="cancelAiEdit">
              停止生成
            </el-button>
          </div>
          <div class="ai-edit-preview__body" v-html="aiEditPreviewHtml" />
        </div>
        <div v-else class="ai-edit-empty">选择一个能力，AI 会依据当前正文生成结果</div>

        <section v-if="aiEditRevisions.length" class="ai-edit-revisions">
          <strong>可回退的原文</strong>
          <article v-for="revision in aiEditRevisions" :key="revision.id">
            <span>
              {{ aiEditCapabilityLabel(revision.capability) }} ·
              {{ formatAiEditTime(revision.createTime) }}
              <template v-if="revision.restored">（已回退过）</template>
            </span>
            <el-button
              size="small"
              text
              type="primary"
              :disabled="aiEditStreaming || aiEditApplying"
              @click="restoreAiEdit(revision)"
            >
              恢复原文
            </el-button>
          </article>
        </section>
      </div>

      <template #footer>
        <el-button :disabled="aiEditApplying" @click="aiEditDialogVisible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="aiEditApplying"
          :disabled="aiEditStreaming || !aiEditText || !aiEditContentHash"
          @click="applyAiEdit"
        >
          应用到笔记
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, computed, watch, onMounted, onBeforeUnmount, inject, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { AlarmClock, MoreFilled, EditPen, PriceTag, FolderOpened, InfoFilled, MagicStick } from '@element-plus/icons-vue'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'
import '@wangeditor/editor/dist/css/style.css'
import { getNoteDetail, createNote, saveNote, moveNote, deleteNote } from '../api/note'
import {
  AI_NOTE_EDIT_CAPABILITIES,
  applyNoteAiEdit,
  getNoteAiRevisions,
  restoreNoteAiRevision,
  streamNoteAiEdit,
  type AiNoteEditCapability,
  type AiNoteRevision
} from '../api/ai'
import { getNotebookList, type NotebookVO } from '../api/notebook'
import { getTagList, createTag, type TagVO } from '../api/tag'
import { setReminder, cancelReminder } from '../api/reminder'
import { uploadImage } from '../api/image'
import { useUserStore } from '../stores/user'
import { setLastNoteId } from '../utils/lastNote'
import { findNextNoteId } from '../utils/nextNote'
import { exportNoteAsMarkdown } from '../utils/export'
import { loadRagReference } from '../utils/ragReference'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const openNoteLibrary = inject<() => void>('openNoteLibrary')
const setActiveNoteNotebookId = inject<(noteId: number, notebookId: number | null) => void>(
  'setActiveNoteNotebookId'
)

const noteId = computed(() => {
  const id = route.params.id as string
  if (!id || id === 'new' || id === 'NaN') return null
  const num = Number(id)
  return Number.isNaN(num) ? null : num
})

// 编辑内容（content 存富文本 HTML）
const title = ref('')
const content = ref('')
const currentNotebookId = ref<number | null>(null)
const selectedTagIds = ref<number[]>([])
const saving = ref(false)
const lastSaveTime = ref('')
const saved = ref(false)
const formatToolbarVisible = ref(false)
// 当前正在编辑的笔记 id（加载/创建笔记时赋值；切走时用它保存旧笔记，不随路由即时变化）
const editingNoteId = ref<number | null>(null)
const writingSurfaceRef = ref<HTMLElement>()

const RAG_HIGHLIGHT_NAME = 'rag-source-highlight'
let lastRevealedReferenceId = ''

// ---- AI 编辑 ----
const aiEditDialogVisible = ref(false)
const aiEditCapability = ref<AiNoteEditCapability | null>(null)
const aiEditText = ref('')
const aiEditContentHash = ref('')
const aiEditStreaming = ref(false)
const aiEditApplying = ref(false)
const aiEditRevisions = ref<AiNoteRevision[]>([])
let aiEditController: AbortController | null = null

const aiEditHint = computed(() => {
  const cap = AI_NOTE_EDIT_CAPABILITIES.find(item => item.value === aiEditCapability.value)
  return cap ? cap.hint : '选择一个能力，AI 会依据当前正文生成结果；写回前你可以先看预览'
})

// 转义与段落包装必须与后端 AiNoteEditService 保持一致：先转义再包 <p>，
// 否则模型输出里的标签会在预览和笔记里变成可执行标记。
function escapeAiText(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function aiTextToHtml(text: string) {
  return text
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0)
    .map(line => `<p>${escapeAiText(line)}</p>`)
    .join('')
}

// 预览展示的是「写回后的完整正文」，而不是只显示 AI 片段 ——
// 用户必须看到笔记会变成什么样，才谈得上确认。
const aiEditPreviewHtml = computed(() => {
  if (!aiEditText.value || !aiEditCapability.value) return ''
  const block = aiTextToHtml(aiEditText.value)
  const base = content.value || ''
  if (aiEditCapability.value === 'summarize') return block + base
  if (aiEditCapability.value === 'rewrite') return block
  return base + block
})

function aiEditCapabilityLabel(value: string) {
  const cap = AI_NOTE_EDIT_CAPABILITIES.find(item => item.value === value)
  return cap ? cap.label : value
}

function formatAiEditTime(value: string) {
  if (!value) return '时间未知'
  const date = new Date(value.replace(' ', 'T'))
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

async function openAiEditDialog() {
  if (editingNoteId.value === null) {
    ElMessage.warning('请先保存笔记后再使用 AI 编辑')
    return
  }
  aiEditDialogVisible.value = true
  await loadAiEditRevisions()
}

async function loadAiEditRevisions() {
  const id = editingNoteId.value
  if (id === null) return
  try {
    aiEditRevisions.value = await getNoteAiRevisions(id)
  } catch {
    aiEditRevisions.value = []
    // 请求层已提示具体错误。
  }
}

function resetAiEdit() {
  cancelAiEdit()
  aiEditCapability.value = null
  aiEditText.value = ''
  aiEditContentHash.value = ''
  aiEditRevisions.value = []
}

function cancelAiEdit() {
  if (aiEditController) {
    aiEditController.abort()
    aiEditController = null
  }
  aiEditStreaming.value = false
}

async function startAiEdit(capability: AiNoteEditCapability) {
  const id = editingNoteId.value
  if (id === null || aiEditStreaming.value) return

  // 先把本地改动落盘：预览与写回都基于服务端内容，本地未保存的改动会让内容指纹对不上。
  await flushSave()

  cancelAiEdit()
  aiEditCapability.value = capability
  aiEditText.value = ''
  aiEditContentHash.value = ''
  aiEditStreaming.value = true
  const controller = new AbortController()
  aiEditController = controller

  try {
    await streamNoteAiEdit(
      id,
      capability,
      {
        onDelta: text => {
          aiEditText.value += text
        },
        onDone: payload => {
          aiEditContentHash.value = payload.contentHash
        }
      },
      controller.signal
    )
  } catch (error) {
    if (!controller.signal.aborted) {
      ElMessage.error(error instanceof Error ? error.message : 'AI 生成失败')
    }
  } finally {
    aiEditStreaming.value = false
    aiEditController = null
  }
}

async function applyAiEdit() {
  const id = editingNoteId.value
  const capability = aiEditCapability.value
  if (id === null || !capability || aiEditApplying.value) return
  if (!aiEditText.value.trim() || !aiEditContentHash.value) return

  aiEditApplying.value = true
  try {
    const result = await applyNoteAiEdit(id, {
      capability,
      text: aiEditText.value,
      contentHash: aiEditContentHash.value
    })
    // 服务端已经写过一次；这里赋值会顺带触发一次自动保存（内容相同、幂等），
    // 不引入抑制标志，避免本地状态与服务端状态出现分歧。
    content.value = result.note.content
    title.value = result.note.title
    selectedTagIds.value = result.note.tags.map(tag => tag.id)
    changeVersion += 1
    saved.value = true
    aiEditDialogVisible.value = false
    ElMessage.success('已应用到笔记，可在同一面板里恢复原文')
  } catch {
    // 请求层已提示具体错误（含 409 竞态提示）。
  } finally {
    aiEditApplying.value = false
  }
}

async function restoreAiEdit(revision: AiNoteRevision) {
  const id = editingNoteId.value
  if (id === null || aiEditApplying.value) return
  try {
    await ElMessageBox.confirm(
      `将把正文恢复到 ${formatAiEditTime(revision.createTime)} 这次 AI 编辑之前。确定继续吗？`,
      '恢复原文',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  aiEditApplying.value = true
  try {
    const note = await restoreNoteAiRevision(id, revision.id)
    content.value = note.content
    title.value = note.title
    selectedTagIds.value = note.tags.map(tag => tag.id)
    changeVersion += 1
    saved.value = true
    await loadAiEditRevisions()
    ElMessage.success('已恢复原文')
  } catch {
    // 请求层已提示具体错误。
  } finally {
    aiEditApplying.value = false
  }
}

interface TextPosition {
  node: Text
  offset: number
}

interface HighlightRegistry {
  set(name: string, highlight: object): void
  delete(name: string): boolean
}

function currentRagReferenceId() {
  return typeof route.query.ragRef === 'string' ? route.query.ragRef : ''
}

function clearRagHighlight() {
  const registry = (CSS as unknown as { highlights?: HighlightRegistry }).highlights
  registry?.delete(RAG_HIGHLIGHT_NAME)
  const selection = window.getSelection()
  if (selection && !selection.isCollapsed) selection.removeAllRanges()
}

function searchableText(value: string) {
  return value
    .replace(/^标题：[^\n]*\n+\s*正文：\s*/u, '')
    .replace(/[\s\u200B-\u200D\uFEFF]/gu, '')
    .toLowerCase()
}

function buildEditorTextIndex(root: HTMLElement) {
  const positions: TextPosition[] = []
  const characters: string[] = []
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let current = walker.nextNode()
  while (current) {
    const textNode = current as Text
    const value = textNode.data
    for (let offset = 0; offset < value.length; offset++) {
      const character = value[offset]
      if (!/[\s\u200B-\u200D\uFEFF]/u.test(character)) {
        characters.push(character.toLowerCase())
        positions.push({ node: textNode, offset })
      }
    }
    current = walker.nextNode()
  }
  return { text: characters.join(''), positions }
}

function findQuotedRange(editorText: string, quoteText: string) {
  const exactIndex = editorText.indexOf(quoteText)
  if (exactIndex >= 0) return { index: exactIndex, length: quoteText.length }

  const lengths = [180, 120, 80, 48, 24].filter(length => length <= quoteText.length)
  for (const length of lengths) {
    const step = Math.max(1, Math.floor(length / 3))
    for (let start = 0; start + length <= quoteText.length; start += step) {
      const index = editorText.indexOf(quoteText.slice(start, start + length))
      if (index >= 0) return { index, length }
    }
    const tailIndex = editorText.indexOf(quoteText.slice(-length))
    if (tailIndex >= 0) return { index: tailIndex, length }
  }
  return null
}

async function revealRagReference(attempt = 0) {
  const referenceId = currentRagReferenceId()
  if (!referenceId || referenceId === lastRevealedReferenceId) return
  const source = loadRagReference(referenceId)
  if (!source || source.noteId !== noteId.value) return

  await nextTick()
  const editorRoot = writingSurfaceRef.value?.querySelector<HTMLElement>('[data-slate-editor]')
  if (!editorRoot || editingNoteId.value !== source.noteId) {
    if (attempt < 6) {
      window.setTimeout(() => void revealRagReference(attempt + 1), 80)
    }
    return
  }

  const quoteText = searchableText(source.content)
  const index = buildEditorTextIndex(editorRoot)
  const match = quoteText.length >= 8 ? findQuotedRange(index.text, quoteText) : null
  lastRevealedReferenceId = referenceId
  clearRagHighlight()
  if (!match) {
    ElMessage.warning('已打开原笔记，但引用内容可能已修改，无法定位原文')
    return
  }

  const start = index.positions[match.index]
  const end = index.positions[match.index + match.length - 1]
  if (!start || !end) return
  const range = document.createRange()
  range.setStart(start.node, start.offset)
  range.setEnd(end.node, end.offset + 1)

  const registry = (CSS as unknown as { highlights?: HighlightRegistry }).highlights
  const HighlightClass = (window as Window & {
    Highlight?: new (...ranges: Range[]) => object
  }).Highlight
  if (registry && HighlightClass) {
    registry.set(RAG_HIGHLIGHT_NAME, new HighlightClass(range))
  } else {
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
  }

  const surface = writingSurfaceRef.value
  if (!surface) return
  const rangeTop = range.getBoundingClientRect().top
  const surfaceTop = surface.getBoundingClientRect().top
  surface.scrollTo({
    top: Math.max(0, surface.scrollTop + rangeTop - surfaceTop - surface.clientHeight * 0.25),
    behavior: 'smooth'
  })
  ElMessage.success({ message: '已定位并高亮引用原文', duration: 1600 })
}

// 笔记本和标签选项
const notebooks = ref<NotebookVO[]>([])
const tags = ref<TagVO[]>([])

// 提醒
const reminder = ref<{ id: number; remindAt: string; status: number } | null>(null)
const reminderVisible = ref(false)
const reminderDays = ref(0)
const reminderHours = ref(0)
const reminderMinutes = ref(5)

const totalMinutes = computed(() => {
  return reminderDays.value * 24 * 60 + reminderHours.value * 60 + reminderMinutes.value
})
const currentNotebookName = computed(() => {
  return notebooks.value.find(nb => nb.id === currentNotebookId.value)?.name || '我的笔记'
})

function humanize() {
  const d = reminderDays.value
  const h = reminderHours.value
  const m = reminderMinutes.value
  const parts: string[] = []
  if (d > 0) parts.push(`${d} 天`)
  if (h > 0) parts.push(`${h} 小时`)
  if (m > 0) parts.push(`${m} 分钟`)
  return parts.join(' ') + ' 后提醒'
}

const reminderText = computed(() => {
  if (!reminder.value) return ''
  return reminder.value.remindAt.replace('T', ' ')
})

// ---- wangEditor ----
const editorRef = shallowRef<IDomEditor>()

const toolbarConfig: Partial<IToolbarConfig> = {
  toolbarKeys: [
    'undo', 'redo', '|',
    'headerSelect', 'fontSize', 'fontFamily', '|',
    'bold', 'italic', 'underline', 'through', 'color', 'bgColor', '|',
    'blockquote', 'bulletedList', 'numberedList', 'todo', '|',
    'insertLink', 'uploadImage', 'insertTable', '|',
    'clearStyle'
  ]
}

const editorConfig: Partial<IEditorConfig> = {
  placeholder: '请输入内容...',
  // 由整篇文档负责滚动，让标题与正文保持在同一个滚动流中
  scroll: false,
  MENU_CONF: {
    fontSize: {
      fontSizeList: ['12px', '14px', '16px', '18px', '20px', '24px', '28px', '32px', '36px']
    },
    fontFamily: {
      fontFamilyList: ['宋体', '黑体', '楷体', '微软雅黑', 'Arial', 'Tahoma', 'Verdana', 'Times New Roman', 'Courier New']
    },
    color: {
      colors: ['#000000', '#333333', '#666666', '#999999', '#cccccc', '#ffffff', '#f56c6c', '#e6a23c', '#67c23a', '#409eff', '#909399']
    },
    bgColor: {
      colors: ['#000000', '#fde2e2', '#fdf6ec', '#f0f9eb', '#ecf5ff', '#f4f4f5', '#ffffff']
    },
    uploadImage: {
      // 禁用 base64，全部走自定义上传
      base64LimitSize: 0,
      async customUpload(file: File, insertFn: (url: string, alt: string, href: string) => void) {
        try {
          const data = await uploadImage(file)
          insertFn(data.url, file.name, data.url)
        } catch { /* 拦截器已提示 */ }
      }
    }
  }
}

function handleCreated(editor: IDomEditor) {
  editorRef.value = editor
  void revealRagReference()
}

function onEditorChange(editor: IDomEditor) {
  // wangEditor 的旧实例在切换路由后仍可能发出变更；只接收当前笔记的实例。
  if (editor !== editorRef.value || editingNoteId.value !== noteId.value) return
  content.value = editor.getHtml()
  triggerAutoSave()
}

function focusEditor() {
  editorRef.value?.focus()
}

// ---- 实时保存 ----
const AUTO_SAVE_DELAY = 700
let saveChain: Promise<void> = Promise.resolve()
let queued = false
let saveTimer: ReturnType<typeof setTimeout> | null = null
let changeVersion = 0
// 路由快速切换时，旧笔记详情请求可能晚于新建请求返回；用序号丢弃过期初始化结果。
let initRequestId = 0

interface LocalDraft {
  title: string
  content: string
  tagIds: number[]
  updatedAt: number
}

function draftKey(id: number) {
  return `noteDraft:${id}`
}

function saveDraft(id: number) {
  const draft: LocalDraft = {
    title: title.value,
    content: content.value,
    tagIds: [...selectedTagIds.value],
    updatedAt: Date.now()
  }
  try {
    localStorage.setItem(draftKey(id), JSON.stringify(draft))
  } catch {
    // 本地存储不可用或空间不足时，仍继续执行服务端自动保存
  }
}

function clearDraft(id: number) {
  try {
    localStorage.removeItem(draftKey(id))
  } catch { /* ignore */ }
}

function restoreDraft(id: number): boolean {
  const raw = localStorage.getItem(draftKey(id))
  if (!raw) return false
  try {
    const draft = JSON.parse(raw) as LocalDraft
    title.value = draft.title || ''
    content.value = draft.content || ''
    selectedTagIds.value = Array.isArray(draft.tagIds) ? draft.tagIds : []
    return true
  } catch {
    clearDraft(id)
    return false
  }
}

function onTitleInput(value: string) {
  title.value = value
  triggerAutoSave()
}

function triggerAutoSave() {
  const id = editingNoteId.value
  if (id === null) return
  changeVersion += 1
  saved.value = false
  saveDraft(id)
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    saveTimer = null
    enqueueSave()
  }, AUTO_SAVE_DELAY)
}

function enqueueSave() {
  if (queued) return
  const id = editingNoteId.value
  if (id === null) return
  queued = true
  saveChain = saveChain.then(async () => {
    queued = false
    // 已切换到别的笔记时跳过，避免用旧内容覆盖新笔记
    if (editingNoteId.value !== id) return
    saving.value = true
    try {
      await doSaveOnce(id, changeVersion)
    } finally {
      saving.value = false
    }
  })
}

async function doSaveOnce(id: number, version: number) {
  const payload = {
    title: title.value,
    content: content.value,
    tagIds: [...selectedTagIds.value]
  }
  try {
    await saveNote(id, payload)
    const isLatest = editingNoteId.value === id && changeVersion === version
    saved.value = isLatest
    if (isLatest) clearDraft(id)
    lastSaveTime.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch { /* 拦截器已提示 */ }
}

// 切走/卸载前强制保存当前笔记的最新内容（排队并等待完成）
async function flushSave() {
  if (editingNoteId.value === null) return
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  // 已登出时不再尝试保存：请求必然 401，只会弹一条"登录状态已失效"的误导提示，
  // 干扰退出登录本身的跳转（草稿仍在 localStorage 里，重新登录后还能恢复）
  if (!userStore.isLoggedIn()) return
  enqueueSave()
  await saveChain
}

async function handleSave() {
  if (editingNoteId.value === null) return
  await flushSave()
  if (saved.value) ElMessage.success('已保存')
}

async function onTagsChange(ids: (number | string)[]) {
  // 检查是否有新输入的标签名（string 类型）
  const newTags = ids.filter(id => typeof id === 'string') as string[]
  for (const name of newTags) {
    try {
      const created = await createTag({ name })
      const idx = selectedTagIds.value.indexOf(name as any)
      if (idx >= 0) selectedTagIds.value.splice(idx, 1, created.id)
    } catch {
      // 标签已存在（409），把字符串从数组中移除，避免后端反序列化失败
      const idx = selectedTagIds.value.indexOf(name as any)
      if (idx >= 0) selectedTagIds.value.splice(idx, 1)
      ElMessage.warning(`标签「${name}」已存在`)
    }
  }
  triggerAutoSave()
}

async function handleMoveNotebook(notebookId: number) {
  if (!noteId.value) return
  try {
    await moveNote(noteId.value, notebookId)
    setActiveNoteNotebookId?.(noteId.value, notebookId)
    ElMessage.success('移动成功')
  } catch { /* ignore */ }
}

async function handleDelete() {
  try {
    await ElMessageBox.confirm('笔记将移入回收站，可在回收站恢复。确定删除吗？', '删除笔记', { type: 'warning' })
  } catch { return }

  const id = editingNoteId.value
  if (!id) return
  await flushSave()
  editingNoteId.value = null
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  try {
    await deleteNote(id)
    clearDraft(id)
    ElMessage.success('已移入回收站')
    if (noteId.value !== id) return
    const nextId = await findNextNoteId(id, currentNotebookId.value)
    if (nextId !== null) {
      setLastNoteId(nextId)
      await router.replace(`/notes/${nextId}`)
    } else {
      localStorage.removeItem('lastNoteId')
      await router.replace('/notes/empty')
    }
  } catch {
    editingNoteId.value = id
  }
}

async function handleSetReminder() {
  if (!noteId.value) {
    ElMessage.warning('请先保存笔记再设置提醒')
    return
  }
  const totalMins = totalMinutes.value
  if (totalMins <= 0) return
  try {
    const result = await setReminder(noteId.value, 'MINUTES', totalMins)
    reminder.value = result
    reminderVisible.value = false
    ElMessage.success('提醒已设置')
  } catch { /* ignore */ }
}

async function handleCancelReminder() {
  try {
    await ElMessageBox.confirm('确定取消提醒？', '提示')
  } catch { return }

  if (!noteId.value) return
  await cancelReminder(noteId.value)
  reminder.value = null
  reminderVisible.value = false
  ElMessage.success('已取消')
}

function handleExport() {
  exportNoteAsMarkdown(title.value, content.value)
}

function handleMoreCommand(command: 'save' | 'export' | 'delete') {
  if (command === 'save') handleSave()
  if (command === 'export') handleExport()
  if (command === 'delete') handleDelete()
}

// 初始化：加载选项 + 加载/创建笔记
async function init(ignoreNew: boolean = false) {
  const requestId = ++initRequestId
  const requestedRouteId = route.params.id
  const isCreating = requestedRouteId === 'new' && !ignoreNew
  if (isCreating) {
    editingNoteId.value = null
    title.value = ''
    content.value = ''
    selectedTagIds.value = []
    reminder.value = null
    saved.value = true
    lastSaveTime.value = ''
  }
  const [nbList, tagList] = await Promise.all([getNotebookList(), getTagList()])
  if (requestId !== initRequestId || route.params.id !== requestedRouteId) return
  notebooks.value = nbList
  tags.value = tagList

  if (isCreating) {
    try {
      const queryNotebookId = route.query.notebookId ? Number(route.query.notebookId) : undefined
      const defaultNb = notebooks.value.find(n => n.isDefault) || notebooks.value[0]
      const note = await createNote({ notebookId: queryNotebookId ?? defaultNb?.id })
      if (requestId !== initRequestId || route.params.id !== 'new') return
      setLastNoteId(note.id)
      editingNoteId.value = note.id
      title.value = note.title
      content.value = note.content || ''
      currentNotebookId.value = note.notebookId
      setActiveNoteNotebookId?.(note.id, note.notebookId)
      selectedTagIds.value = note.tags.map(t => t.id)
      reminder.value = note.reminder
      saved.value = true
      router.replace({ path: `/notes/${note.id}`, replace: true })
    } catch {
      ElMessage.error('新建笔记失败，请稍后重试')
      openNoteLibrary?.()
    }
    return
  }

  const id = noteId.value
  if (!id) {
    router.replace('/notes/empty')
    return
  }
  try {
    const detail = await getNoteDetail(id)
    if (requestId !== initRequestId || route.params.id !== String(id)) return
    setLastNoteId(id)
    editingNoteId.value = id
    title.value = detail.title
    content.value = detail.content || ''
    currentNotebookId.value = detail.notebookId
    setActiveNoteNotebookId?.(id, detail.notebookId)
    selectedTagIds.value = detail.tags.map(t => t.id)
    reminder.value = detail.reminder
    lastSaveTime.value = detail.updateTime.replace('T', ' ').replace(/\d{2}:\d{2}:\d{2}$/, '').trim()
    const recovered = restoreDraft(id)
    saved.value = !recovered
    if (recovered) {
      ElMessage.info('已恢复上次未同步的本地草稿')
      triggerAutoSave()
    }
    void revealRagReference()
  } catch {
    localStorage.removeItem('lastNoteId')
    router.replace('/notes/empty')
  }
}

// 监听路由变化
watch(
  () => route.params.id,
  (newId, oldId) => {
    if (newId === oldId) return
    // @wangeditor/editor-for-vue 卸载组件时不会自动销毁编辑器实例。
    // 路由切换前销毁旧实例，避免其后续事件把旧正文写进新笔记。
    const previousEditor = editorRef.value
    editorRef.value = undefined
    previousEditor?.destroy()
  },
  { flush: 'sync' }
)

watch(
  () => route.params.id,
  async (newId, oldId) => {
    if (oldId === 'new' && newId && newId !== 'new' && newId !== 'NaN') {
      return
    }
    if (newId && newId !== oldId) {
      clearRagHighlight()
      lastRevealedReferenceId = ''
      // 切走前先保存当前笔记的最新内容
      await flushSave()
      // 切到新建路由时正常创建新笔记，其余场景沿用原有逻辑
      await init(newId !== 'new')
    }
  }
)

watch(
  () => route.query.ragRef,
  () => void revealRagReference()
)

onBeforeRouteLeave(async () => {
  // 这个守卫的 Promise 一旦 reject，vue-router 会把它当成「导航中止」，页面就卡在原处不动。
  // 登出后 token 已被吊销，这里的保存必然 401 —— 正是这个场景导致退出登录后页面不跳转。
  // 保存失败只该丢一次草稿，绝不该拦着用户走。
  try {
    await flushSave()
  } catch {
    // 拦截器已提示，这里只负责放行
  }
})

function preserveDraftBeforeUnload() {
  if (editingNoteId.value !== null && !saved.value) {
    saveDraft(editingNoteId.value)
  }
}

function handleExternalRename(event: Event) {
  const detail = (event as CustomEvent<{ id: number; title: string }>).detail
  if (detail?.id === editingNoteId.value) {
    title.value = detail.title
  }
}

function handleExternalDelete(event: Event) {
  const id = (event as CustomEvent<{ id: number }>).detail?.id
  if (id !== editingNoteId.value) return
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  clearDraft(id)
  editingNoteId.value = null
}

function handleExternalDeleteCancelled(event: Event) {
  const id = (event as CustomEvent<{ id: number }>).detail?.id
  if (id && editingNoteId.value === null && noteId.value === id) {
    editingNoteId.value = id
  }
}

onMounted(() => {
  window.addEventListener('beforeunload', preserveDraftBeforeUnload)
  window.addEventListener('note-renamed', handleExternalRename)
  window.addEventListener('note-deleting', handleExternalDelete)
  window.addEventListener('note-delete-cancelled', handleExternalDeleteCancelled)
  init()
})

onBeforeUnmount(() => {
  clearRagHighlight()
  if (saveTimer) clearTimeout(saveTimer)
  preserveDraftBeforeUnload()
  window.removeEventListener('beforeunload', preserveDraftBeforeUnload)
  window.removeEventListener('note-renamed', handleExternalRename)
  window.removeEventListener('note-deleting', handleExternalDelete)
  window.removeEventListener('note-delete-cancelled', handleExternalDeleteCancelled)
  editorRef.value?.destroy()
})
</script>

<style scoped>
.note-edit-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 64px);
  background: #fff;
  overflow: hidden;
}
.save-status {
  color: #929a94;
  font-size: 12px;
  white-space: nowrap;
}
.save-status.saving { color: #9c7440; }
.save-status.saved { color: #708079; }
.writing-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.writing-surface {
  width: 100%;
  height: 100%;
  margin: 0;
  padding: clamp(24px, 4vh, 36px) 0 0 clamp(28px, 4vw, 64px);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
  overflow-y: auto;
}
.title-input :deep(.el-input__wrapper) {
  box-shadow: none !important;
  padding: 0;
  background: transparent;
}
.title-input :deep(.el-input__inner) {
  border: none;
  height: 62px;
  color: #202421;
  font-family: Georgia, "Noto Serif SC", "Songti SC", serif;
  font-size: clamp(30px, 3.4vw, 40px);
  font-weight: 500;
  line-height: 1.35;
  letter-spacing: -0.025em;
  padding: 0;
}
.title-input :deep(.el-input__inner::placeholder) {
  color: #c6cbc7;
}
.reminder-form { padding: 8px 0; }
.reminder-row {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 8px;
}
.reminder-label {
  font-size: 14px;
  color: #606266;
  white-space: nowrap;
}
.reminder-form :deep(.el-input-number) {
  --el-input-number-controls-width: 32px;
  width: 92px !important;
}
.reminder-form :deep(.el-input__wrapper) {
  padding-right: 42px !important;
}
.reminder-preview { margin: 4px 0 10px; font-size: 12px; color: #909399; }
.reminder-tip { margin: 4px 0 10px; font-size: 12px; color: #f56c6c; }
.reminder-actions { display: flex; gap: 8px; }

.editor-wrap {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  min-height: calc(100% - 80px);
  overflow: visible;
  margin-top: 18px;
}
.editor-toolbar {
  flex-shrink: 0;
  min-height: 42px;
  border: 0;
  margin-bottom: 12px;
  border: 1px solid #e8ece9;
  border-radius: 11px;
  background: #fafbfa;
  padding: 3px 5px;
  --w-e-toolbar-bg-color: #fafbfa;
  --w-e-toolbar-color: #606a63;
  --w-e-toolbar-active-color: #247b68;
  --w-e-toolbar-active-bg-color: #eaf3f0;
  --w-e-toolbar-border-color: transparent;
}
.editor-body {
  flex: 0 0 auto;
  height: auto;
  min-height: calc(100vh - 190px);
  border: 0;
  overflow: visible;
  --w-e-textarea-bg-color: #fff;
  --w-e-textarea-color: #333a35;
  --w-e-textarea-border-color: transparent;
}
.editor-body :deep(.w-e-text-container) {
  height: auto !important;
  min-height: inherit;
  overflow: visible !important;
  background: #fff;
}
.editor-body :deep(.w-e-text-placeholder) {
  top: 28px;
  color: #b3bab5;
  font-style: normal;
}
.editor-body :deep(.w-e-scroll) {
  height: auto !important;
  min-height: inherit;
  overflow: visible !important;
  padding: 16px 4px 90px !important;
}
.editor-body :deep([data-slate-editor]) {
  min-height: 100%;
  color: #343b36;
  font-size: 16px;
  line-height: 1.9;
}
.editor-body :deep([data-slate-editor] p) {
  margin: 0 0 1em;
}
.editor-body :deep([data-slate-editor] h1),
.editor-body :deep([data-slate-editor] h2),
.editor-body :deep([data-slate-editor] h3) {
  color: #222824;
  font-family: Georgia, "Noto Serif SC", "Songti SC", serif;
  font-weight: 500;
}
.editor-body :deep([data-slate-editor] blockquote) {
  margin: 1.5em 0;
  padding: 4px 0 4px 18px;
  border-left: 3px solid #247b68;
  background: transparent;
  color: #667069;
}
.floating-tools {
  position: absolute;
  top: 24px;
  right: 22px;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 5px;
  border: 1px solid #e7ebe8;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 10px 28px rgba(35, 51, 42, 0.09);
}
.floating-tools :deep(.el-button) {
  width: 36px;
  height: 36px;
  margin: 0;
  color: #6f7972;
}
.floating-tools :deep(.el-button:hover) {
  color: #247b68;
  background: #eaf3f0;
}
.floating-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.floating-meta-row {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
  align-items: baseline;
  gap: 10px;
  color: #8a938d;
  font-size: 12px;
}
.floating-meta-row strong {
  min-width: 0;
  color: #3e4741;
  font-size: 13px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.floating-meta-row .save-status {
  white-space: normal;
}
.tool-popover {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tool-popover strong {
  color: #343b36;
  font-size: 14px;
  font-weight: 500;
}

@media (max-width: 900px) {
  .save-status { display: none; }
  .writing-surface {
    width: 100%;
    margin: 0;
    padding: 24px 0 0 34px;
  }
}

@media (max-width: 640px) {
  .note-edit-page { height: calc(100vh - 64px); }
  .writing-surface {
    width: 100%;
    margin: 0;
    padding: 20px 0 0 22px;
  }
  .title-input :deep(.el-input__inner) { font-size: 30px; height: 54px; }
  .editor-wrap { margin-top: 16px; }
  .editor-toolbar { overflow-x: auto; }
}

@media (max-width: 520px) {
  .writing-surface {
    padding-left: 18px;
    padding-bottom: 62px;
  }
  .floating-tools {
    top: auto;
    right: 50%;
    bottom: 14px;
    transform: translateX(50%);
    flex-direction: row;
    border-radius: 14px;
    box-shadow: 0 8px 24px rgba(35, 51, 42, 0.14);
  }
}

:global(::highlight(rag-source-highlight)) {
  color: inherit;
  background-color: rgba(255, 205, 74, 0.58);
  text-decoration: underline 2px rgba(211, 151, 28, 0.7);
}

/* ---- AI 编辑面板 ---- */
.ai-edit-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ai-edit-caps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ai-edit-hint {
  margin: 0;
  color: #8a948e;
  font-size: 12px;
  line-height: 1.6;
}
.ai-edit-preview {
  overflow: hidden;
  border: 1px solid #e7ebe8;
  border-radius: 11px;
  background: #fafbfa;
}
.ai-edit-preview__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid #e7ebe8;
  background: #f4f7f5;
}
.ai-edit-preview__head strong {
  color: #354139;
  font-size: 12px;
}
.ai-edit-preview__body {
  max-height: 260px;
  overflow-y: auto;
  padding: 12px;
  color: #4a554e;
  font-size: 13px;
  line-height: 1.7;
}
.ai-edit-preview__body p {
  margin: 0 0 8px;
}
.ai-edit-empty {
  padding: 24px 12px;
  color: #929b95;
  font-size: 12px;
  text-align: center;
}
.ai-edit-revisions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 12px;
  border-top: 1px solid #e7ebe8;
}
.ai-edit-revisions > strong {
  color: #354139;
  font-size: 12px;
}
.ai-edit-revisions article {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 10px;
  border: 1px solid #eef1ef;
  border-radius: 10px;
  background: #fbfcfb;
}
.ai-edit-revisions span {
  color: #7d8a82;
  font-size: 11px;
}
</style>
