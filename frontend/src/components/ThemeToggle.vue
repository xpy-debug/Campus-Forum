<script setup>
import { computed } from 'vue'
import { Moon, Sunny } from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'

const { theme, toggleTheme } = useTheme()

const isDark = computed(() => theme.value === 'dark')
const label = computed(() => (isDark.value ? '切换到浅色模式' : '切换到深色模式'))
</script>

<template>
  <button class="theme-toggle" type="button" :aria-label="label" :title="label" @click="toggleTheme">
    <!-- 图标表示「点了会变成什么」：深色时显示太阳，点它去浅色 -->
    <el-icon :size="17">
      <component :is="isDark ? Sunny : Moon" />
    </el-icon>
  </button>
</template>

<style scoped>
.theme-toggle {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  color: var(--ink-2);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-ctl);
  cursor: pointer;
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease);
}

.theme-toggle:hover {
  color: var(--brand);
  background: var(--el-fill-color-light);
}

.theme-toggle:active {
  transform: scale(0.94);
}
</style>
