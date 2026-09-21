<template>
  <div class="login-page">
    <div class="login-card">
      <h1>云记事本</h1>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" :loading="loading" native-type="submit" class="submit-btn">
            登 录
          </el-button>
        </el-form-item>
      </el-form>
      <!-- 演示用：访客/面试官无需注册即可体验。点击自动填入 -->
      <div class="demo-account" @click="fillDemoAccount">
        <span class="demo-label">演示账号</span>
        <span class="demo-cred">{{ DEMO_ACCOUNT.username }} / {{ DEMO_ACCOUNT.password }}</span>
        <span class="demo-tip">点击填入</span>
      </div>
      <div class="extra-link">
        <router-link to="/forgot-password">忘记密码？</router-link>
        <span class="sep">·</span>
        还没有账号？<router-link to="/register">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '../stores/user'
import { lastNotePath } from '../utils/lastNote'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

/**
 * 演示账号：展示站不需要访客注册即可体验。
 * ⚠️ 改过这个账号的密码后，这里的文案要同步更新，否则提示就失效了。
 */
const DEMO_ACCOUNT = { username: '222222', password: '222222' }

function fillDemoAccount() {
  form.username = DEMO_ACCOUNT.username
  form.password = DEMO_ACCOUNT.password
}

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名 3~20 位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码 6~20 位', trigger: 'blur' }
  ]
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push(lastNotePath())
  } catch {
    // 错误已在拦截器中处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: #f0f2f5;
}
.login-card {
  width: 400px;
  padding: 40px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}
.login-card h1 {
  text-align: center;
  margin-bottom: 32px;
  color: #409eff;
}
.submit-btn {
  width: 100%;
}
.demo-account {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
  padding: 10px 14px;
  border: 1px dashed #a0cfff;
  border-radius: 6px;
  background: #ecf5ff;
  color: #409eff;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
  transition: background 0.2s;
}
.demo-account:hover {
  background: #d9ecff;
}
.demo-cred {
  font-family: Consolas, Monaco, monospace;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.demo-tip {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
}
.extra-link {
  text-align: center;
  color: #999;
}
.extra-link a {
  color: #409eff;
}
.extra-link .sep {
  margin: 0 6px;
  color: #ccc;
}
</style>
