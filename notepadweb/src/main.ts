import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElAlert,
  ElAside,
  ElBadge,
  ElButton,
  ElButtonGroup,
  ElCalendar,
  ElContainer,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElHeader,
  ElIcon,
  ElInput,
  ElInputNumber,
  ElMain,
  ElOption,
  ElPagination,
  ElPopover,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElSkeleton,
  ElSwitch,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTooltip
} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)

const elementComponents = [
  ElAlert, ElAside, ElBadge, ElButton, ElButtonGroup, ElCalendar, ElContainer,
  ElDialog, ElDropdown, ElDropdownItem, ElDropdownMenu, ElEmpty, ElForm,
  ElFormItem, ElHeader, ElIcon, ElInput, ElInputNumber, ElMain, ElOption,
  ElPagination, ElPopover, ElRadio, ElRadioGroup, ElSelect, ElSkeleton,
  ElSwitch, ElTabPane, ElTabs, ElTag, ElTooltip
]
elementComponents.forEach(component => app.use(component))

app.mount('#app')
