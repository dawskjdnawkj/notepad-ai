import request from './request'

export interface CalendarMarksResult {
  month: string
  dates: string[]
}

/** 获取某月有笔记的日期 */
export function getCalendarMarks(month: string): Promise<CalendarMarksResult> {
  return request.get('/calendar/marks', { params: { month } })
}
