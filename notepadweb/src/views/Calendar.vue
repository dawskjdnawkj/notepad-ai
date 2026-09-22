<template>
  <div class="calendar-page">
    <div class="page-header">
      <h1>日历视图</h1>
    </div>

    <!-- 月份切换 -->
    <div class="month-header">
      <el-button-group>
        <el-button :icon="ArrowLeft" @click="prevMonth" />
        <el-button :icon="ArrowRight" @click="nextMonth" />
      </el-button-group>
      <span class="month-label">{{ currentMonth }}</span>
      <el-button @click="goToday">今天</el-button>
    </div>

    <!-- 日历 -->
    <el-calendar v-model="selectedDate">
      <template #date-cell="{ data }">
        <div
          class="calendar-cell"
          :class="{ marked: markedDates.has(data.day) }"
          @click="handleDateClick(data)"
        >
          {{ data.day.split('-')[2].replace(/^0/, '') }}
          <span v-if="markedDates.has(data.day)" class="dot"></span>
        </div>
      </template>
    </el-calendar>

    <!-- 当天笔记弹窗 -->
    <el-dialog v-model="dialogVisible" :title="`${selectedDay} 的笔记`" width="600px">
      <div v-if="dayNotes.length > 0" class="day-note-list">
        <div
          v-for="note in dayNotes"
          :key="note.id"
          class="day-note-item"
          @click="$router.push(`/notes/${note.id}`); dialogVisible=false"
        >
          <h4>{{ note.title || '无标题' }}</h4>
          <div class="note-footer">
            <el-tag size="small" type="info">{{ note.notebookName }}</el-tag>
            <span class="note-time">{{ note.createTime }}</span>
          </div>
        </div>
        <el-button
          v-if="dayNotes.length < dayNotesTotal"
          text
          :loading="dayNotesLoading"
          @click="loadMoreDayNotes"
        >加载更多</el-button>
      </div>
      <el-empty v-else description="当天没有笔记" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { getCalendarMarks } from '../api/calendar'
import { getNoteList, type NoteListItem } from '../api/note'

const currentYear = ref(new Date().getFullYear())
const currentMonthNum = ref(new Date().getMonth() + 1)  // 1-12
const selectedDate = ref(new Date())
const markedDates = ref(new Set<string>())

const dialogVisible = ref(false)
const selectedDay = ref('')
const dayNotes = ref<NoteListItem[]>([])
const dayNotesTotal = ref(0)
const dayNotesPage = ref(1)
const dayNotesLoading = ref(false)
/** 日期弹窗的请求序号，用于丢弃过期响应 */
let dayNotesRequestId = 0

const currentMonth = computed(() => {
  return `${currentYear.value}-${String(currentMonthNum.value).padStart(2, '0')}`
})

// 日期变化时重置
watch([currentYear, currentMonthNum], () => {
  selectedDate.value = new Date(currentYear.value, currentMonthNum.value - 1, 1)
})

async function loadMarks() {
  try {
    const result = await getCalendarMarks(currentMonth.value)
    markedDates.value = new Set(result.dates)
  } catch { /* ignore */ }
}

function prevMonth() {
  if (currentMonthNum.value === 1) {
    currentMonthNum.value = 12
    currentYear.value--
  } else {
    currentMonthNum.value--
  }
  loadMarks()
}

function nextMonth() {
  if (currentMonthNum.value === 12) {
    currentMonthNum.value = 1
    currentYear.value++
  } else {
    currentMonthNum.value++
  }
  loadMarks()
}

function goToday() {
  const now = new Date()
  currentYear.value = now.getFullYear()
  currentMonthNum.value = now.getMonth() + 1
  selectedDate.value = now
  loadMarks()
}

async function handleDateClick(data: { day: string }) {
  selectedDay.value = data.day
  dayNotes.value = []
  dayNotesTotal.value = 0
  dayNotesPage.value = 1
  dialogVisible.value = true
  await loadDayNotes(true)
}

async function loadDayNotes(reset = false) {
  // 用请求序号而不是 dayNotesLoading 做保护。原来的写法是「有请求在飞就 return」，
  // 结果快速切换日期时新日期的请求会被整个丢掉 —— 弹窗标题是新日期，内容永远加载不出来；
  // 而且旧请求返回后会直接盖掉新日期的列表。
  const day = selectedDay.value
  if (!day) return
  const requestId = ++dayNotesRequestId
  const page = reset ? 1 : dayNotesPage.value + 1
  dayNotesLoading.value = true
  try {
    const result = await getNoteList({ createdDate: day, page, pageSize: 30 })
    if (requestId !== dayNotesRequestId) return
    dayNotes.value = reset ? result.records : [...dayNotes.value, ...result.records]
    dayNotesTotal.value = result.total
    dayNotesPage.value = page
  } catch {
    if (reset && requestId === dayNotesRequestId) {
      dayNotes.value = []
      dayNotesTotal.value = 0
    }
  } finally {
    if (requestId === dayNotesRequestId) {
      dayNotesLoading.value = false
    }
  }
}

function loadMoreDayNotes() {
  loadDayNotes(false)
}

onMounted(loadMarks)
</script>

<style scoped>
.calendar-page { padding: 0; }
.page-header { margin-bottom: 16px; }
.page-header h1 { margin: 0; font-size: 22px; }
.month-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
.month-label { font-size: 18px; font-weight: 600; }
.calendar-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
  height: 100%;
  padding-top: 4px;
}
.calendar-cell.marked {
  color: #409eff;
  font-weight: 600;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #409eff;
  margin-top: 2px;
}
:deep(.el-calendar-table .el-calendar-day) {
  height: auto !important;
  min-height: 60px;
}
.day-note-list { display: flex; flex-direction: column; gap: 8px; }
.day-note-item {
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  cursor: pointer;
}
.day-note-item:hover { background: #f5f7fa; }
.day-note-item h4 { margin: 0 0 6px; font-size: 14px; }
.note-footer { display: flex; align-items: center; gap: 12px; }
.note-time { font-size: 12px; color: #c0c4cc; }
</style>
