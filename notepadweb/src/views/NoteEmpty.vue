<template>
  <main class="empty-notes">
    <div class="empty-content">
      <h1>还没有打开的笔记</h1>
      <p>可以从左侧选择一篇笔记，或点击右上角“写一篇”创建新笔记。</p>
    </div>
  </main>
</template>

<style scoped>
.empty-notes {
  display: grid;
  min-height: calc(100vh - 64px);
  place-items: center;
  color: #2f4140;
}
.empty-content {
  padding: 24px;
  text-align: center;
}
h1 {
  margin: 0 0 12px;
  font-size: 22px;
}
p {
  margin: 0;
  color: #7a8987;
}
</style>
