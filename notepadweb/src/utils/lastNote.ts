const KEY = 'lastNoteId'

/** 记录最近打开/编辑的笔记 id */
export function setLastNoteId(id: number) {
  localStorage.setItem(KEY, String(id))
}

/** 读取最近打开的笔记 id（无则返回 null） */
export function getLastNoteId(): number | null {
  const v = localStorage.getItem(KEY)
  if (!v) return null
  const n = Number(v)
  return Number.isNaN(n) ? null : n
}

/** 进入笔记区时的默认路径：优先上次打开的笔记，否则显示空状态。 */
export function lastNotePath(): string {
  const id = getLastNoteId()
  return id ? `/notes/${id}` : '/notes/empty'
}
