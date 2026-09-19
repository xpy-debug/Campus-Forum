<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const router = useRouter()

const formRef = ref()
const loading = ref(false)
const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  studentNo: '',
  email: ''
})

/**
 * 校验规则与后端的 RegisterRequest 逐条对应。
 * 前端先校验一遍不是为了替代服务端校验——服务端那份才是有效的那份——
 * 而是为了在用户按下按钮之前就告诉他哪里不对，省掉一次往返。
 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z][A-Za-z0-9_]{3,19}$/,
      message: '4-20 位，字母开头，只能包含字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    {
      pattern: /^[一-龥A-Za-z0-9_]{2,20}$/,
      message: '2-20 位，只能是中文、字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d)\S{8,32}$/,
      message: '8-32 位，需同时包含字母和数字，不能有空格',
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) =>
        value === form.password ? callback() : callback(new Error('两次输入的密码不一致')),
      trigger: 'blur'
    }
  ],
  studentNo: [{ pattern: /^\d{8,20}$/, message: '学号需为 8-20 位数字', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

async function submit() {
  // validate 在校验不通过时会 reject，这里用 catch 收敛成 false：
  // 不接的话控制台会多一条「未处理的 Promise 拒绝」，掩盖真正的问题
  if (!(await formRef.value.validate().catch(() => false))) {
    return
  }
  loading.value = true
  try {
    await store.register({
      ...form,
      // 空字符串会被后端的 @Pattern("^$|...") 放行，但语义上「没填」比「填了空串」更准确
      studentNo: form.studentNo || undefined,
      email: form.email || undefined
    })
    ElMessage.success('注册成功，请登录')
    await router.replace({ name: 'login' })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth card">
    <h2 class="title">注册</h2>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" placeholder="登录用，注册后不可修改" />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="form.nickname" placeholder="其他人看到的名字" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input v-model="form.password" type="password" show-password placeholder="8-32 位，含字母和数字" />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入密码" />
      </el-form-item>
      <el-form-item label="学号（选填）" prop="studentNo">
        <el-input v-model="form.studentNo" placeholder="绑定后可用于找回账号" />
      </el-form-item>
      <el-form-item label="邮箱（选填）" prop="email">
        <el-input v-model="form.email" placeholder="用于接收通知" />
      </el-form-item>
      <el-button class="submit" type="primary" :loading="loading" @click="submit">注册</el-button>
    </el-form>
    <p class="tip">
      已有账号？
      <router-link class="link" :to="{ name: 'login' }">去登录</router-link>
    </p>
  </div>
</template>

<style scoped>
.auth {
  max-width: 440px;
  margin: 40px auto;
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
