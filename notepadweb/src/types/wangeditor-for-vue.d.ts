// @wangeditor/editor-for-vue 的 package.json 中 exports 字段缺少 types 条件，
// 导致 TypeScript 无法解析其类型声明。这里手动声明，避免 TS7016。
declare module '@wangeditor/editor-for-vue' {
  const Editor: any
  const Toolbar: any
  export { Editor, Toolbar }
}
