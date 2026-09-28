<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Check, ChevronRight, Eye, EyeOff, FolderOpen, Image, ShieldCheck, Smartphone } from 'lucide-vue-next'
import BrandLogo from '../components/BrandLogo.vue'
import { useAuth } from '../stores/auth'
import { registerRequest } from '../api/auth'

const router = useRouter()
const auth = useAuth()
const account = ref('')
const password = ref('')
const remember = ref(true)
const visible = ref(false)
const loading = ref(false)
const error = ref('')
const isRegister = ref(false)
const confirmPassword = ref('')

async function submit() {
  error.value = ''
  if (!account.value.trim() || !password.value.trim()) {
    error.value = '请输入手机号/邮箱/用户名和密码'
    return
  }
  loading.value = true
  try {
    if (isRegister.value) {
      if (password.value !== confirmPassword.value) throw new Error('两次输入的密码不一致')
      await registerRequest(account.value.trim(), password.value)
    }
    await auth.login({ account: account.value, password: password.value, remember: remember.value })
    await router.replace('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function toggleMode() {
  isRegister.value = !isRegister.value
  error.value = ''
  confirmPassword.value = ''
}
</script>

<template>
  <main class="login-page">
    <header class="login-header">
      <BrandLogo />
      <nav><a href="#">客户端下载</a><a href="#">会员中心</a><a href="#">帮助中心</a><span></span><a href="#">企业服务</a></nav>
    </header>

    <section class="login-hero">
      <div class="hero-copy">
        <div class="hero-kicker"><span></span>安全存储 · 极速传输</div>
        <h1>你的文件，<br /><em>随时随地</em>都在身边</h1>
        <p>照片、视频和文档安全存储，多端同步，轻松分享</p>
        <div class="feature-row">
          <div><i><Image :size="24" /></i><b>智能相册</b><small>珍贵回忆自动整理</small></div>
          <div><i><FolderOpen :size="24" /></i><b>多端同步</b><small>文件随身携带</small></div>
          <div><i><ShieldCheck :size="24" /></i><b>安全保障</b><small>数据加密保护</small></div>
        </div>
      </div>

      <div class="login-card">
        <div class="card-title"><h2>{{ isRegister ? '创建账号' : '账号登录' }}</h2><button title="扫码登录"><Smartphone :size="20" /></button></div>
        <p class="welcome">{{ isRegister ? '注册 CendoDrive，开始云端生活' : '登录 CendoDrive，畅享美好生活' }}</p>
        <form @submit.prevent="submit">
          <label class="input-wrap">
            <input v-model="account" autocomplete="username" placeholder="手机号 / 邮箱 / 用户名" />
          </label>
          <label class="input-wrap password">
            <input v-model="password" :type="visible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" />
            <button type="button" @click="visible = !visible" :aria-label="visible ? '隐藏密码' : '显示密码'"><EyeOff v-if="visible" :size="18" /><Eye v-else :size="18" /></button>
          </label>
          <label v-if="isRegister" class="input-wrap password">
            <input v-model="confirmPassword" :type="visible ? 'text' : 'password'" autocomplete="new-password" placeholder="请再次输入密码" />
          </label>
          <div class="form-meta">
            <label class="check"><input v-model="remember" type="checkbox" /><span><Check :size="12" /></span>下次自动登录</label>
            <a href="#">忘记密码？</a>
          </div>
          <p v-if="error" class="error">{{ error }}</p>
          <button class="login-button" :disabled="loading">{{ loading ? '正在处理...' : isRegister ? '注册并登录' : '登录' }}</button>
        </form>
        <div class="register">{{ isRegister ? '已有账号？' : '还没有账号？' }}<a href="#" @click.prevent="toggleMode">{{ isRegister ? '返回登录' : '立即注册' }} <ChevronRight :size="14" /></a></div>
        <p class="terms">登录即代表同意 <a href="#">用户协议</a> 和 <a href="#">隐私政策</a></p>
      </div>
    </section>

    <footer>© 2026 CendoDrive　|　隐私政策　|　服务协议　|　联系我们</footer>
  </main>
</template>
