<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ShoppingCart } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { createOrder, listGoods } from '@/api/mall'
import { getAccount } from '@/api/points'
import { getSeckillResult, grab, listSeckillActivities } from '@/api/seckill'
import { MALL_GOODS_TYPE, SECKILL_ACTIVITY_STATUS, mapOr } from '@/constants'

const router = useRouter()

const goods = ref([])
const balance = ref(0)
const loading = ref(false)
const submitting = ref(false)

const dialogVisible = ref(false)
const current = ref(null)
const remark = ref('')

// ==================== 限时秒杀 ====================
// 与上面的「积分商城」并列但完全分开：一个是随时可换，一个是限时限量抢。
// 两套数据来自不同的接口，前端不做任何合并（合并了就没人说得清
// 「还剩几件」到底是 stock 还是 available_stock）

const activities = ref([])
const grabbing = ref(null)
/** 每秒推进一次，用于倒计时。用 ref 而不是直接在模板里调 Date.now()，
 *  否则时间不会自己走，倒计时得等下一次别的刷新才更新 */
const nowMs = ref(Date.now())
let ticker = null

async function load() {
  loading.value = true
  try {
    // 三个请求互不依赖，并发发出：串行会让页面多等两个往返
    const [list, account, seckillList] = await Promise.all([
      listGoods(),
      getAccount(),
      listSeckillActivities()
    ])
    goods.value = list
    balance.value = account.balance
    activities.value = seckillList
  } finally {
    loading.value = false
  }
}

/** 倒计时文案。以服务端返回的时间为准，不用浏览器本地时间——本地时钟可能偏 */
function countdownText(item) {
  const start = new Date(item.startTime).getTime()
  const end = new Date(item.endTime).getTime()
  const now = nowMs.value
  if (now < start) {
    return `距开始 ${humanize(start - now)}`
  }
  if (now <= end) {
    return `剩余 ${humanize(end - now)}`
  }
  return '已结束'
}

function humanize(ms) {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = String(Math.floor(total / 3600)).padStart(2, '0')
  const m = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return `${h}:${m}:${s}`
}

function buttonText(item) {
  if (item.stock === 0) {
    return '已抢完'
  }
  if (item.status === 0) {
    return '即将开始'
  }
  if (item.status === 2) {
    return '已结束'
  }
  return '立即抢购'
}

const canGrab = computed(() => (item) => item.grabbable)

/**
 * 抢购 -> 轮询结果。
 *
 * 抢购接口是「Redis 预扣成功即返回」，订单还没落库，因此拿到 orderNo 之后
 * 必须轮询。这中间用户会看到「排队中」，这是秒杀特有的中间态，
 * 没有它前端只能在「什么都不显示」和「以为失败了」之间二选一。
 */
async function onGrab(item) {
  if (grabbing.value) {
    return
  }
  grabbing.value = item.id
  try {
    await grab(item.id)
    ElMessage.success('抢购成功，正在生成订单…')
    const result = await pollResult(item.id)
    if (!result) {
      ElMessage.warning('订单仍在生成中，可到「我的秒杀」查看')
    } else if (result.status === 'SUCCESS') {
      ElMessage.success('抢到了！请在 15 分钟内完成支付')
      await router.push({ name: 'seckill-orders' })
    } else if (result.status === 'FAILED') {
      ElMessage.error(result.message || '很遗憾，未能抢到')
    }
  } catch {
    // 失败提示由请求拦截器统一弹出（15001 已抢光 / 15002 已达限购 / 15003 未开始 …），
    // 这里只是把它接住，免得变成未处理的 Promise 拒绝
  } finally {
    grabbing.value = null
    await load()
  }
}

/** 首次 200ms，之后每次 +300ms 并封顶 2s，最多 10 次 */
async function pollResult(activityId) {
  let delay = 200
  for (let i = 0; i < 10; i++) {
    await sleep(delay)
    try {
      const r = await getSeckillResult(activityId)
      if (r.status !== 'PENDING') {
        return r
      }
    } catch {
      // 轮询期间的偶发网络抖动不该中断整个流程，下一次再试即可
    }
    delay = Math.min(delay + 300, 2000)
  }
  return null
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function openExchange(item) {
  current.value = item
  remark.value = ''
  dialogVisible.value = true
}

async function submitExchange() {
  if (submitting.value) {
    return
  }
  submitting.value = true
  try {
    await createOrder({ goodsId: current.value.id, remark: remark.value || undefined })
    dialogVisible.value = false
    ElMessage.success('兑换成功，可在「我的兑换」查看进度')
    // 兑换会同时改变余额与库存，两个都要刷新
    await load()
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  load()
  ticker = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
})

// 必须清掉定时器：SPA 里切换路由不会卸载页面进程，留着这个 interval
// 就会一直空转（每个进过商城页的用户都留下一个）
onUnmounted(() => {
  if (ticker) {
    clearInterval(ticker)
  }
})
</script>

<template>
  <div>
    <PageHeader title="积分商城" subtitle="用签到攒下的积分兑换校园福利">
      <template #extra>
        <span class="balance">
          当前积分 <strong>{{ balance }}</strong>
        </span>
        <el-button :icon="ShoppingCart" @click="router.push({ name: 'mall-orders' })">
          我的兑换
        </el-button>
      </template>
    </PageHeader>

    <!-- 限时秒杀：与普通兑换并列但分开的一整套链路（Redis 预扣 + 异步落库 + 待支付） -->
    <section class="card seckill">
      <div class="seckill-head">
        <h3 class="section-title">限时秒杀</h3>
        <p class="text-muted sub">限量福利，先到先得。每人限 1 件，抢到后 15 分钟内用积分支付</p>
        <el-button link type="primary" @click="router.push({ name: 'seckill-orders' })">
          我的秒杀
        </el-button>
      </div>

      <el-empty v-if="!activities.length" :image-size="64" description="暂时没有秒杀活动" />

      <div v-else class="seckill-grid">
        <div v-for="item in activities" :key="item.id" class="seckill-item">
          <div class="seckill-cover">
            <img v-if="item.goodsCover" :src="item.goodsCover" :alt="item.goodsName" />
            <span v-else class="cover-text">{{ (item.goodsName || item.name).slice(0, 2) }}</span>
          </div>

          <div class="seckill-body">
            <div class="seckill-name">
              <span>{{ item.name }}</span>
              <el-tag size="small" :type="mapOr(SECKILL_ACTIVITY_STATUS, item.status).tag">
                {{ item.statusName }}
              </el-tag>
            </div>

            <div class="seckill-meta">
              <span class="price">{{ item.pointsCost }} 积分</span>
              <span class="text-muted stock">
                {{ item.stock > 0 ? `剩 ${item.stock} / ${item.totalStock}` : '已抢完' }}
              </span>
            </div>

            <div class="text-muted countdown">{{ countdownText(item) }}</div>

            <el-button
              type="primary"
              class="action"
              :disabled="!canGrab(item)"
              :loading="grabbing === item.id"
              @click="onGrab(item)"
            >
              {{ buttonText(item) }}
            </el-button>
          </div>
        </div>
      </div>
    </section>

    <el-skeleton v-if="loading" :rows="4" animated class="card skeleton" />

    <el-empty v-else-if="!goods.length" :image-size="88" description="商城还没有上架商品" />

    <div v-else class="grid">
      <div v-for="item in goods" :key="item.id" class="card item lift">
        <div class="cover">
          <img v-if="item.coverImage" :src="item.coverImage" :alt="item.name" />
          <span v-else class="cover-text">{{ item.name.slice(0, 2) }}</span>
        </div>

        <div class="body">
          <div class="head">
            <h3 class="name">{{ item.name }}</h3>
            <el-tag size="small" :type="mapOr(MALL_GOODS_TYPE, item.type).tag">
              {{ mapOr(MALL_GOODS_TYPE, item.type).label }}
            </el-tag>
          </div>

          <p class="desc text-muted">{{ item.description || '暂无描述' }}</p>

          <div class="meta">
            <span class="price">{{ item.pointsPrice }} 积分</span>
            <span class="text-muted stock">
              {{ item.stock > 0 ? `剩余 ${item.stock} 件` : '已兑完' }}，已兑 {{ item.soldCount }}
            </span>
          </div>

          <!--
            库存为 0 与积分不足都是「换不了」，但原因不同：
            前者是「来晚了」，后者是「还差多少分」——后者给的是一个可行动的目标
          -->
          <el-button
            type="primary"
            class="action"
            :disabled="!item.canExchange"
            @click="openExchange(item)"
          >
            <template v-if="item.stock === 0">已兑完</template>
            <template v-else-if="!item.canExchange">还差 {{ item.lackPoints }} 积分</template>
            <template v-else>立即兑换</template>
          </el-button>
        </div>
      </div>
    </div>

    <!--
      兑换的确认信息全部放在这个对话框里，而不是先弹一个 message-box 再弹对话框：
      积分是用户攒出来的，扣多少、扣完还剩多少都要在按下确认前看到，
      而两步确认只会让人闭着眼睛点掉第一步
    -->
    <el-dialog v-model="dialogVisible" title="确认兑换" width="420px">
      <template v-if="current">
        <p class="dialog-line">
          <span class="text-sub">商品</span>
          <strong>{{ current.name }}</strong>
        </p>
        <p class="dialog-line">
          <span class="text-sub">消耗积分</span>
          <strong class="price">{{ current.pointsPrice }}</strong>
        </p>
        <p class="dialog-line">
          <span class="text-sub">兑换后余额</span>
          <strong>{{ balance - current.pointsPrice }}</strong>
        </p>
        <el-input
          v-model="remark"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="留言（实物类请填写联系方式或领取方式，可不填）"
        />
      </template>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitExchange">确认兑换</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.balance {
  color: var(--ink-2);
  font-size: 13px;
}

.balance strong {
  color: var(--brand);
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}

.skeleton {
  padding: 24px;
}

/* ==================== 限时秒杀 ==================== */

.seckill {
  padding: 18px 22px 22px;
  margin-bottom: 12px;
}

.seckill-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.section-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.seckill-head .sub {
  flex: 1;
  margin: 0;
  font-size: 13px;
}

.seckill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 12px;
}

.seckill-item {
  display: flex;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-ctl);
}

.seckill-cover {
  display: grid;
  place-items: center;
  width: 92px;
  flex-shrink: 0;
  color: var(--points);
  background: var(--surface-2);
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--points) 16%, transparent),
    var(--surface-2)
  );
  font-size: 20px;
  font-weight: 600;
}

.seckill-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.seckill-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  min-width: 0;
}

.seckill-name {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.seckill-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.countdown {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

/* ==================== 商品网格 ==================== */

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(268px, 1fr));
  gap: 12px;
}

.item {
  display: flex;
  overflow: hidden;
}

.cover {
  display: grid;
  place-items: center;
  width: 108px;
  flex-shrink: 0;
  color: var(--brand);
  background: linear-gradient(135deg, var(--brand-soft), var(--surface-2));
  font-size: 22px;
  font-weight: 600;
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-text {
  font-size: 20px;
}

.body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  min-width: 0;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.seckill-name span,
.name {
  margin: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.desc {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  margin: 0;
  font-size: 13px;
}

.meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.price {
  color: var(--points);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.stock {
  font-size: 12px;
}

.action {
  margin-top: auto;
}

.dialog-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 0 10px;
}

@media (max-width: 767px) {
  .seckill {
    padding: 16px 16px 18px;
  }

  .seckill-head {
    flex-wrap: wrap;
    gap: 6px;
  }

  .seckill-head .sub {
    flex-basis: 100%;
    order: 3;
  }

  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
