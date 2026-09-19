<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import CommentItem from './CommentItem.vue'
import { createComment, deleteComment, listComments } from '@/api/comment'
import { PAGE_SIZE } from '@/constants'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  postId: { type: [Number, String], required: true },
  postAuthorId: { type: [Number, String], default: null },
  /** 帖子的评论数，由详情页传入，发表/删除后由本组件回传新值 */
  commentCount: { type: Number, default: 0 }
})

const emit = defineEmits(['count-change'])

const store = useUserStore()

const comments = ref([])
const cursor = ref('')
const hasMore = ref(false)
const loading = ref(false)
const submitting = ref(false)
const content = ref('')
/** 当前正在回复的评论，null 表示发表一级评论 */
const replyTo = ref(null)

const placeholder = computed(() =>
  replyTo.value
    ? `回复 @${replyTo.value.user?.nickname || '该用户'}`
    : '写下你的评论…'
)

async function load(reset = false) {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const page = await listComments(props.postId, {
      cursor: reset ? undefined : cursor.value || undefined,
      size: PAGE_SIZE
    })
    comments.value = reset ? page.list : [...comments.value, ...page.list]
    cursor.value = page.nextCursor || ''
    hasMore.value = page.hasMore
  } finally {
    loading.value = false
  }
}

async function submit() {
  const text = content.value.trim()
  if (!text) {
    ElMessage.warning('评论内容不能为空')
    return
  }
  submitting.value = true
  try {
    await createComment({
      postId: Number(props.postId),
      content: text,
      // 后端用 0 表示「没有父级」，与表结构的字段约定一致
      parentId: replyTo.value ? replyTo.value.id : 0
    })
    content.value = ''
    replyTo.value = null
    // 重新拉第一页而不是把返回值插到本地：新评论的楼层、回复数、
    // 以及被回复那条的 replyCount 都变了，本地拼装很容易漏掉其中一项
    await load(true)
    emit('count-change', props.commentCount + 1)
    ElMessage.success('评论成功')
  } finally {
    submitting.value = false
  }
}

async function remove(commentId) {
  await deleteComment(commentId)
  await load(true)
  emit('count-change', Math.max(0, props.commentCount - 1))
  ElMessage.success('已删除')
}

onMounted(() => load(true))
</script>

<template>
  <section class="comments card">
    <h3 class="heading">
      评论
      <span class="count">{{ commentCount }}</span>
    </h3>

    <div class="editor">
      <el-input
        v-model="content"
        type="textarea"
        :rows="3"
        maxlength="1000"
        show-word-limit
        :placeholder="store.isLogin ? placeholder : '登录后即可参与讨论'"
        :disabled="!store.isLogin"
      />
      <div class="editor-footer">
        <span v-if="replyTo" class="replying text-muted">
          正在回复 {{ replyTo.user?.nickname || '该用户' }}
          <el-button link size="small" @click="replyTo = null">取消</el-button>
        </span>
        <el-button
          class="submit"
          type="primary"
          :loading="submitting"
          :disabled="!store.isLogin"
          @click="submit"
        >
          发表
        </el-button>
      </div>
    </div>

    <el-empty v-if="!loading && !comments.length" :image-size="72" description="还没有评论，来说两句" />

    <CommentItem
      v-for="comment in comments"
      :key="comment.id"
      :comment="comment"
      :post-author-id="postAuthorId"
      @reply="(target) => (replyTo = target)"
      @deleted="remove"
    />

    <div v-if="hasMore" class="more">
      <el-button link :loading="loading" @click="load(false)">加载更多评论</el-button>
    </div>
  </section>
</template>

<style scoped>
.comments {
  padding: 20px 22px 8px;
}

.heading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}

.count {
  color: var(--ink-3);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  font-weight: 400;
}

.editor {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

/* 未在回复时把「发表」推到右侧 */
.replying {
  font-size: 13px;
}

.editor-footer .submit {
  margin-left: auto;
}

.more {
  padding: 12px 0;
  text-align: center;
}

@media (max-width: 767px) {
  .comments {
    padding: 16px 16px 4px;
  }
}
</style>
