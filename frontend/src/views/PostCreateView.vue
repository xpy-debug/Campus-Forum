<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listBoards } from '@/api/board'
import { createPost } from '@/api/post'
import { renderMarkdown } from '@/utils/markdown'

const router = useRouter()

const boards = ref([])
const formRef = ref()
const submitting = ref(false)
const tab = ref('edit')
const form = reactive({ boardId: null, title: '', content: '' })

const preview = computed(() => renderMarkdown(form.content))

const rules = {
  boardId: [{ required: true, message: '请选择板块', trigger: 'change' }],
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { min: 5, max: 128, message: '标题需 5-128 字', trigger: 'blur' }
  ],
  content: [
    { required: true, message: '请输入正文', trigger: 'blur' },
    { min: 5, max: 20000, message: '正文需 5-20000 字', trigger: 'blur' }
  ]
}

onMounted(async () => {
  boards.value = await listBoards()
  // 预选默认板块，让用户少做一次选择。没有标记默认的板块时留空，交给用户选
  form.boardId = boards.value.find((board) => board.isDefault)?.id ?? null
})

async function submit() {
  if (!(await formRef.value.validate().catch(() => false))) {
    return
  }
  submitting.value = true
  try {
    // 幂等令牌在「按下发布」这一刻生成，重试时复用同一个：
    // 网络超时后用户再点一次，服务端返回的是首次创建的那篇帖子
    const postId = await createPost(
      {
        boardId: form.boardId,
        title: form.title,
        content: form.content,
        status: 1
      },
      crypto.randomUUID()
    )
    ElMessage.success('发布成功')
    await router.replace({ name: 'post-detail', params: { id: postId } })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="create card">
    <h2 class="title">发布新帖</h2>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="板块" prop="boardId">
        <el-select v-model="form.boardId" placeholder="请选择板块" class="board-select">
          <el-option
            v-for="board in boards"
            :key="board.id"
            :label="board.name"
            :value="board.id"
            :disabled="board.status === 2"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="用一句话说清楚要讨论什么" />
      </el-form-item>

      <el-form-item prop="content">
        <template #label>
          <div class="content-label">
            <span>正文</span>
            <el-radio-group v-model="tab" size="small">
              <el-radio-button value="edit">编辑</el-radio-button>
              <el-radio-button value="preview">预览</el-radio-button>
            </el-radio-group>
          </div>
        </template>
        <el-input
          v-if="tab === 'edit'"
          v-model="form.content"
          type="textarea"
          :rows="16"
          maxlength="20000"
          placeholder="支持 Markdown：**加粗**、# 标题、- 列表、`代码`，图片用 ![说明](图片地址)"
        />
        <!-- 预览用与详情页同一套渲染函数，避免出现「预览好看、发出来不一样」 -->
        <div v-else class="preview markdown-body prose">
          <div v-if="preview" v-html="preview"></div>
          <p v-else class="text-muted">还没有内容</p>
        </div>
      </el-form-item>

      <el-button type="primary" :loading="submitting" @click="submit">发布</el-button>
    </el-form>
  </div>
</template>

<style scoped>
.create {
  max-width: 840px;
  margin: 4px auto;
  padding: 26px 28px 28px;
}

.title {
  margin: 0 0 22px;
  font-size: 18px;
  font-weight: 600;
}

.board-select {
  width: 260px;
}

.content-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.preview {
  width: 100%;
  min-height: 360px;
  padding: 14px 16px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--radius-ctl);
}

@media (max-width: 767px) {
  .create {
    padding: 20px 16px 22px;
  }

  .board-select {
    width: 100%;
  }
}
</style>
