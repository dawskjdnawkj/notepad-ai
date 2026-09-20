import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false, // 不渲染源文本里的 HTML，避免导入内容注入脚本
  linkify: true,
  breaks: true
})

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 去掉扩展名，作为导入笔记的标题 */
export function filenameToTitle(filename: string): string {
  const name = filename.replace(/\.[^.]+$/, '')
  return name.trim() || '无标题'
}

/** 根据扩展名把文件文本转为富文本 HTML（.md 走 markdown，其余按纯文本） */
export function textToHtml(filename: string, text: string): string {
  const ext = (filename.toLowerCase().split('.').pop() || '').trim()
  if (ext === 'md') {
    return md.render(text)
  }
  return escapeHtml(text).replace(/\r\n|\r|\n/g, '<br>')
}

/** 判断是否为受支持的导入类型（.md / .txt） */
export function isImportableFile(name: string): boolean {
  const ext = (name.toLowerCase().split('.').pop() || '').trim()
  return ext === 'md' || ext === 'txt'
}
