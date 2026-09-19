<script setup>
import { useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'

/**
 * 页面标题区。
 *
 * <p>原先商城、兑换记录、秒杀记录、两个管理页各写了一遍几乎相同的 `.bar` 结构，
 * 且「返回 + 居中标题」那种做法还要额外放一个等宽占位元素来假居中。
 * 统一到这里之后，全站只有一个页面头布局族，标题一律左对齐。
 */
const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  /** 传了就在标题左侧出现返回按钮，值为路由 location */
  backTo: { type: [String, Object], default: null },
  backText: { type: String, default: '返回' }
})

const router = useRouter()

function goBack() {
  if (props.backTo) {
    router.push(props.backTo)
  } else {
    router.back()
  }
}
</script>

<template>
  <header class="page-header">
    <el-button v-if="backTo" class="back" text :icon="ArrowLeft" @click="goBack">
      {{ backText }}
    </el-button>

    <div class="lead">
      <h2 class="title">{{ title }}</h2>
      <p v-if="subtitle" class="subtitle text-muted">{{ subtitle }}</p>
    </div>

    <div v-if="$slots.extra" class="extra">
      <slot name="extra" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  margin-bottom: 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-sm);
}

.back {
  margin-left: -8px;
}

.lead {
  min-width: 0;
}

.title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--ink);
}

.subtitle {
  margin: 2px 0 0;
  font-size: 13px;
  line-height: 1.5;
}

/* 右侧内容靠到最右，标题块吃掉剩余空间 */
.extra {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

@media (max-width: 767px) {
  .page-header {
    flex-wrap: wrap;
    padding: 14px 16px;
  }

  .extra {
    width: 100%;
    margin-left: 0;
  }
}
</style>
