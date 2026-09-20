import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as loginApi, register as registerApi, getMe, logout as logoutApi } from '../api/auth'
import type { UserInfo } from '../api/auth'
import router from '../router'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const userInfo = ref<UserInfo | null>(null)

  /** 是否已登录 */
  const isLoggedIn = () => !!token.value

  /** 登录 */
  async function login(username: string, password: string) {
    const result = await loginApi({ username, password })
    token.value = result.token
    userInfo.value = result.user
    localStorage.setItem('token', result.token)
    return result
  }

  /** 注册 */
  async function register(username: string, password: string, email: string, code: string) {
    const result = await registerApi({ username, password, email, code })
    token.value = result.token
    userInfo.value = result.user
    localStorage.setItem('token', result.token)
    return result
  }

  /** 刷新时恢复用户信息 */
  async function fetchMe() {
    if (!token.value) return
    const user = await getMe()
    userInfo.value = user
  }

  /** 登出 */
  async function logout() {
    try {
      await logoutApi()
    } finally {
      token.value = ''
      userInfo.value = null
      localStorage.removeItem('token')
      router.push('/login')
    }
  }

  return { token, userInfo, isLoggedIn, login, register, fetchMe, logout }
})
