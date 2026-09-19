<script setup>
import { computed } from 'vue'
import { ChatDotRound, View } from '@element-plus/icons-vue'
import LikeButton from './LikeButton.vue'
import { POST_TYPE, TARGET_POST } from '@/constants'

const props = defineProps({
  post: { type: Object, required: true }
})

const typeTag = computed(() => POST_TYPE[props.post.type] || POST_TYPE[0])

/** 后端返回的是 UTC 毫秒序列化后的字符串，这里只做展示层的截断 */
function formatTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <article class="post card lift">
    <router-link class="main" :to="{ name: 'post-detail', params: { id: post.id } }">
      <div class="body">
        <h3 class="title">
          <el-tag v-if="typeTag.label" :type="typeTag.tag" size="small" effect="light">
            {{ typeTag.label }}
          </el-tag>
          <span>{{ post.title }}</span>
        </h3>
        <p v-if="post.summary" class="summary">{{ post.summary }}</p>
        <div class="meta">
          <span class="author">{{ post.author?.nickname || '已注销用户' }}</span>
          <i class="sep" aria-hidden="true"></i>
          <time class="text-muted">{{ formatTime(post.publishTime) }}</time>
          <span v-for="tag in post.tags" :key="tag.id" class="tag">#{{ tag.name }}</span>
        </div>
      </div>
      <img v-if="post.coverImage" class="cover" :src="post.coverImage" alt="" />
    </router-link>

    <div class="footer">
      <LikeButton
        :target-type="TARGET_POST"
        :target-id="post.id"
        :liked="post.isLiked"
        :count="post.likeCount"
        size="small"
      />
      <span class="stat"><el-icon><ChatDotRound /></el-icon>{{ post.commentCount }}</span>
      <span class="stat"><el-icon><View /></el-icon>{{ post.viewCount }}</span>
    </div>
  </article>
</template>

<style scoped>
.post {
  padding: 16px 18px;
  margin-bottom: 12px;
}

.main {
  display: flex;
  gap: 16px;
}

.body {
  flex: 1;
  min-width: 0;
}

.title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
  transition: color var(--dur) var(--ease);
}

.main:hover .title {
  color: var(--brand);
}

.summary {
  display: -webkit-box;
  margin: 0 0 10px;
  overflow: hidden;
  color: var(--ink-2);
  font-size: 13.5px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: var(--ink-3);
  font-size: 12px;
}

.author {
  color: var(--ink-2);
}

/* 分隔点用元素而非字符，省得一行里攒出好几个间隔号 */
.sep {
  width: 3px;
  height: 3px;
  background: var(--line-strong);
  border-radius: 50%;
}

.tag {
  color: var(--brand);
}

/* 封面固定尺寸，避免不同比例的图片把卡片撑得高低不一，列表看起来会乱 */
.cover {
  flex: none;
  width: 128px;
  height: 86px;
  object-fit: cover;
  background: var(--surface-2);
  border-radius: var(--radius-ctl);
}

.footer {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.stat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-3);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 767px) {
  .cover {
    width: 96px;
    height: 72px;
  }
}
</style>
