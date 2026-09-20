<template>
  <router-view />
</template>

<style>
:root {
  font-family: Inter, "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, sans-serif;
  color: #202421;
  background: #f7f8f6;
  font-synthesis: none;
  --el-color-primary: #247b68;
  --el-color-primary-light-3: #5b9d8d;
  --el-color-primary-light-5: #88b7ab;
  --el-color-primary-light-7: #b8d3cc;
  --el-color-primary-light-8: #d0e2dd;
  --el-color-primary-light-9: #e8f1ee;
  --el-color-primary-dark-2: #1b6555;
  --el-border-radius-base: 10px;
  --el-border-color: #e4e8e4;
  --el-border-color-light: #edf0ed;
  --el-fill-color-light: #f4f6f4;
  --el-text-color-primary: #202421;
  --el-text-color-regular: #555d58;
}

html,
body,
#app {
  width: 100%;
  min-width: 320px;
  height: 100%;
  margin: 0;
}

body {
  overflow: hidden;
  background: #f7f8f6;
}

button,
input,
textarea,
select {
  font: inherit;
}

* {
  box-sizing: border-box;
}
</style>
