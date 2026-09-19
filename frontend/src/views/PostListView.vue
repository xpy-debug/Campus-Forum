<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PostCard from '@/components/PostCard.vue'
import { listBoards } from '@/api/board'
import { listPosts } from '@/api/post'
import { PAGE_SIZE, SORT_OPTIONS } from '@/constants'

const route = useRoute()
const router = useRouter()

const boards = ref([])
const posts = ref([])
const sort = ref('latest')
const cursor = ref('')
const hasMore = ref(false)
const loading = ref(false)

const boardId = computed(() => (route.params.boardId ? Number(route.params.boardId) : null))
/** el-tabs 的 name 统一用字符串：数字 0 与字符串 '0' 在它内部比较时不相等 */
const activeTab = computed(() => String(boardId.value ?? 0))

async function loadPosts(reset) {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const page = await listPosts({
      boardId: boardId.value ?? undefined,
      sort: sort.value,
      // 第一页不传游标。空字符串会被后端当作一个格式非法的游标，
      // 虽然它也会退回第一页，但语义上「不传」才是对的
      cursor: reset ? undefined : cursor.value || undefined,
      size: PAGE_SIZE
    })
    posts.value = reset ? page.list : [...posts.value, ...page.list]
    cursor.value = page.nextCursor || ''
    hasMore.value = page.hasMore
  } finally {
    loading.value = false
  }
}

function changeBoard(name) {
  const path = name === '0' ? '/' : `/board/${name}`
  router.push(path)
}

function changeSort(value) {
  sort.value = value
  loadPosts(true)
}

onMounted(async () => {
  boards.value = await listBoards()
  await loadPosts(true)
})

// 用路由参数驱动列表，而不是在点击标签时手动重新加载：
// 浏览器前进/后退、直接改地址栏、从帖子详情点返回，走的都是同一条路径
watch(() => route.params.boardId, () => loadPosts(true))
</script>

<template>
  <div>
    <div class="toolbar card">
      <el-tabs :model-value="activeTab" @tab-change="changeBoard">
        <el-tab-pane label="全部" name="0" />
        <el-tab-pane v-for="board in boards" :key="board.id" :label="board.name" :name="String(board.id)" />
      </el-tabs>

      <el-radio-group v-model="sort" size="small" @change="changeSort">
        <el-radio-button v-for="option in SORT_OPTIONS" :key="option.value" :value="option.value">
          {{ option.label }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <el-skeleton v-if="loading && !posts.length" :rows="4" animated class="card skeleton" />

    <el-empty v-else-if="!posts.length" :image-size="88" description="这个板块还没有帖子" />

    <PostCard v-for="post in posts" :key="post.id" :post="post" />

    <div v-if="hasMore" class="more">
      <el-button :loading="loading" @click="loadPosts(false)">加载更多</el-button>
    </div>
    <p v-else-if="posts.length" class="end text-muted">没有更多了</p>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 6px 16px 2px;
  margin-bottom: 12px;
}

/* 标签页自带的下边距会把工具栏撑高，这里压掉 */
.toolbar :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.toolbar :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.toolbar :deep(.el-tabs__item) {
  height: 44px;
}

.skeleton {
  padding: 18px;
}

.more,
.end {
  padding: 10px 0 24px;
  text-align: center;
}

@media (max-width: 767px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    padding: 6px 12px 12px;
  }

  .toolbar :deep(.el-tabs__nav-wrap) {
    overflow-x: auto;
  }
}
</style>
