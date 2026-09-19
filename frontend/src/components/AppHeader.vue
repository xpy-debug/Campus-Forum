<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { EditPen, User } from '@element-plus/icons-vue'
import ThemeToggle from './ThemeToggle.vue'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const router = useRouter()

/** 昵称可能为空（用户没填），退回用户名，再不济给个占位，避免顶栏出现空白按钮 */
const displayName = computed(() => store.info?.nickname || store.info?.username || '用户')

/** 下拉菜单是命令式的：所有分支都走这里，而不是给每个 item 各挂一个 @click */
async function onCommand(command) {
  if (command === 'logout') {
    await store.logout()
    ElMessage.success('已退出登录')
    await router.push({ name: 'home' })
    return
  }
  await router.push({ name: command })
}
</script>

<template>
  <header class="header">
    <div class="header-inner">
      <router-link class="brand" to="/">
        <span class="brand-mark">校</span>
        <span class="brand-name">校园论坛</span>
      </router-link>

      <nav class="nav">
        <router-link class="nav-item" to="/">首页</router-link>
        <router-link class="nav-item" to="/points">签到</router-link>
        <router-link class="nav-item" to="/mall">积分商城</router-link>
        <!-- 管理入口只对管理员显示。这是「不打扰普通用户」，不是权限控制：
             后端对 /admin/** 全部做了 @RequireRole(ROLE_ADMIN) 校验 -->
        <router-link v-if="store.isAdmin" class="nav-item" to="/admin/mall">商城管理</router-link>
        <router-link v-if="store.isAdmin" class="nav-item" to="/admin/seckill">秒杀管理</router-link>
      </nav>

      <div class="actions">
        <ThemeToggle />

        <template v-if="store.isLogin">
          <el-button type="primary" :icon="EditPen" @click="router.push({ name: 'post-create' })">
            发帖
          </el-button>
          <el-dropdown @command="onCommand">
            <span class="user">
              <el-avatar :size="28" :src="store.info?.avatar">
                {{ displayName.slice(0, 1) }}
              </el-avatar>
              <span class="user-name">{{ displayName }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="points">我的积分</el-dropdown-item>
                <el-dropdown-item command="orders">我的兑换</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <el-button :icon="User" @click="router.push({ name: 'login' })">登录</el-button>
          <el-button type="primary" @click="router.push({ name: 'register' })">注册</el-button>
        </template>
      </div>
    </div>
  </header>
</template>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: var(--z-header);
  /* 先给不透明底作为兜底：不认识 color-mix 的浏览器会忽略下面那行 */
  background: var(--surface);
  background: color-mix(in srgb, var(--surface) 82%, transparent);
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(12px) saturate(160%);
  -webkit-backdrop-filter: blur(12px) saturate(160%);
}

/* 不支持毛玻璃时退回不透明底，避免文字压在内容上读不清 */
@supports not (backdrop-filter: blur(1px)) {
  .header {
    background: var(--surface);
  }
}

.header-inner {
  display: flex;
  align-items: center;
  gap: 20px;
  width: 100%;
  max-width: var(--content);
  height: var(--header-h);
  margin: 0 auto;
  padding: 0 16px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  color: var(--brand-contrast);
  background: var(--brand-solid);
  border-radius: 9px;
  font-size: 15px;
  font-weight: 600;
}

.nav {
  display: flex;
  flex: 1;
  gap: 2px;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.nav::-webkit-scrollbar {
  display: none;
}

.nav-item {
  padding: 6px 12px;
  color: var(--ink-2);
  border-radius: var(--radius-ctl);
  font-size: 14px;
  white-space: nowrap;
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease);
}

.nav-item:hover {
  color: var(--ink);
  background: var(--el-fill-color-light);
}

.nav-item.router-link-active {
  color: var(--brand);
  background: var(--brand-soft);
  font-weight: 500;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 6px 3px 3px;
  border-radius: 999px;
  cursor: pointer;
  outline: none;
  transition: background var(--dur) var(--ease);
}

.user:hover {
  background: var(--el-fill-color-light);
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 767px) {
  .header-inner {
    gap: 12px;
  }

  .user-name {
    display: none;
  }
}
</style>
