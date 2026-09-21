import request from './request'
import type { NoteDetail } from './note'
import { useUserStore } from '../stores/user'

/**
 * SSE 走原生 fetch，不经过 axios 拦截器，401 的统一处理得自己做。
 * 只删 localStorage 不够：isLoggedIn() 看的是 Pinia，路由守卫判定"已登录用户
 * 访问登录页"，会把 /login 原路弹回笔记页，用户永远到不了登录页。
 */
function handleUnauthorized() {
  useUserStore().clearSession()
}

export interface RagSource {
  noteId: number
  title: string
  content: string
  score: number
}

export interface ConversationMessage {
  role: 'user' | 'assistant'
  content: string
}

export type AnswerScopeType = 'all' | 'notebook' | 'note'
export type RagEvalMatchMode = 'any' | 'all'
export type FeedbackRating = 'helpful' | 'unhelpful'
export type FeedbackReason =
  | 'irrelevant_sources'
  | 'incomplete'
  | 'inconsistent'
  | 'wrong_scope'
  | 'other'

export interface AiAnswerFeedback {
  rating: FeedbackRating
  reason: FeedbackReason | null
  comment: string | null
  evalCaseId: number | null
  updatedAt: string
}

export interface AiAnswerFeedbackPayload {
  rating: FeedbackRating
  reason?: FeedbackReason | null
  comment?: string | null
}

export interface AiFeedbackReasonCount {
  reason: FeedbackReason
  count: number
  rate: number
}

export interface AiFeedbackFailureQuestion {
  question: string
  count: number
  convertedCaseCount: number
  lastFeedbackAt: string | null
  reasonCounts: AiFeedbackReasonCount[]
  // 该问题转换出的用例在最近一次回归报告里的状态；用于把反馈和回归测试报告关联起来
  evalStatus: 'passed' | 'failed' | 'not_run'
  lastEvalAt: string | null
  evalFailureReason: string | null
}

export interface AiFeedbackStatistics {
  totalCount: number
  helpfulCount: number
  unhelpfulCount: number
  helpfulRate: number
  convertedCaseCount: number
  conversionRate: number
  reasonCounts: AiFeedbackReasonCount[]
  commonFailureQuestions: AiFeedbackFailureQuestion[]
}

export interface StoredConversationMessage {
  role: 'user' | 'assistant'
  content: string
  question: string | null
  sources: RagSource[]
  clientMessageId: string
  scopeType: AnswerScopeType | null
  scopeId: number | null
  feedback: AiAnswerFeedback | null
}

export interface AiConversationSummary {
  clientId: string
  title: string
  turnCount: number
  updatedAt: string
}

export interface AiConversationDetail {
  clientId: string
  title: string
  updatedAt: string
  messages: StoredConversationMessage[]
}

export interface AiConversationSavePayload {
  title: string
  messages: StoredConversationMessage[]
}

export function getAiConversations(): Promise<AiConversationSummary[]> {
  return request.get('/ai/conversations')
}

export function getAiConversation(clientId: string): Promise<AiConversationDetail> {
  return request.get(`/ai/conversations/${encodeURIComponent(clientId)}`)
}

export function saveAiConversation(
  clientId: string,
  payload: AiConversationSavePayload
): Promise<AiConversationDetail> {
  return request.put(`/ai/conversations/${encodeURIComponent(clientId)}`, payload)
}

export function deleteAiConversation(clientId: string): Promise<null> {
  return request.delete(`/ai/conversations/${encodeURIComponent(clientId)}`)
}

export function saveAnswerFeedback(
  conversationClientId: string,
  messageClientId: string,
  payload: AiAnswerFeedbackPayload
): Promise<AiAnswerFeedback> {
  return request.put(
    `/ai/conversations/${encodeURIComponent(conversationClientId)}`
      + `/messages/${encodeURIComponent(messageClientId)}/feedback`,
    payload
  )
}

export function deleteAnswerFeedback(
  conversationClientId: string,
  messageClientId: string
): Promise<null> {
  return request.delete(
    `/ai/conversations/${encodeURIComponent(conversationClientId)}`
      + `/messages/${encodeURIComponent(messageClientId)}/feedback`
  )
}

export function getAiFeedbackStatistics(): Promise<AiFeedbackStatistics> {
  return request.get('/ai/feedback/statistics')
}

export interface RagEvalCasePayload {
  name: string
  question: string
  scopeType: AnswerScopeType
  scopeId: number | null
  expectAnswer: boolean
  expectedNoteIds: number[]
  matchMode: RagEvalMatchMode
  minScore: number
  enabled: boolean
}

export interface RagEvalCase extends RagEvalCasePayload {
  id: number
  updatedAt: string
}

export interface RagEvalSourceFeedback {
  feedbackId: number
  reason: string | null
  comment: string | null
  feedbackAt: string | null
}

export interface RagEvalResultItem {
  caseId: number
  name: string
  question: string
  expectAnswer: boolean
  expectedNoteIds: number[]
  matchedNoteIds: number[]
  topScore: number | null
  wrongReferenceCount: number
  durationMs: number
  passed: boolean
  failureReason: string | null
  retrievalTraces?: RagEvalRetrievalTrace[] | null
  // 由用户「没帮助」反馈转换而来的用例会带上来源；手工创建的用例为 null
  sourceFeedback?: RagEvalSourceFeedback | null
}

export interface RagEvalRetrievalCandidate {
  noteId: number
  title: string
  score: number | null
  selected: boolean
}

export interface RagEvalRetrievalTrace {
  query: string
  candidates: RagEvalRetrievalCandidate[]
}

export interface RagEvalRunSummary {
  id: number
  caseCount: number
  passedCount: number
  passRate: number
  hitRate: number
  rejectionAccuracy: number
  wrongReferenceCount: number
  averageDurationMs: number
  createdAt: string
}

export interface RagEvalRun extends RagEvalRunSummary {
  positiveCaseCount: number
  hitCount: number
  negativeCaseCount: number
  correctRejectionCount: number
  results: RagEvalResultItem[]
}

export function getRagEvalCases(): Promise<RagEvalCase[]> {
  return request.get('/ai/evaluations/cases')
}

export function createRagEvalCase(payload: RagEvalCasePayload): Promise<RagEvalCase> {
  return request.post('/ai/evaluations/cases', payload)
}

export function createRagEvalCaseFromFeedback(
  conversationClientId: string,
  messageClientId: string,
  payload: RagEvalCasePayload
): Promise<RagEvalCase> {
  return request.post(
    `/ai/evaluations/cases/from-feedback/${encodeURIComponent(conversationClientId)}`
      + `/${encodeURIComponent(messageClientId)}`,
    payload
  )
}

export function updateRagEvalCase(
  caseId: number,
  payload: RagEvalCasePayload
): Promise<RagEvalCase> {
  return request.put(`/ai/evaluations/cases/${caseId}`, payload)
}

export function deleteRagEvalCase(caseId: number): Promise<null> {
  return request.delete(`/ai/evaluations/cases/${caseId}`)
}

export function getRagEvalRuns(): Promise<RagEvalRunSummary[]> {
  return request.get('/ai/evaluations/runs')
}

export function getRagEvalRun(runId: number): Promise<RagEvalRun> {
  return request.get(`/ai/evaluations/runs/${runId}`)
}

export function runRagEvaluation(): Promise<RagEvalRun> {
  return request.post('/ai/evaluations/runs', undefined, { timeout: 600_000 })
}

export interface NoteIndexStatus {
  noteCount: number
  indexedNoteCount: number
  chunkCount: number
  missingNoteCount: number
  staleChunkCount: number
  storeFileExists: boolean
  storeFileBytes: number
  lastUpdatedAt: string | null
  state: 'HEALTHY' | 'EMPTY' | 'NEEDS_REBUILD' | 'ERROR'
  // 当前生效的向量存储；simple 为进程内实现，pgvector 走 PostgreSQL
  storeType: 'simple' | 'pgvector'
  // 仅 pgvector 有值（NONE / IVFFLAT / HNSW）；simple 不是索引型存储，为 null
  indexType: string | null
}

export interface NoteIndexRebuildResult {
  noteCount: number
  chunkCount: number
}

export function getNoteIndexStatus(): Promise<NoteIndexStatus> {
  return request.get('/ai/notes/index/status')
}

export function rebuildNoteIndex(): Promise<NoteIndexRebuildResult> {
  return request.post('/ai/notes/index/rebuild', undefined, { timeout: 600_000 })
}

export interface NoteIndexBackupItem {
  fileName: string
  fileBytes: number
  createdAt: string
  reason: 'manual' | 'pre-restore'
}

export interface NoteIndexBackupVerifyResult {
  fileName: string
  valid: boolean
  entryCount: number
  userCount: number
  issueMessage: string | null
}

export interface NoteIndexRestoreResult {
  fileName: string
  restoredEntryCount: number
  storeFileBytes: number
  snapshotFileName: string | null
  status: NoteIndexStatus
}

export function getNoteIndexBackups(): Promise<NoteIndexBackupItem[]> {
  return request.get('/ai/notes/index/backups')
}

export function createNoteIndexBackup(): Promise<NoteIndexBackupItem> {
  // 3MB 文件落盘并刷盘，本地通常不到一秒；给足余量以防磁盘较慢。
  return request.post('/ai/notes/index/backups', undefined, { timeout: 120_000 })
}

export function verifyNoteIndexBackup(fileName: string): Promise<NoteIndexBackupVerifyResult> {
  return request.get(`/ai/notes/index/backups/${encodeURIComponent(fileName)}/verify`, {
    timeout: 60_000
  })
}

export function restoreNoteIndexBackup(fileName: string): Promise<NoteIndexRestoreResult> {
  return request.post(
    `/ai/notes/index/backups/${encodeURIComponent(fileName)}/restore`,
    undefined,
    { timeout: 300_000 }
  )
}

export interface NoteAnswerStreamHandlers {
  onStatus?: (phase: string) => void
  onSources?: (sources: RagSource[]) => void
  onDelta?: (text: string) => void
  onDone?: () => void
}

export interface NoteAnswerScope {
  noteId?: number
  notebookId?: number
}

export class AiStreamHttpError extends Error {
  readonly status?: number
  readonly errorId?: string

  constructor(message: string, status?: number, errorId?: string) {
    super(message)
    this.name = 'AiStreamHttpError'
    this.status = status
    this.errorId = errorId
  }
}

interface StreamDelta {
  text?: string
}

interface StreamStatus {
  phase?: string
  requestId?: string
}

interface StreamError {
  message?: string
  errorId?: string
}

/**
 * 使用 fetch 读取 POST SSE。浏览器原生 EventSource 只支持 GET，
 * 无法满足当前接口的 JSON 请求体和 Authorization 请求头。
 */
export async function streamNoteAnswer(
  question: string,
  history: ConversationMessage[],
  scope: NoteAnswerScope,
  handlers: NoteAnswerStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const token = localStorage.getItem('token')
  const response = await fetch('/api/ai/notes/ask/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify({
      question,
      history,
      noteId: scope.noteId,
      notebookId: scope.notebookId
    }),
    signal
  })
  const responseRequestId = response.headers.get('X-Request-Id') || undefined

  if (!response.ok) {
    if (response.status === 401) handleUnauthorized()
    const errorBody = await readErrorResponse(response)
    throw new AiStreamHttpError(
      errorBody.message,
      response.status,
      errorBody.errorId || responseRequestId
    )
  }
  if (!response.body) {
    throw new AiStreamHttpError('当前浏览器无法读取流式响应', undefined, responseRequestId)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = 'message'
  let dataLines: string[] = []
  let receivedDone = false

  const dispatchEvent = () => {
    if (dataLines.length === 0) {
      eventName = 'message'
      return
    }

    const data = dataLines.join('\n')
    if (eventName === 'status') {
      const status = JSON.parse(data) as StreamStatus
      if (status.phase) handlers.onStatus?.(status.phase)
    } else if (eventName === 'sources') {
      handlers.onSources?.(JSON.parse(data) as RagSource[])
    } else if (eventName === 'delta') {
      const delta = JSON.parse(data) as StreamDelta
      if (delta.text) handlers.onDelta?.(delta.text)
    } else if (eventName === 'done') {
      receivedDone = true
      handlers.onDone?.()
    } else if (eventName === 'error') {
      const error = JSON.parse(data) as StreamError
      throw new AiStreamHttpError(error.message || 'AI 回答生成失败', undefined, error.errorId)
    }

    eventName = 'message'
    dataLines = []
  }

  const consumeLine = (rawLine: string) => {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line === '') {
      dispatchEvent()
      return
    }
    if (line.startsWith(':')) return

    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') eventName = value
    if (field === 'data') dataLines.push(value)
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })

    let newlineIndex = buffer.indexOf('\n')
    while (newlineIndex >= 0) {
      consumeLine(buffer.slice(0, newlineIndex))
      buffer = buffer.slice(newlineIndex + 1)
      newlineIndex = buffer.indexOf('\n')
    }

    if (done) break
  }

  if (buffer) consumeLine(buffer)
  if (dataLines.length > 0) dispatchEvent()
  if (!receivedDone && !signal?.aborted) {
    throw new AiStreamHttpError('AI 连接意外中断，请重试', undefined, responseRequestId)
  }
}

async function readErrorResponse(
  response: Response
): Promise<{ message: string; errorId?: string }> {
  const fallback = `请求失败（${response.status}）`
  try {
    const text = await response.text()
    if (!text) return { message: fallback }
    const body = JSON.parse(text) as { message?: string; errorId?: string; requestId?: string }
    return {
      message: body.message || fallback,
      errorId: body.errorId || body.requestId
    }
  } catch {
    return { message: fallback }
  }
}

// ---------------------------------------------------------------- AI 编辑笔记

export type AiNoteEditCapability = 'summarize' | 'rewrite' | 'continue' | 'todos'

export const AI_NOTE_EDIT_CAPABILITIES: { value: AiNoteEditCapability; label: string; hint: string }[] = [
  { value: 'summarize', label: '摘要', hint: '在正文开头插入一段摘要，原文保留' },
  { value: 'rewrite', label: '改写', hint: '用改写后的正文替换原文（可回退）' },
  { value: 'continue', label: '续写', hint: '在正文末尾追加续写内容' },
  { value: 'todos', label: '提取待办', hint: '从正文中提取待办，追加到末尾' }
]

export interface AiNoteEditDonePayload {
  contentHash: string
  capability: AiNoteEditCapability
}

export interface AiNoteEditStreamHandlers {
  onStatus?: (phase: string) => void
  onDelta?: (text: string) => void
  // contentHash 是生成预览时笔记正文的指纹，确认写回时要原样回传
  onDone?: (payload: AiNoteEditDonePayload) => void
}

export interface AiNoteEditApplyResult {
  revisionId: number
  note: NoteDetail
}

export interface AiNoteRevision {
  id: number
  capability: string
  createTime: string
  restored: boolean
  restoreTime: string | null
}

/**
 * 流式生成 AI 编辑预览。不写库 —— 写回必须走 applyNoteAiEdit 并带上这里拿到的 contentHash。
 *
 * 用 fetch 而不是 EventSource：EventSource 只支持 GET，而这里需要带 JSON 请求体和鉴权头。
 */
export async function streamNoteAiEdit(
  noteId: number,
  capability: AiNoteEditCapability,
  handlers: AiNoteEditStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const token = localStorage.getItem('token')
  const response = await fetch(`/api/ai/notes/${noteId}/ai-edit/preview/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify({ capability }),
    signal
  })
  const responseRequestId = response.headers.get('X-Request-Id') || undefined

  if (!response.ok) {
    if (response.status === 401) handleUnauthorized()
    const errorBody = await readErrorResponse(response)
    throw new AiStreamHttpError(
      errorBody.message,
      response.status,
      errorBody.errorId || responseRequestId
    )
  }
  if (!response.body) {
    throw new AiStreamHttpError('当前浏览器无法读取流式响应', undefined, responseRequestId)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = 'message'
  let dataLines: string[] = []
  let receivedDone = false

  const dispatchEvent = () => {
    if (dataLines.length === 0) {
      eventName = 'message'
      return
    }

    const data = dataLines.join('\n')
    if (eventName === 'status') {
      const status = JSON.parse(data) as StreamStatus
      if (status.phase) handlers.onStatus?.(status.phase)
    } else if (eventName === 'delta') {
      const delta = JSON.parse(data) as StreamDelta
      if (delta.text) handlers.onDelta?.(delta.text)
    } else if (eventName === 'done') {
      receivedDone = true
      const payload = JSON.parse(data) as AiNoteEditDonePayload
      handlers.onDone?.(payload)
    } else if (eventName === 'error') {
      const error = JSON.parse(data) as StreamError
      throw new AiStreamHttpError(error.message || 'AI 生成失败', undefined, error.errorId)
    }

    eventName = 'message'
    dataLines = []
  }

  const consumeLine = (rawLine: string) => {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line === '') {
      dispatchEvent()
      return
    }
    if (line.startsWith(':')) return

    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') eventName = value
    if (field === 'data') dataLines.push(value)
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })

    let newlineIndex = buffer.indexOf('\n')
    while (newlineIndex >= 0) {
      consumeLine(buffer.slice(0, newlineIndex))
      buffer = buffer.slice(newlineIndex + 1)
      newlineIndex = buffer.indexOf('\n')
    }

    if (done) break
  }

  if (buffer) consumeLine(buffer)
  if (dataLines.length > 0) dispatchEvent()
  if (!receivedDone && !signal?.aborted) {
    throw new AiStreamHttpError('AI 连接意外中断，请重试', undefined, responseRequestId)
  }
}

/** 用户确认后写回。contentHash 不匹配时后端返回 409，不会覆盖用户新改的内容。 */
export function applyNoteAiEdit(
  noteId: number,
  payload: { capability: AiNoteEditCapability; text: string; contentHash: string }
): Promise<AiNoteEditApplyResult> {
  return request.post(`/ai/notes/${noteId}/ai-edit/apply`, payload, { timeout: 60_000 })
}

export function getNoteAiRevisions(noteId: number): Promise<AiNoteRevision[]> {
  return request.get(`/ai/notes/${noteId}/ai-edit/revisions`)
}

export function restoreNoteAiRevision(noteId: number, revisionId: number): Promise<NoteDetail> {
  return request.post(`/ai/notes/${noteId}/ai-edit/revisions/${revisionId}/restore`, undefined, {
    timeout: 60_000
  })
}
