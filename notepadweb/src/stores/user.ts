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

  /**
   * 只清本地登录态并回登录页，不调后端。
   * 用于改密成功后（服务端已吊销 token）、路由守卫失败等场景。
   */
  function clearSession() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    // 已在登录页就不要再 push，否则 vue-router 会抛重复导航的 rejection
    if (router.currentRoute.value.path !== '/login') {
      router.push('/login')
    }
  }

  /** 登出：服务端吊销 token，无论成功失败都清本地态 */
  async function logout() {
    try {
      await logoutApi()
    } finally {
      clearSession()
    }
  }

  return { token, userInfo, isLoggedIn, login, register, fetchMe, logout, clearSession }
})
