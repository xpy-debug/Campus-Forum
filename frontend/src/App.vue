<script setup>
import AppHeader from '@/components/AppHeader.vue'
</script>

<template>
  <div class="layout">
    <AppHeader />
    <main class="page">
      <router-view />
    </main>
    <footer class="footer">
      <span>校园论坛</span>
      <i class="sep" aria-hidden="true"></i>
      <span>课程设计项目</span>
    </footer>
  </div>
</template>

<style scoped>
/* 用 100dvh 而非 100vh：移动端浏览器地址栏收起时 100vh 会让页脚被顶出一屏 */
.layout {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
}

.page {
  flex: 1;
}

.footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 28px 16px 32px;
  color: var(--ink-3);
  font-size: 12px;
}

.sep {
  width: 3px;
  height: 3px;
  background: var(--line-strong);
  border-radius: 50%;
}
</style>
