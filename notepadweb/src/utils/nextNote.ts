import { getNoteList } from '../api/note'

/** 删除当前笔记后，优先找同一笔记本里的另一篇，再找其他笔记。 */
export async function findNextNoteId(deletedId: number, notebookId: number | null): Promise<number | null> {
  if (notebookId !== null) {
    try {
      const nearby = await getNoteList({ notebookId, page: 1, pageSize: 10 })
      const next = nearby.records.find(note => note.id !== deletedId)
      if (next) return next.id
    } catch {
      // 当前笔记本查询失败时，仍尝试从其他笔记本选择一篇。
    }
  }
  try {
    const all = await getNoteList({ page: 1, pageSize: 10 })
    return all.records.find(note => note.id !== deletedId)?.id ?? null
  } catch {
    // 查询失败时保留在空状态，避免误建新笔记或跳回已删除的笔记。
    return null
  }
}
