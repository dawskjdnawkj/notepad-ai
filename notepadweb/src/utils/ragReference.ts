import type { RagSource } from '../api/ai'

const STORAGE_PREFIX = 'ragReference:'

interface StoredRagReference {
  source: RagSource
  createdAt: number
}

export function saveRagReference(source: RagSource): string | null {
  const id = `${Date.now().toString(36)}-${crypto.randomUUID()}`
  const value: StoredRagReference = {
    source: {
      noteId: source.noteId,
      title: source.title,
      content: source.content,
      score: source.score
    },
    createdAt: Date.now()
  }
  try {
    sessionStorage.setItem(STORAGE_PREFIX + id, JSON.stringify(value))
    return id
  } catch {
    return null
  }
}

export function loadRagReference(id: string): RagSource | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_PREFIX + id)
    if (!raw) return null
    const value = JSON.parse(raw) as StoredRagReference
    if (!value.source || typeof value.source.noteId !== 'number' || typeof value.source.content !== 'string') {
      return null
    }
    return value.source
  } catch {
    return null
  }
}
