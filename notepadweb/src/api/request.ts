import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

function withRequestId(message: string, requestId?: string): string {
  if (!requestId || message.includes('错误编号：')) return message
  return `${message}（错误编号：${requestId}）`
}

// 请求拦截：自动加 token
request.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => Promise.reject(error)
)

// 响应拦截：统一错误处理
request.interceptors.response.use(
  response => {
    const body = response.data
    // 业务成功
    if (body.code === 200) {
      return body.data
    }
    // 业务失败（非 200）
    const message = withRequestId(
      body.message || '请求失败',
      response.headers?.['x-request-id']
    )
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  },
  error => {
    if (error.response) {
      const { status, data } = error.response
      if (status === 401) {
        // 同时清空 Pinia 里的登录态，避免路由守卫仍认为已登录而卡死
        const userStore = useUserStore()
        userStore.clearSession()
        // 后端区分了「登录已过期」和「登出/改密后被吊销」，直接用它给的文案
        ElMessage.error(data?.message || '登录已过期，请重新登录')
      } else if (status === 429) {
        // 登录失败限流：后端 message 里已带「请 N 分钟后再试」，给足阅读时间
        ElMessage({
          type: 'error',
          duration: 5000,
          message: withRequestId(
            data?.message || '操作过于频繁，请稍后再试',
            error.response.headers?.['x-request-id']
          ),
        })
      } else {
        ElMessage.error(withRequestId(
          data?.message || `请求错误 ${status}`,
          error.response.headers?.['x-request-id']
        ))
      }
    } else {
      ElMessage.error('网络异常，请检查网络连接')
    }
    return Promise.reject(error)
  }
)

export default request
