<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Check,
  ChevronRight,
  Eye,
  EyeOff,
  FolderOpen,
  Image,
  ShieldCheck,
} from "@lucide/vue";
import { authErrorMessage } from "../api/auth";
import BrandLogo from "../components/BrandLogo.vue";
import { useAuth } from "../stores/auth";

const route = useRoute();
const router = useRouter();
const auth = useAuth();
const username = ref("");
const password = ref("");
const remember = ref(true);
const visible = ref(false);
const loading = ref(false);
const error = ref("");

async function submit() {
  error.value = "";
  if (!username.value.trim() || !password.value) {
    error.value = "请输入用户名和密码";
    return;
  }
  if (loading.value) return;
  loading.value = true;
  try {
    await auth.login(
      { username: username.value.trim(), password: password.value },
      remember.value,
    );
    const redirect = route.query.redirect;
    await router.replace(
      typeof redirect === "string" &&
        redirect.startsWith("/") &&
        !redirect.startsWith("//")
        ? redirect
        : "/",
    );
  } catch (e) {
    error.value = authErrorMessage(e, "登录失败，请稍后重试");
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <header class="login-header">
      <BrandLogo />
      <nav>
        <a href="#">客户端下载</a><a href="#">会员中心</a
        ><a href="#">帮助中心</a><span aria-hidden="true"></span
        ><a href="#">企业服务</a>
      </nav>
    </header>

    <section class="login-hero">
      <div class="hero-copy">
        <div class="hero-kicker"><span></span>安全存储 · 极速传输</div>
        <h1>你的文件，<br /><em>随时随地</em>都在身边</h1>
        <p>照片、视频和文档安全存储，多端同步，轻松分享</p>
        <div class="feature-row">
          <div>
            <i><Image :size="24" /></i><b>智能相册</b
            ><small>珍贵回忆自动整理</small>
          </div>
          <div>
            <i><FolderOpen :size="24" /></i><b>多端同步</b
            ><small>文件随身携带</small>
          </div>
          <div>
            <i><ShieldCheck :size="24" /></i><b>安全保障</b
            ><small>数据加密保护</small>
          </div>
        </div>
      </div>

      <div class="login-card">
        <div class="card-title"><h2>账号登录</h2></div>
        <p class="welcome">登录 CendoDrive，畅享美好生活</p>
        <p v-if="route.query.registered === '1'" class="success">
          注册成功，请登录。
        </p>
        <p v-if="route.query.logoutWarning === '1'" class="error" role="alert">
          本地已退出，但服务端撤销未确认；请检查网络。
        </p>
        <form @submit.prevent="submit">
          <label class="input-wrap">
            <input
              v-model="username"
              autocomplete="username"
              aria-label="用户名"
              placeholder="用户名"
            />
          </label>
          <label class="input-wrap password">
            <input
              v-model="password"
              :type="visible ? 'text' : 'password'"
              autocomplete="current-password"
              aria-label="密码"
              placeholder="请输入密码"
            />
            <button
              type="button"
              @click="visible = !visible"
              :aria-label="visible ? '隐藏密码' : '显示密码'"
            >
              <EyeOff v-if="visible" :size="18" /><Eye v-else :size="18" />
            </button>
          </label>
          <div class="form-meta">
            <label class="check"
              ><input v-model="remember" type="checkbox" /><span
                ><Check :size="12" /></span
              >下次自动登录</label
            >
          </div>
          <p v-if="auth.verificationError.value" class="error">
            暂时无法验证登录状态，请检查网络后重试。
          </p>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <button class="login-button" :disabled="loading">
            {{ loading ? "正在登录..." : "登录" }}
          </button>
        </form>
        <div class="register">
          还没有账号？<RouterLink to="/register"
            >立即注册 <ChevronRight :size="14"
          /></RouterLink>
        </div>
      </div>
    </section>

    <footer class="login-footer">
      <span>© 2026 CendoDrive</span><span>开发团队 MIND</span>
    </footer>
  </main>
</template>
