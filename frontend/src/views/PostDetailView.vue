<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { View } from '@element-plus/icons-vue'
import CommentList from '@/components/CommentList.vue'
import LikeButton from '@/components/LikeButton.vue'
import { getPost } from '@/api/post'
import { POST_TYPE, TARGET_POST } from '@/constants'
import { renderMarkdown } from '@/utils/markdown'

const route = useRoute()

const post = ref(null)
const loading = ref(false)

const typeTag = computed(() => POST_TYPE[post.value?.type] || POST_TYPE[0])
const html = computed(() => renderMarkdown(post.value?.content))

async function load(postId) {
  loading.value = true
  try {
    post.value = await getPost(postId)
  } finally {
    loading.value = false
  }
}

function formatTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

onMounted(() => load(route.params.id))

// 从详情页直接跳到另一篇帖子时组件不会重新创建，靠监听参数重新拉取
watch(() => route.params.id, (id) => id && load(id))
</script>

<template>
  <div v-if="post">
    <article class="post card">
      <h1 class="title">
        <el-tag v-if="typeTag.label" :type="typeTag.tag" size="small" effect="light">
          {{ typeTag.label }}
        </el-tag>
        {{ post.title }}
      </h1>

      <div class="meta">
        <el-avatar :size="36" :src="post.author?.avatar">
          {{ (post.author?.nickname || '?').slice(0, 1) }}
        </el-avatar>

        <div class="who">
          <div class="name">{{ post.author?.nickname || '已注销用户' }}</div>
          <div class="line text-muted">
            <span>{{ post.boardName }}</span>
            <i class="sep" aria-hidden="true"></i>
            <time>{{ formatTime(post.publishTime) }}</time>
          </div>
        </div>

        <div class="aside text-muted">
          <span v-if="post.wordCount">{{ post.wordCount }} 字</span>
          <span class="views"><el-icon><View /></el-icon>{{ post.viewCount }}</span>
        </div>
      </div>

      <div class="markdown-body prose" v-html="html"></div>

      <div v-if="post.tags?.length" class="tags">
        <el-tag v-for="tag in post.tags" :key="tag.id" size="small" effect="plain" type="info">
          #{{ tag.name }}
        </el-tag>
      </div>

      <div class="actions">
        <LikeButton
          :target-type="TARGET_POST"
          :target-id="post.id"
          :liked="post.isLiked"
          :count="post.likeCount"
        />
      </div>
    </article>

    <CommentList
      :post-id="post.id"
      :post-author-id="post.author?.id"
      :comment-count="post.commentCount"
      @count-change="(value) => (post.commentCount = value)"
    />
  </div>

  <el-skeleton v-else-if="loading" :rows="8" animated class="card skeleton" />
</template>

<style scoped>
.post {
  padding: 28px 30px;
  margin-bottom: 12px;
}

.title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 18px;
  font-size: 23px;
  font-weight: 600;
  line-height: 1.4;
}

.meta {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}

.who {
  min-width: 0;
}

.name {
  font-size: 14px;
  font-weight: 500;
}

.line {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
}

.sep {
  width: 3px;
  height: 3px;
  background: var(--line-strong);
  border-radius: 50%;
}

/* 字数与浏览数靠到最右，两个数值用间距分隔，不再各配一个间隔号 */
.aside {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-left: auto;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.views {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.markdown-body {
  min-height: 60px;
  margin-top: 20px;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 22px;
}

.actions {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}

.skeleton {
  padding: 28px;
}

@media (max-width: 767px) {
  .post {
    padding: 20px 18px;
  }

  .title {
    font-size: 20px;
  }

  .aside {
    display: none;
  }
}
</style>
