<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const router = useRouter()
const route = useRoute()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  // validate 在校验不通过时会 reject，这里用 catch 收敛成 false：
  // 不接的话控制台会多一条「未处理的 Promise 拒绝」，掩盖真正的问题
  if (!(await formRef.value.validate().catch(() => false))) {
    return
  }
  loading.value = true
  try {
    await store.login({ ...form, deviceId: deviceId() })
    ElMessage.success('登录成功')
    // 回到登录前想去的页面。redirect 是路由守卫写进去的，
    // 直接用它做 path 之前要确认它是站内路径，否则就成了开放重定向
    await router.replace(safeRedirect())
  } finally {
    loading.value = false
  }
}

function safeRedirect() {
  const target = route.query.redirect
  return typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
    ? target
    : { name: 'home' }
}

/** 设备标识，用于服务端区分同一账号的不同会话。没有它也不影响登录 */
function deviceId() {
  const key = 'forum_device_id'
  let value = localStorage.getItem(key)
  if (!value) {
    value = crypto.randomUUID()
    localStorage.setItem(key, value)
  }
  return value
}
</script>

<template>
  <div class="auth card">
    <h2 class="title">登录</h2>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="请输入密码"
          show-password
          autocomplete="current-password"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-button class="submit" type="primary" :loading="loading" @click="submit">登录</el-button>
    </el-form>
    <p class="tip">
      还没有账号？
      <router-link class="link" :to="{ name: 'register' }">立即注册</router-link>
    </p>
  </div>
</template>

<style scoped>
.auth {
  max-width: 392px;
  margin: 56px auto;
  padding: 30px 28px;
}

.title {
  margin: 0 0 22px;
  font-size: 20px;
  font-weight: 600;
}

.submit {
  width: 100%;
}

.tip {
  margin: 18px 0 0;
  color: var(--ink-2);
  text-align: center;
  font-size: 13px;
}

.link {
  color: var(--brand);
  font-weight: 500;
}

.link:hover {
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
