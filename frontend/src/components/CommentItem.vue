<script setup>
import { computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import LikeButton from './LikeButton.vue'
import { TARGET_COMMENT } from '@/constants'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  comment: { type: Object, required: true },
  /** 楼主 ID。楼主有权删除自己帖子下的任何评论 */
  postAuthorId: { type: [Number, String], default: null }
})

const emit = defineEmits(['reply', 'deleted'])

const store = useUserStore()

const canDelete = computed(() => {
  const me = store.info
  if (!me) {
    return false
  }
  // 与后端 CommentServiceImpl.delete 的判定保持一致：
  // 本人、楼主、版主以上。前端只是提前隐藏按钮，真正的把关在服务端
  return (
    String(props.comment.user?.id) === String(me.id) ||
    String(props.postAuthorId) === String(me.id) ||
    me.role >= 1
  )
})

async function confirmDelete() {
  // 删除不可撤销，先确认。这一步不是防误触，而是防「看错楼层删错了」
  await ElMessageBox.confirm('删除后无法恢复，确定删除这条评论吗？', '删除评论', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  emit('deleted', props.comment.id)
}

function formatTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <div class="comment">
    <el-avatar :size="32" :src="comment.user?.avatar">
      {{ (comment.user?.nickname || '?').slice(0, 1) }}
    </el-avatar>

    <div class="main">
      <div class="head">
        <span class="name">{{ comment.user?.nickname || '已注销用户' }}</span>
        <el-tag v-if="comment.isAuthor" size="small" effect="plain" type="primary">楼主</el-tag>
        <span v-if="comment.floor > 0" class="floor">{{ comment.floor }}楼</span>
        <time class="time">{{ formatTime(comment.createTime) }}</time>
      </div>

      <p class="content">{{ comment.content }}</p>

      <div class="actions">
        <LikeButton
          :target-type="TARGET_COMMENT"
          :target-id="comment.id"
          :liked="comment.isLiked"
          :count="comment.likeCount"
          size="small"
        />
        <el-button link size="small" @click="emit('reply', comment)">回复</el-button>
        <el-button v-if="canDelete" link size="small" type="danger" @click="confirmDelete">
          删除
        </el-button>
      </div>

      <!-- 二级评论。只有两级，所以这里直接平铺，不做递归组件 -->
      <div v-if="comment.replies?.length" class="replies">
        <div v-for="reply in comment.replies" :key="reply.id" class="reply">
          <div class="head">
            <span class="name">{{ reply.user?.nickname || '已注销用户' }}</span>
            <template v-if="reply.replyUser">
              <span class="floor">回复</span>
              <span class="name">@{{ reply.replyUser.nickname }}</span>
            </template>
            <time class="time">{{ formatTime(reply.createTime) }}</time>
          </div>
          <p class="content">{{ reply.content }}</p>
          <div class="actions">
            <LikeButton
              :target-type="TARGET_COMMENT"
              :target-id="reply.id"
              :liked="reply.isLiked"
              :count="reply.likeCount"
              size="small"
            />
            <el-button link size="small" @click="emit('reply', reply)">回复</el-button>
          </div>
        </div>
        <!-- 后端列表只带前两条回复预览。没有「展开全部」的接口，
             这里如实说明还有多少条，而不是给一个点了没反应的按钮 -->
        <p v-if="comment.replyCount > comment.replies.length" class="more text-muted">
          共 {{ comment.replyCount }} 条回复
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.comment {
  display: flex;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid var(--line);
}

/* 最后一条不再画分隔线，否则列表会以一条悬空的线收尾 */
.comment:last-child {
  border-bottom: none;
}

.main {
  flex: 1;
  min-width: 0;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.name {
  color: var(--ink);
  font-weight: 500;
}

.floor,
.time {
  color: var(--ink-3);
  font-size: 12px;
}

.content {
  margin: 6px 0 8px;
  white-space: pre-wrap;
  word-break: break-word;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.replies {
  margin-top: 12px;
  padding: 4px 14px;
  background: var(--surface-2);
  border-left: 2px solid var(--line);
  border-radius: var(--radius-sm);
}

.reply {
  padding: 10px 0;
}

.reply + .reply {
  border-top: 1px solid var(--line);
}

.more {
  margin: 8px 0;
  font-size: 12px;
}
</style>
