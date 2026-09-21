import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'
import { lastNotePath } from '../utils/lastNote'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/Login.vue'),
      meta: { guest: true }
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('../views/Register.vue'),
      meta: { guest: true }
    },
    {
      path: '/forgot-password',
      name: 'ForgotPassword',
      component: () => import('../views/ForgotPassword.vue'),
      meta: { guest: true }
    },
    {
      path: '/',
      component: () => import('../views/Layout.vue'),
      redirect: () => lastNotePath(),
      children: [
        {
          path: 'notes',
          redirect: () => lastNotePath(),
          meta: { requiresAuth: true }
        },
        {
          path: 'notes/empty',
          name: 'NoteEmpty',
          component: () => import('../views/NoteEmpty.vue'),
          meta: { requiresAuth: true }
        },
        {
          path: 'notes/:id',
          name: 'NoteEdit',
          component: () => import('../views/NoteEdit.vue'),
          meta: { requiresAuth: true }
        },
        {
          path: 'trash',
          name: 'Trash',
          component: () => import('../views/Trash.vue'),
          meta: { requiresAuth: true }
        },
        {
          path: 'calendar',
          name: 'Calendar',
          component: () => import('../views/Calendar.vue'),
          meta: { requiresAuth: true }
        },
        {
          path: 'notifications',
          name: 'Notification',
          component: () => import('../views/Notification.vue'),
          meta: { requiresAuth: true }
        }
      ]
    }
  ]
})

// 导航守卫：未登录跳登录页，已登录访问 guest 页跳首页
router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()

  // 已登录用户访问登录/注册页 → 跳首页
  if (to.meta.guest && userStore.isLoggedIn()) {
    return next(lastNotePath())
  }

  // 需要登录的页面
  if (to.meta.requiresAuth) {
    if (!userStore.isLoggedIn()) {
      return next('/login')
    }
    // 有 token 但没用户信息 → 恢复
    if (!userStore.userInfo) {
      try {
        await userStore.fetchMe()
      } catch {
        // 只清本地态：fetchMe 都失败了，再调 logout 只会多打一次必定 401 的请求
        userStore.clearSession()
        return next('/login')
      }
    }
  }

  next()
})

export default router
