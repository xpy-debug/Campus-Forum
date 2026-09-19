import { ref } from 'vue'

/**
 * 主题（浅色 / 深色）。
 *
 * <p>三处要共用同一套判断：index.html 的无闪烁内联脚本、这里的 initTheme、
 * 以及用户点开关时的 toggleTheme。因此存储键与优先级集中在本文件，
 * 内联脚本那份是它的复制品（内联脚本不能用 import，只能靠约定同步）。
 *
 * <p>优先级：用户显式选择 > 系统偏好。
 */
const STORAGE_KEY = 'forum_theme'

const theme = ref('light')
let initialized = false

function systemPrefersDark() {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

/** 只在用户手动选过时返回，没选过返回 null（此时跟随系统） */
function storedTheme() {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' ? value : null
  } catch {
    return null
  }
}

function applyTheme(value) {
  theme.value = value
  document.documentElement.classList.toggle('dark', value === 'dark')
}

/** 应用启动时调用一次。重复调用无副作用 */
export function initTheme() {
  if (initialized) {
    return theme
  }
  initialized = true

  applyTheme(storedTheme() ?? (systemPrefersDark() ? 'dark' : 'light'))

  // 用户没手动选过时才跟随系统变化；选过之后就不再被系统覆盖
  window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener('change', (event) => {
    if (!storedTheme()) {
      applyTheme(event.matches ? 'dark' : 'light')
    }
  })

  return theme
}

export function toggleTheme() {
  const next = theme.value === 'dark' ? 'light' : 'dark'
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 隐私模式写不进去：本次切换仍然生效，只是刷新后回到系统偏好
  }
  applyTheme(next)
}

export function useTheme() {
  return { theme, toggleTheme }
}
