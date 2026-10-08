<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ChevronRight, Eye, EyeOff } from "@lucide/vue";
import BrandLogo from "../components/BrandLogo.vue";
import { authErrorMessage, fieldErrors, registerRequest } from "../api/auth";

const router = useRouter();
const username = ref("");
const nickname = ref("");
const password = ref("");
const confirmPassword = ref("");
const visible = ref(false);
const loading = ref(false);
const error = ref("");
const fields = ref<Record<string, string>>({});

async function submit() {
  if (loading.value) return;
  error.value = "";
  fields.value = {};
  const name = username.value.trim();
  if (!/^[a-zA-Z0-9_]{3,64}$/.test(name))
    fields.value.username = "用户名需为 3–64 位字母、数字或下划线";
  if (password.value.length < 8 || password.value.length > 128)
    fields.value.password = "密码需为 8–128 位";
  if (nickname.value.trim().length > 64)
    fields.value.nickname = "昵称最多 64 个字符";
  if (password.value !== confirmPassword.value)
    fields.value.confirmPassword = "两次输入的密码不一致";
  if (Object.keys(fields.value).length) return;
  loading.value = true;
  try {
    await registerRequest({
      username: name,
      password: password.value,
      ...(nickname.value.trim() ? { nickname: nickname.value.trim() } : {}),
    });
    await router.replace({ name: "login", query: { registered: "1" } });
  } catch (e) {
    fields.value = fieldErrors(e);
    error.value = authErrorMessage(e, "注册失败，请稍后重试");
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <main class="login-page">
    <header class="login-header"><BrandLogo /></header>
    <section class="login-hero">
      <div class="hero-copy">
        <div class="hero-kicker"><span></span>创建账号</div>
        <h1>欢迎来到<br /><em>CendoDrive</em></h1>
        <p>使用用户名和密码开始你的云盘体验</p>
      </div>
      <div class="login-card">
        <div class="card-title"><h2>注册账号</h2></div>
        <p class="welcome">
          已有账号？<RouterLink to="/login">返回登录</RouterLink>
        </p>
        <form @submit.prevent="submit">
          <label class="input-wrap"
            ><input
              v-model="username"
              autocomplete="username"
              maxlength="64"
              aria-label="用户名"
              :aria-invalid="Boolean(fields.username)"
              placeholder="用户名（3–64 位字母、数字或下划线）"
          /></label>
          <p v-if="fields.username" class="field-error">
            {{ fields.username }}
          </p>
          <label class="input-wrap"
            ><input
              v-model="nickname"
              autocomplete="nickname"
              maxlength="64"
              aria-label="昵称（选填）"
              :aria-invalid="Boolean(fields.nickname)"
              placeholder="昵称（选填）"
          /></label>
          <p v-if="fields.nickname" class="field-error">
            {{ fields.nickname }}
          </p>
          <label class="input-wrap password">
            <input
              v-model="password"
              :type="visible ? 'text' : 'password'"
              autocomplete="new-password"
              aria-label="密码"
              :aria-invalid="Boolean(fields.password)"
              placeholder="密码（8–128 位）"
            />
            <button
              type="button"
              @click="visible = !visible"
              :aria-label="visible ? '隐藏密码' : '显示密码'"
            >
              <EyeOff v-if="visible" :size="18" /><Eye v-else :size="18" />
            </button>
          </label>
          <p v-if="fields.password" class="field-error">
            {{ fields.password }}
          </p>
          <label class="input-wrap password"
            ><input
              v-model="confirmPassword"
              :type="visible ? 'text' : 'password'"
              autocomplete="new-password"
              aria-label="确认密码"
              :aria-invalid="Boolean(fields.confirmPassword)"
              placeholder="再次输入密码"
          /></label>
          <p v-if="fields.confirmPassword" class="field-error">
            {{ fields.confirmPassword }}
          </p>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <button class="login-button" :disabled="loading">
            {{ loading ? "正在注册..." : "注册" }}
          </button>
        </form>
        <div class="register">
          已有账号？<RouterLink to="/login"
            >立即登录 <ChevronRight :size="14"
          /></RouterLink>
        </div>
      </div>
    </section>
    <footer>© 2026 CendoDrive</footer>
  </main>
</template>
