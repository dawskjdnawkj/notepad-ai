<template>
  <el-container class="layout">
    <!-- 侧边栏：默认保持安静，需要时再展开管理笔记本与标签 -->
    <el-aside :width="isCollapse ? '72px' : '354px'" class="aside" :class="{ 'aside--collapsed': isCollapse }">
      <div class="rail-column">
        <div class="aside-header">
          <button
            type="button"
            class="brand-mark"
            :aria-label="isCollapse ? '展开笔记库' : '收起笔记库'"
            :aria-expanded="!isCollapse"
            @click="toggleNoteLibrary"
          >
            <el-icon><Notebook /></el-icon>
          </button>
        </div>

        <nav class="rail-nav" aria-label="主导航">
          <el-tooltip content="笔记库" placement="right">
            <button
              type="button"
              class="rail-nav-button"
              :class="{ active: activeMenu === 'notes' }"
              aria-label="笔记库"
              @click="openNotes"
            ><el-icon><Document /></el-icon></button>
          </el-tooltip>
          <el-tooltip content="日历视图" placement="right">
            <button
              type="button"
              class="rail-nav-button"
              :class="{ active: activeMenu === 'calendar' }"
              aria-label="日历视图"
              @click="router.push('/calendar')"
            ><el-icon><Calendar /></el-icon></button>
          </el-tooltip>
          <el-tooltip content="回收站" placement="right">
            <button
              type="button"
              class="rail-nav-button"
              :class="{ active: activeMenu === 'trash' }"
              aria-label="回收站"
              @click="router.push('/trash')"
            ><el-icon><Delete /></el-icon></button>
          </el-tooltip>
        </nav>
      </div>

      <!-- 笔记本列表 -->
      <div class="notebook-section library-panel" v-show="!isCollapse">
        <div class="library-heading">
          <strong>笔记库</strong>
          <el-button :icon="Plus" text circle aria-label="新建笔记" @click="handleNewNote" />
        </div>
        <el-input
          v-model="sidebarKeyword"
          :prefix-icon="Search"
          clearable
          placeholder="搜索全部笔记"
          class="sidebar-search"
          @input="handleSidebarSearch"
          @clear="clearSidebarSearch"
        />
        <el-select
          v-model="activeTagId"
          class="sidebar-tag-filter"
          clearable
          filterable
          placeholder="按标签筛选"
          @change="handleTagFilterChange"
        >
          <el-option v-for="tag in tags" :key="tag.id" :label="`${tag.name} (${tag.noteCount})`" :value="tag.id" />
        </el-select>

        <div v-if="isFiltering" class="search-result-section">
          <div class="section-title">
            <span>筛选结果</span>
            <span class="filter-result-actions">
              <span>{{ searchTotal }}</span>
              <el-button text size="small" @click="clearAllFilters">返回笔记本</el-button>
            </span>
          </div>
          <div v-if="searchLoading && searchResults.length === 0" class="search-loading">正在搜索...</div>
          <div v-else-if="searchResults.length === 0" class="search-empty">没有找到相关笔记</div>
          <div v-else class="search-note-list">
            <div
              v-for="note in searchResults"
              :key="note.id"
              class="note-child search-note"
              :class="{ active: activeNoteId === note.id }"
              @click="openNote(note.id, note.notebookId)"
              @contextmenu.prevent="openNoteMenu($event, note)"
            >
              <el-icon v-if="note.pinned === 1" class="pin-icon"><Top /></el-icon>
              <el-icon v-else><Document /></el-icon>
              <span class="child-title">{{ note.title || '无标题' }}</span>
              <span class="search-notebook">{{ note.notebookName }}</span>
            </div>
            <el-button
              v-if="searchResults.length < searchTotal"
              class="load-more"
              text
              :loading="searchLoading"
              @click="loadMoreSearch"
            >加载更多</el-button>
          </div>
        </div>

        <template v-else>
        <div class="section-title">
          <span>笔记本</span>
          <el-button :icon="Plus" size="small" text @click="handleAddNotebook" />
        </div>
        <div class="notebook-list">
          <div v-for="nb in notebooks" :key="nb.id" class="notebook-group">
            <div
              class="notebook-item"
              :class="{ active: currentNotebookId === nb.id }"
              @click="toggleNotebook(nb.id)"
              @contextmenu.prevent="openNotebookMenu($event, nb)"
            >
              <el-icon class="expand-btn">
                <CaretRight v-if="!isExpanded(nb.id)" />
                <CaretBottom v-else />
              </el-icon>
              <el-icon><Notebook /></el-icon>
              <span class="name">{{ nb.name }}</span>
              <span class="count">{{ nb.noteCount }}</span>
            </div>
            <div v-if="isExpanded(nb.id)" class="note-children">
              <div
                v-for="note in notebookNotes[nb.id] || []"
                :key="note.id"
                class="note-child"
                :class="{ active: activeNoteId === note.id }"
                @click="openNote(note.id, nb.id)"
                @contextmenu.prevent="openNoteMenu($event, note)"
              >
                <el-icon v-if="note.pinned === 1" class="pin-icon"><Top /></el-icon>
                <el-icon v-else><Document /></el-icon>
                <span class="child-title">{{ note.title || '无标题' }}</span>
              </div>
              <div v-if="!(notebookNotes[nb.id] || []).length" class="note-child empty">暂无笔记</div>
              <el-button
                v-if="(notebookNotes[nb.id] || []).length < (notebookTotals[nb.id] || 0)"
                class="load-more"
                text
                :loading="notebookLoading[nb.id]"
                @click.stop="loadMoreNotebook(nb.id)"
              >加载更多</el-button>
            </div>
          </div>
        </div>
        </template>
      </div>

    </el-aside>

    <!-- 笔记本右键菜单 -->
    <Teleport to="body">
      <div
        v-if="notebookMenu.visible"
        class="context-menu"
        :style="{ left: notebookMenu.x + 'px', top: notebookMenu.y + 'px' }"
      >
        <div class="menu-item" @click="handleNewNoteInNotebook"><el-icon><Plus /></el-icon>新建笔记</div>
        <div class="menu-item" @click="handleRenameNotebook"><el-icon><Edit /></el-icon>重命名</div>
        <div v-if="!notebookMenu.data?.isDefault" class="menu-item danger" @click="handleDeleteNotebook"><el-icon><Delete /></el-icon>删除笔记本</div>
      </div>
      <!-- 点击空白关闭菜单 -->
      <div v-if="notebookMenu.visible" class="menu-mask" @click="closeMenus" />
    </Teleport>

    <!-- 笔记右键菜单 -->
    <Teleport to="body">
      <div
        v-if="noteMenu.visible"
        class="context-menu"
        :style="{ left: noteMenu.x + 'px', top: noteMenu.y + 'px' }"
      >
        <div class="menu-item" @click="handleTogglePin">
          <el-icon><Top /></el-icon>{{ noteMenu.data?.pinned === 1 ? '取消置顶' : '置顶' }}
        </div>
        <div class="menu-item" @click="handleRenameNote"><el-icon><Edit /></el-icon>重命名</div>
        <div class="menu-item danger" @click="handleDeleteNote"><el-icon><Delete /></el-icon>删除笔记</div>
      </div>
      <div v-if="noteMenu.visible" class="menu-mask" @click="closeMenus" />
    </Teleport>

    <!-- 主内容区 -->
    <el-container class="workspace-shell" :class="{ 'workspace-shell--ai': aiAssistantVisible }">
      <!-- 顶栏 -->
      <el-header class="header">
        <div class="header-left">
          <div class="page-context">
            <span class="workspace-name">我的知识空间</span>
            <strong>{{ pageTitle }}</strong>
          </div>
          <input
            ref="importInputRef"
            type="file"
            accept=".md,.txt"
            multiple
            class="hidden-input"
            @change="handleImportFiles"
          />
        </div>
        <div class="header-right">
          <el-button
            class="ai-assistant-button"
            :class="{ active: aiAssistantVisible }"
            :icon="MagicStick"
            @click="aiAssistantVisible = !aiAssistantVisible"
          >AI 助手</el-button>
          <el-button class="quiet-action" :icon="Upload" text @click="triggerImport">导入</el-button>
          <el-button class="new-note-button" type="primary" :icon="Plus" @click="handleNewNote">写一篇</el-button>
          <!-- 通知 -->
          <el-badge :value="notificationStore.unreadCount" :hidden="notificationStore.unreadCount === 0" class="notice-badge">
            <el-button class="icon-action" :icon="Bell" text circle aria-label="通知" @click="$router.push('/notifications')" />
          </el-badge>
          <!-- 用户 -->
          <el-dropdown>
            <span class="user-info">
              <span class="user-avatar">{{ userInitial }}</span>
              <span class="user-name">{{ userStore.userInfo?.username }}</span>
              <el-icon class="user-arrow"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-item @click="openChangePassword">修改密码</el-dropdown-item>
              <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 内容 -->
      <el-main class="main" :class="{ 'main--editor': isEditorRoute }">
        <router-view />
      </el-main>
    </el-container>

    <AiAssistant
      v-model:open="aiAssistantVisible"
      :current-note-id="activeNoteId"
      :current-notebook-id="activeNotebookId"
      @open-note="openNoteFromAi"
    />

    <!-- 修改密码弹窗 -->
    <el-dialog v-model="changePwdVisible" title="修改密码" width="420px">
      <el-form ref="changePwdFormRef" :model="changePwdForm" :rules="changePwdRules" label-position="top">
        <el-form-item label="验证码" prop="code">
          <div class="change-code-row">
            <el-input v-model="changePwdForm.code" placeholder="6 位数字验证码" maxlength="6" />
            <el-button :disabled="changeCountdown > 0" :loading="changeSending" @click="handleSendChangeCode">
              {{ changeCountdown > 0 ? `${changeCountdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="changePwdForm.newPassword" type="password" placeholder="6~20 位新密码" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="changePwdForm.confirmPassword" type="password" placeholder="请再次输入新密码" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changePwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="changeLoading" @click="handleChangePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onUnmounted, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Bell, ArrowDown, Document, Calendar, Delete, Notebook, Edit, CaretRight, CaretBottom, Upload, Search, Top, MagicStick } from '@element-plus/icons-vue'
import AiAssistant from '../components/AiAssistant.vue'
import { useUserStore } from '../stores/user'
import { sendChangePasswordCode, changePassword } from '../api/auth'
import { getNotebookList, createNotebook, renameNotebook, deleteNotebook } from '../api/notebook'
import { createNote, getNoteList, pinNote, renameNote, deleteNote } from '../api/note'
import { getTagList } from '../api/tag'
import type { NoteListItem } from '../api/note'
import type { RagSource } from '../api/ai'
import { filenameToTitle, textToHtml, isImportableFile } from '../utils/import'
import { lastNotePath, setLastNoteId } from '../utils/lastNote'
import { findNextNoteId } from '../utils/nextNote'
import { saveRagReference } from '../utils/ragReference'
import { useNotificationStore } from '../stores/notification'
import type { NotebookVO } from '../api/notebook'
import type { TagVO } from '../api/tag'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const aiAssistantVisible = ref(false)
const isCollapse = ref(true)
const notebooks = ref<NotebookVO[]>([])
// 侧栏选中的笔记本用于展开列表、新建和导入；不能作为 AI 的检索范围。
const currentNotebookId = ref<number | null>(null)
// AI 范围必须绑定“当前路由中的笔记 ID + 该笔记真实所属笔记本”。
const activeNoteNotebookContext = ref<{ noteId: number; notebookId: number | null } | null>(null)
const notificationStore = useNotificationStore()
const activeMenu = computed(() => {
  if (route.path.startsWith('/calendar')) return 'calendar'
  if (route.path.startsWith('/trash')) return 'trash'
  return 'notes'
})
const isEditorRoute = computed(() => route.name === 'NoteEdit')
const pageTitle = computed(() => {
  const names: Record<string, string> = {
    NoteEdit: '专注书写',
    NoteEmpty: '笔记库',
    Calendar: '日历视图',
    Trash: '回收站',
    Notification: '通知'
  }
  return names[String(route.name)] || '云笔记'
})
const userInitial = computed(() => userStore.userInfo?.username?.slice(0, 1).toUpperCase() || 'U')
const sidebarKeyword = ref('')
const tags = ref<TagVO[]>([])
const activeTagId = ref<number | null | undefined>(null)
const searchResults = ref<NoteListItem[]>([])
const searchLoading = ref(false)
const searchTotal = ref(0)
const searchPage = ref(1)
const PAGE_SIZE = 30
const isFiltering = computed(() => !!sidebarKeyword.value.trim() || typeof activeTagId.value === 'number')
let sidebarSearchTimer: ReturnType<typeof setTimeout> | null = null
let searchRequestId = 0

function toggleNoteLibrary() {
  isCollapse.value = !isCollapse.value
}

function openNoteFromAi(source: RagSource) {
  const ragRef = saveRagReference(source)
  // 引用来源暂未携带 notebookId，等待 NoteEdit 加载详情后回填，避免沿用旧范围。
  activeNoteNotebookContext.value = null
  void router.push({
    path: `/notes/${source.noteId}`,
    query: ragRef ? { ragRef } : undefined
  })
}

function openNotes() {
  const target = lastNotePath()
  if (route.path !== target) router.push(target)
}

function openNoteLibrary() {
  isCollapse.value = false
}

provide('openNoteLibrary', openNoteLibrary)
provide('setActiveNoteNotebookId', (noteId: number, notebookId: number | null) => {
  activeNoteNotebookContext.value = { noteId, notebookId }
  if (notebookId) currentNotebookId.value = notebookId
})

function handleSidebarSearch() {
  if (sidebarSearchTimer) clearTimeout(sidebarSearchTimer)
  const keyword = sidebarKeyword.value.trim()
  if (!keyword && typeof activeTagId.value !== 'number') {
    searchResults.value = []
    searchTotal.value = 0
    searchLoading.value = false
    return
  }
  searchLoading.value = true
  sidebarSearchTimer = setTimeout(() => loadFilteredNotes(true), 280)
}

function clearSidebarSearch() {
  if (sidebarSearchTimer) clearTimeout(sidebarSearchTimer)
  searchResults.value = []
  searchTotal.value = 0
  searchLoading.value = false
  if (typeof activeTagId.value === 'number') loadFilteredNotes(true)
}

function clearAllFilters() {
  if (sidebarSearchTimer) {
    clearTimeout(sidebarSearchTimer)
    sidebarSearchTimer = null
  }
  searchRequestId += 1
  sidebarKeyword.value = ''
  activeTagId.value = null
  searchResults.value = []
  searchTotal.value = 0
  searchPage.value = 1
  searchLoading.value = false
}

function handleTagFilterChange() {
  if (sidebarSearchTimer) clearTimeout(sidebarSearchTimer)
  if (isFiltering.value) loadFilteredNotes(true)
  else {
    searchResults.value = []
    searchTotal.value = 0
  }
}

async function loadFilteredNotes(reset = false) {
  if (!isFiltering.value) return
  const keyword = sidebarKeyword.value.trim()
  const tagId = typeof activeTagId.value === 'number' ? activeTagId.value : null
  const page = reset ? 1 : searchPage.value + 1
  const signature = `${keyword}:${tagId ?? ''}`
  const requestId = ++searchRequestId
  searchLoading.value = true
  try {
    const result = await getNoteList({ keyword: keyword || undefined, tagId: tagId || undefined, page, pageSize: PAGE_SIZE })
    if (signature !== `${sidebarKeyword.value.trim()}:${activeTagId.value ?? ''}`) return
    searchResults.value = reset ? result.records : [...searchResults.value, ...result.records]
    searchTotal.value = result.total
    searchPage.value = page
  } catch {
    if (reset) {
      searchResults.value = []
      searchTotal.value = 0
    }
  } finally {
    if (requestId === searchRequestId) searchLoading.value = false
  }
}

function loadMoreSearch() {
  if (searchLoading.value) return
  loadFilteredNotes(false)
}

// 笔记本展开状态与子笔记列表（懒加载缓存）
const expandedIds = ref<number[]>([])
let notebookSelectionRequest = 0
const notebookNotes = reactive<Record<number, NoteListItem[]>>({})
const notebookTotals = reactive<Record<number, number>>({})
const notebookPages = reactive<Record<number, number>>({})
const notebookLoading = reactive<Record<number, boolean>>({})
const activeNoteId = computed(() => {
  const id = Number(route.params.id)
  return Number.isNaN(id) ? null : id
})
const activeNotebookId = computed(() =>
  isEditorRoute.value
    && activeNoteId.value
    && activeNoteNotebookContext.value?.noteId === activeNoteId.value
    ? activeNoteNotebookContext.value.notebookId
    : null
)

// 笔记本右键菜单
const notebookMenu = reactive<{ visible: boolean; x: number; y: number; data: NotebookVO | null }>({
  visible: false, x: 0, y: 0, data: null
})
const noteMenu = reactive<{ visible: boolean; x: number; y: number; data: NoteListItem | null }>({
  visible: false, x: 0, y: 0, data: null
})

function closeMenus() {
  notebookMenu.visible = false
  noteMenu.visible = false
}

function openNoteMenu(e: MouseEvent, note: NoteListItem) {
  closeMenus()
  noteMenu.visible = true
  noteMenu.x = e.clientX
  noteMenu.y = e.clientY
  noteMenu.data = note
}

function openNotebookMenu(e: MouseEvent, nb: NotebookVO) {
  closeMenus()
  notebookMenu.visible = true
  notebookMenu.x = e.clientX
  notebookMenu.y = e.clientY
  notebookMenu.data = nb
}

// 加载笔记本分类
async function loadSidebar() {
  const [notebookList, tagList] = await Promise.all([getNotebookList(), getTagList()])
  notebooks.value = notebookList
  tags.value = tagList
}

// 笔记本展开/收起（点击笔记本即切换，并记为“当前笔记本”供新建笔记使用）
function isExpanded(id: number) {
  return expandedIds.value.includes(id)
}

async function loadNotebookNotes(id: number, reset = true): Promise<boolean> {
  if (notebookLoading[id]) return false
  const page = reset ? 1 : (notebookPages[id] || 1) + 1
  notebookLoading[id] = true
  try {
    const result = await getNoteList({ notebookId: id, page, pageSize: PAGE_SIZE })
    notebookNotes[id] = reset ? result.records : [...(notebookNotes[id] || []), ...result.records]
    notebookTotals[id] = result.total
    notebookPages[id] = page
    return true
  } catch {
    if (reset) notebookNotes[id] = []
    return false
  } finally {
    notebookLoading[id] = false
  }
}

function loadMoreNotebook(id: number) {
  loadNotebookNotes(id, false)
}

async function toggleNotebook(id: number) {
  closeMenus()
  const selectionRequest = ++notebookSelectionRequest
  currentNotebookId.value = id
  const idx = expandedIds.value.indexOf(id)
  if (idx >= 0) {
    expandedIds.value.splice(idx, 1)
    return
  }

  // 选择笔记本时同步切换编辑器内容。此前这里只展开侧栏，路由仍停留在
  // 上一个笔记，导致用户看到“默认笔记本”高亮但编辑器显示其他笔记的内容。
  expandedIds.value = [id]
  const loaded = await loadNotebookNotes(id)
  // 用户在请求返回前又选择了其他笔记本时，丢弃这次过期结果，避免异步响应
  // 把编辑器切回旧笔记本。
  if (!loaded || selectionRequest !== notebookSelectionRequest) return

  // 如果当前笔记本来就属于刚选中的笔记本，只需保持当前编辑位置。
  const activeNoteBelongsToNotebook = activeNoteId.value != null
    && activeNoteNotebookContext.value?.noteId === activeNoteId.value
    && activeNoteNotebookContext.value.notebookId === id
  if (activeNoteBelongsToNotebook) return

  const notes = notebookNotes[id] || []
  if (notes.length > 0) {
    // 进入笔记本后打开列表中的第一篇笔记，保证编辑器与侧栏上下文一致。
    openNote(notes[0].id, id)
  } else {
    // 查看空笔记本不会隐式创建笔记；用户可通过“写一篇”主动创建。
    activeNoteNotebookContext.value = null
    router.push('/notes/empty')
  }
}

function openNote(id: number, notebookId?: number) {
  if (notebookId) {
    currentNotebookId.value = notebookId
    activeNoteNotebookContext.value = { noteId: id, notebookId }
  } else {
    activeNoteNotebookContext.value = null
  }
  router.push(`/notes/${id}`)
  if (window.innerWidth <= 760) isCollapse.value = true
}

async function refreshVisibleNotes() {
  await loadSidebar()
  await Promise.all(expandedIds.value.map(id => loadNotebookNotes(id, true)))
  if (isFiltering.value) await loadFilteredNotes(true)
}

async function handleTogglePin() {
  const note = noteMenu.data
  closeMenus()
  if (!note) return
  await pinNote(note.id, note.pinned !== 1)
  await refreshVisibleNotes()
}

async function handleRenameNote() {
  const note = noteMenu.data
  closeMenus()
  if (!note) return
  try {
    const { value } = await ElMessageBox.prompt('请输入新标题', '重命名笔记', {
      inputValue: note.title,
      inputPattern: /\S+/,
      inputErrorMessage: '标题不能为空'
    })
    const nextTitle = value?.trim()
    if (!nextTitle) return
    await renameNote(note.id, nextTitle)
    window.dispatchEvent(new CustomEvent('note-renamed', { detail: { id: note.id, title: nextTitle } }))
    await refreshVisibleNotes()
  } catch { /* 取消 */ }
}

async function handleDeleteNote() {
  const note = noteMenu.data
  closeMenus()
  if (!note) return
  try {
    await ElMessageBox.confirm('笔记将移入回收站，确定删除吗？', '删除笔记', { type: 'warning' })
  } catch { return }
  const deletingActiveNote = activeNoteId.value === note.id
  if (deletingActiveNote) {
    window.dispatchEvent(new CustomEvent('note-deleting', { detail: { id: note.id } }))
  }
  try {
    await deleteNote(note.id)
  } catch {
    if (deletingActiveNote) {
      window.dispatchEvent(new CustomEvent('note-delete-cancelled', { detail: { id: note.id } }))
    }
    return
  }
  if (deletingActiveNote && activeNoteId.value === note.id) {
    const nextId = await findNextNoteId(note.id, note.notebookId)
    if (nextId !== null) {
      setLastNoteId(nextId)
      await router.replace(`/notes/${nextId}`)
    } else {
      localStorage.removeItem('lastNoteId')
      activeNoteNotebookContext.value = null
      await router.replace('/notes/empty')
    }
  }
  ElMessage.success('已移入回收站')
  await refreshVisibleNotes()
}

// 新建笔记本
async function handleAddNotebook() {
  try {
    const { value } = await ElMessageBox.prompt('请输入笔记本名称', '新建笔记本', {
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空'
    })
    if (value) {
      await createNotebook({ name: value.trim() })
      ElMessage.success('创建成功')
      loadSidebar()
    }
  } catch { /* 取消 */ }
}

// 重命名笔记本
async function handleRenameNotebook() {
  closeMenus()
  const nb = notebookMenu.data
  if (!nb || nb.isDefault) return
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名笔记本', {
      inputValue: nb.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空'
    })
    if (value) {
      await renameNotebook(nb.id, { name: value.trim() })
      ElMessage.success('重命名成功')
      loadSidebar()
    }
  } catch { /* 取消 */ }
}

// 删除笔记本
async function handleDeleteNotebook() {
  closeMenus()
  const nb = notebookMenu.data
  if (!nb || nb.isDefault) return
  try {
    await ElMessageBox.confirm(
      `删除笔记本"${nb.name}"后，其中所有笔记将移入回收站。确定删除？`,
      '删除笔记本',
      { type: 'warning' }
    )
  } catch { return }

  await deleteNotebook(nb.id)
  ElMessage.success('笔记本已删除')
  currentNotebookId.value = null
  const idx = expandedIds.value.indexOf(nb.id)
  if (idx >= 0) expandedIds.value.splice(idx, 1)
  delete notebookNotes[nb.id]
  loadSidebar()
}

// 新建笔记
function handleNewNote() {
  // 若当前选中了某个笔记本，则新笔记默认创建到该笔记本
  router.push({
    path: '/notes/new',
    query: currentNotebookId.value ? { notebookId: currentNotebookId.value } : {}
  })
}

// 导入笔记（.md / .txt）
const importInputRef = ref<HTMLInputElement>()

function triggerImport() {
  importInputRef.value?.click()
}

async function handleImportFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = '' // 清空，允许下次重复选择同一文件
  if (files.length === 0) return

  let imported = 0
  for (const file of files) {
    if (!isImportableFile(file.name)) {
      ElMessage.warning(`已跳过「${file.name}」：仅支持 .md / .txt`)
      continue
    }
    try {
      const text = await file.text()
      await createNote({
        notebookId: currentNotebookId.value || undefined,
        title: filenameToTitle(file.name),
        content: textToHtml(file.name, text)
      })
      imported++
    } catch {
      ElMessage.error(`导入「${file.name}」失败`)
    }
  }
  if (imported > 0) {
    ElMessage.success(`已导入 ${imported} 篇笔记`)
    loadSidebar()
  }
}

// 右键笔记本 → 在该笔记本下新建笔记
function handleNewNoteInNotebook() {
  closeMenus()
  const nb = notebookMenu.data
  if (!nb) return
  router.push({ path: '/notes/new', query: { notebookId: nb.id } })
}

// 退出
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '提示')
  } catch {
    return
  }
  await userStore.logout()
}

// 修改密码
const changePwdVisible = ref(false)
const changePwdFormRef = ref<FormInstance>()
const changeSending = ref(false)
const changeLoading = ref(false)
const changeCountdown = ref(0)
const changePwdForm = reactive({ code: '', newPassword: '', confirmPassword: '' })
let changeTimer: ReturnType<typeof setInterval> | null = null
let unreadTimer: ReturnType<typeof setInterval> | null = null

const validateChangeConfirm = (_rule: any, value: string, callback: any) => {
  if (value !== changePwdForm.newPassword) {
    callback(new Error('两次密码不一致'))
  } else {
    callback()
  }
}

const changePwdRules: FormRules = {
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码 6~20 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateChangeConfirm, trigger: 'blur' }
  ]
}

function openChangePassword() {
  changePwdForm.code = ''
  changePwdForm.newPassword = ''
  changePwdForm.confirmPassword = ''
  changePwdVisible.value = true
}

async function handleSendChangeCode() {
  changeSending.value = true
  try {
    await sendChangePasswordCode()
    ElMessage.success('验证码已发送，请查收邮件')
    changeCountdown.value = 60
    changeTimer = setInterval(() => {
      changeCountdown.value -= 1
      if (changeCountdown.value <= 0) {
        clearInterval(changeTimer!)
        changeTimer = null
      }
    }, 1000)
  } catch {
    // 错误已在拦截器处理
  } finally {
    changeSending.value = false
  }
}

async function handleChangePassword() {
  const valid = await changePwdFormRef.value?.validate().catch(() => false)
  if (!valid) return
  changeLoading.value = true
  try {
    await changePassword(changePwdForm.code, changePwdForm.newPassword)
    ElMessage.success('密码修改成功')
    changePwdVisible.value = false
  } catch {
    // 错误已在拦截器处理
  } finally {
    changeLoading.value = false
  }
}

onMounted(() => {
  loadSidebar()
  notificationStore.refreshUnreadCount()
  // 每 60 秒拉取未读数
  unreadTimer = setInterval(() => notificationStore.refreshUnreadCount(), 60000)
})

onUnmounted(() => {
  if (changeTimer) clearInterval(changeTimer)
  if (unreadTimer) clearInterval(unreadTimer)
  if (sidebarSearchTimer) clearTimeout(sidebarSearchTimer)
})

// 路由变化时刷新侧边栏计数与已展开笔记本的子笔记（新建/删除/移动后保持同步）
watch(
  () => route.path,
  () => {
    loadSidebar()
    for (const id of expandedIds.value) loadNotebookNotes(id)
  }
)
</script>

<style scoped>
.layout {
  height: 100vh;
  background: #f7f8f6;
}
.workspace-shell {
  min-width: 0;
  transition: margin-right 0.24s ease;
}
.workspace-shell--ai {
  margin-right: 430px;
}
.aside {
  background: #fbfcfb;
  border-right: 1px solid #e7eae7;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  transition: width 0.24s ease;
}
.rail-column {
  width: 72px;
  flex: 0 0 72px;
  display: flex;
  flex-direction: column;
  background: #fbfcfb;
}
.aside-header {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 64px;
  padding: 0;
}
.brand-mark {
  appearance: none;
  border: 0;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: #247b68;
  color: #fff;
  box-shadow: 0 7px 18px rgba(36, 123, 104, 0.18);
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.brand-mark:hover {
  transform: translateY(-1px);
  box-shadow: 0 9px 20px rgba(36, 123, 104, 0.22);
}
.rail-nav {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 8px 0;
}
.rail-nav-button {
  appearance: none;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  display: grid;
  place-items: center;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: #58625b;
  cursor: pointer;
  transition: color 0.18s ease, background-color 0.18s ease;
}
.rail-nav-button:hover {
  background: #f0f4f1;
  color: #247b68;
}
.rail-nav-button.active {
  background: #eaf3f0;
  color: #247b68;
}
.rail-nav-button :deep(.el-icon) {
  width: 20px;
  height: 20px;
  margin: 0;
  font-size: 20px;
}
.aside-menu {
  border-right: none;
  background: transparent;
  padding: 8px 14px 2px;
  --el-menu-item-height: 44px;
  --el-menu-hover-bg-color: #f0f4f1;
  --el-menu-active-color: #247b68;
}
.aside-menu.el-menu--collapse {
  width: auto;
}
.aside-menu.el-menu--collapse :deep(.el-menu-item) {
  width: 44px;
  margin: 0 auto 4px;
  border-radius: 12px;
  justify-content: center;
  padding: 0 !important;
}
.aside-menu :deep(.el-menu-item) {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 !important;
}
.aside-menu :deep(.el-menu-item .el-icon) {
  width: 20px;
  margin: 0 !important;
  flex: 0 0 20px;
}
.aside-menu :deep(.el-menu-item.is-active) {
  background: #eaf3f0;
}
.section-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 18px 14px 7px;
  font-size: 12px;
  color: #929992;
  letter-spacing: 0.08em;
}
.filter-result-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  letter-spacing: normal;
}
.filter-result-actions :deep(.el-button) {
  height: 26px;
  padding: 0 4px;
  color: #247b68;
}
.library-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 14px 10px;
}
.library-panel {
  width: 282px;
  min-width: 0;
  overflow-y: auto;
  border-left: 1px solid #e7eae7;
  background: #f8faf8;
}
.library-heading strong {
  color: #303832;
  font-size: 15px;
  font-weight: 600;
}
.sidebar-search {
  width: calc(100% - 24px);
  margin: 0 12px 8px;
}
.sidebar-tag-filter {
  width: calc(100% - 24px);
  margin: 0 12px 4px;
}
.sidebar-tag-filter :deep(.el-select__wrapper) {
  min-height: 36px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 0 0 1px #e4e8e4 inset;
}
.sidebar-search :deep(.el-input__wrapper) {
  min-height: 38px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 0 0 1px #e4e8e4 inset;
}
.search-result-section {
  padding-bottom: 16px;
}
.search-loading,
.search-empty {
  padding: 24px 14px;
  color: #929992;
  font-size: 13px;
  text-align: center;
}
.search-note-list {
  padding: 2px 8px;
}
.search-note {
  min-height: 42px;
  padding-left: 10px;
}
.search-notebook {
  flex: 0 0 auto;
  max-width: 72px;
  overflow: hidden;
  color: #a1a8a2;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notebook-list,
.tag-list {
  padding: 4px 8px;
}
.notebook-item,
.tag-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 9px;
  cursor: pointer;
  font-size: 14px;
  color: #4c554f;
  user-select: none;
}
.notebook-item:hover,
.tag-item:hover {
  background: #f0f4f1;
}
.notebook-item.active,
.tag-item.active {
  background: #eaf3f0;
  color: #247b68;
}
.notebook-item .count {
  margin-left: auto;
  font-size: 12px;
  color: #999;
}
.notebook-group {
  margin-bottom: 2px;
}
.notebook-item .expand-btn {
  flex-shrink: 0;
  color: #909399;
  cursor: pointer;
}
.notebook-item .expand-btn:hover {
  color: #247b68;
}
.note-children {
  padding: 2px 8px 4px;
}
.note-child {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 34px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: #555;
  user-select: none;
}
.note-child:hover {
  background: #f0f4f1;
}
.note-child.active {
  background: #eaf3f0;
  color: #247b68;
}
.note-child .pin-icon {
  color: #247b68;
}
.load-more {
  display: flex;
  width: calc(100% - 16px);
  margin: 4px 8px;
  color: #78827b;
}
.note-child .child-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.note-child.empty {
  color: #c0c4cc;
  cursor: default;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid #e7eae7;
  padding: 0 24px 0 28px;
  height: 64px;
  backdrop-filter: blur(14px);
}
.header-left,
.header-right {
  display: flex;
  align-items: center;
}
.page-context {
  display: flex;
  align-items: baseline;
  gap: 10px;
  white-space: nowrap;
}
.workspace-name {
  color: #949b95;
  font-size: 13px;
}
.page-context strong {
  color: #28302b;
  font-size: 15px;
  font-weight: 600;
}
.header-right {
  gap: 8px;
}
.ai-assistant-button {
  border-color: #cfe0da;
  color: #247b68;
  background: #f2f8f6;
}
.ai-assistant-button:hover,
.ai-assistant-button.active {
  border-color: #8fbbaf;
  color: #1d6757;
  background: #e4f1ed;
}
.new-note-button {
  border: none;
  border-radius: 10px;
  padding-inline: 15px;
  box-shadow: 0 6px 14px rgba(36, 123, 104, 0.16);
}
.quiet-action,
.icon-action {
  color: #667069;
}
.hidden-input {
  display: none;
}
.notice-badge {
  margin: 0 3px;
}
.user-info {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px 4px 4px;
  border-radius: 999px;
  color: #4f5852;
  outline: none;
}
.user-info:hover {
  background: #f1f4f1;
}
.user-avatar {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #e1eee9;
  color: #247b68;
  font-size: 13px;
  font-weight: 600;
}
.user-name {
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-arrow {
  color: #9aa19b;
}
.main {
  background: #f7f8f6;
  padding: 28px clamp(20px, 3vw, 44px);
}
.main--editor {
  padding: 0;
  background: #fff;
}

/* 右键菜单 */
.context-menu {
  position: fixed;
  z-index: 9999;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 12px;
  box-shadow: 0 14px 38px rgba(33, 44, 37, 0.14);
  padding: 6px;
  min-width: 150px;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
.menu-item:hover {
  background: #f0f4f1;
}
.menu-item.danger {
  color: #f56c6c;
}
.menu-item.danger:hover {
  background: #fef0f0;
}
.change-code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.change-code-row .el-input {
  flex: 1;
}
.change-code-row .el-button {
  flex-shrink: 0;
  white-space: nowrap;
}
.menu-mask {
  position: fixed;
  inset: 0;
  z-index: 9998;
}

@media (max-width: 1180px) {
  .workspace-shell--ai { margin-right: 0; }
}

@media (max-width: 760px) {
  .aside {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 20;
    width: min(340px, 92vw) !important;
    box-shadow: 16px 0 34px rgba(31, 43, 36, 0.12);
  }
  .aside.aside--collapsed {
    width: 58px !important;
    box-shadow: none;
  }
  .rail-column {
    width: 58px;
    flex-basis: 58px;
  }
  .layout > :deep(.el-container) {
    min-width: 0;
    margin-left: 58px;
  }
  .aside-header { padding: 0; }
  .brand-mark { width: 36px; }
  .aside--collapsed .notebook-section { display: none !important; }
  .aside-menu { padding-inline: 7px; }
  .aside-menu :deep(.el-menu-item) {
    width: 44px;
    margin: 0 auto 4px;
    padding: 0 !important;
    justify-content: center;
    border-radius: 12px;
  }
  .header { padding: 0 12px 0 16px; }
  .workspace-name,
  .quiet-action,
  .user-name,
  .user-arrow { display: none; }
  .ai-assistant-button {
    width: 38px;
    padding: 0;
  }
  .ai-assistant-button :deep(span) { display: none; }
  .main { padding: 18px 14px; }
  .main--editor { padding: 0; }
}

@media (max-width: 520px) {
  .page-context,
  .quiet-action,
  .user-info { display: none; }
  .header-left { min-width: 0; }
  .header-right { width: 100%; justify-content: flex-end; }
  .new-note-button {
    width: 38px;
    padding: 0;
  }
  .new-note-button :deep(span) { display: none; }
}
</style>
