import request from './request'

export interface LoginParams {
  username: string
  password: string
}

export interface RegisterParams {
  username: string
  password: string
  email: string
  code: string
}

export interface UserInfo {
  id: number
  username: string
  nickname: string | null
  avatar: string | null
}

export interface LoginResult {
  token: string
  user: UserInfo
}

/** 登录 */
export function login(params: LoginParams): Promise<LoginResult> {
  return request.post('/auth/login', params)
}

/** 发送邮箱验证码 */
export function sendCode(email: string): Promise<null> {
  return request.post('/auth/send-code', { email })
}

/** 注册（成功后自动登录，返回同 login） */
export function register(params: RegisterParams): Promise<LoginResult> {
  return request.post('/auth/register', params)
}

/** 登出 */
export function logout(): Promise<null> {
  return request.post('/auth/logout')
}

/** 获取当前用户信息 */
export function getMe(): Promise<UserInfo> {
  return request.get('/auth/me')
}

/** 发送找回密码验证码 */
export function sendPasswordResetCode(email: string): Promise<null> {
  return request.post('/auth/password/reset-code', { email })
}

/** 找回密码（邮箱 + 验证码 + 新密码） */
export function resetPassword(email: string, code: string, newPassword: string): Promise<null> {
  return request.post('/auth/password/reset', { email, code, newPassword })
}

/** 发送修改密码验证码（登录后） */
export function sendChangePasswordCode(): Promise<null> {
  return request.post('/auth/password/change-code')
}

/** 修改密码（登录后，验证码 + 新密码） */
export function changePassword(code: string, newPassword: string): Promise<null> {
  return request.post('/auth/password/change', { code, newPassword })
}
