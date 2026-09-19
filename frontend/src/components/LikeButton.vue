<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CaretTop } from '@element-plus/icons-vue'
import { like, unlike } from '@/api/like'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  targetType: { type: Number, required: true },
  targetId: { type: [Number, String], required: true },
  liked: { type: Boolean, default: false },
  count: { type: Number, default: 0 },
  /** small 用于评论，default 用于帖子 */
  size: { type: String, default: 'default' }
})

const emit = defineEmits(['change'])

const store = useUserStore()
const pending = ref(false)

// 本地副本而不是直接改 props：props 是只读的，而且点赞后要立刻反映到界面上，
// 不能等父组件重新拉一次列表
const liked = ref(props.liked)
const count = ref(props.count)

watch(() => props.liked, (value) => { liked.value = value })
watch(() => props.count, (value) => { count.value = value })

async function toggle() {
  if (!store.isLogin) {
    ElMessage.warning('请先登录')
    return
  }
  // 防连点：两次请求若乱序返回，界面会停在错误的状态上
  // （后发的取消点赞先返回，先发的点赞后返回，最终显示已点赞）
  if (pending.value) {
    return
  }
  pending.value = true
  try {
    const result = liked.value
      ? await unlike(props.targetType, props.targetId)
      : await like(props.targetType, props.targetId)
    liked.value = result.isLiked
    count.value = result.likeCount
    emit('change', result)
  } catch {
    // 失败提示已由响应拦截器统一弹出，这里保持原状即可
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <button
    class="like"
    :class="{ active: liked, small: size === 'small' }"
    type="button"
    :disabled="pending"
    :aria-pressed="liked"
    :aria-label="liked ? '取消点赞' : '点赞'"
    @click.stop="toggle"
  >
    <el-icon class="icon"><CaretTop /></el-icon>
    <span class="count">{{ count > 0 ? count : '赞' }}</span>
  </button>
</template>

<style scoped>
.like {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 11px;
  color: var(--ink-2);
  background: transparent;
  border: 1px solid var(--line);
  border-radius: 999px;
  cursor: pointer;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease),
    border-color var(--dur) var(--ease), transform 0.12s var(--ease);
}

.like:hover {
  color: var(--brand);
  border-color: var(--brand-soft);
  background: var(--brand-soft);
}

.like:active {
  transform: scale(0.95);
}

.like.active {
  color: var(--brand);
  background: var(--brand-soft);
  border-color: var(--brand-soft);
  font-weight: 500;
}

.like:disabled {
  cursor: default;
  opacity: 0.65;
}

.icon {
  font-size: 15px;
}

.small {
  padding: 2px 9px;
  font-size: 12px;
}

.small .icon {
  font-size: 13px;
}
</style>
