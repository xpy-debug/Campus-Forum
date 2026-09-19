<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { cancelSeckillOrder, listMySeckillOrders, paySeckillOrder } from '@/api/seckill'
import { MALL_GOODS_TYPE, PAGE_SIZE, SECKILL_ORDER_STATUS, mapOr } from '@/constants'

const orders = ref([])
const cursor = ref('')
const hasMore = ref(false)
const loading = ref(false)
const acting = ref(null)

/** 每秒推进一次，用于待支付订单的倒计时 */
const nowMs = ref(Date.now())
let ticker = null

async function load(reset) {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const page = await listMySeckillOrders({
      cursor: reset ? undefined : cursor.value || undefined,
      size: PAGE_SIZE
    })
    orders.value = reset ? page.list : [...orders.value, ...page.list]
    cursor.value = page.nextCursor || ''
    hasMore.value = page.hasMore
  } finally {
    loading.value = false
  }
}

/**
 * 支付。
 *
 * 二次确认里写明「用多少积分」，与商城兑换同一个理由：
 * 积分是用户攒出来的，扣多少要在按下确认之前看到。
 */
async function onPay(order) {
  try {
    await ElMessageBox.confirm(
      `将消耗 ${order.pointsCost} 积分完成支付。支付后积分不可退回（除非管理员取消订单）。`,
      '确认支付',
      { type: 'warning', confirmButtonText: '确认支付', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  acting.value = order.orderNo
  try {
    await paySeckillOrder(order.orderNo)
    ElMessage.success('支付成功，等待管理员发放')
    await load(true)
  } catch {
    // 积分不足（16002）等失败原因由请求拦截器提示；
    // 订单仍是「待支付」，用户可以充值积分后再来，或等它超时自动关闭
  } finally {
    acting.value = null
  }
}

/**
 * 取消。
 *
 * 确认框里要明确说出**取消之后不能再抢**——这是本产品的规则
 * （每人限购 1 件是活动期内的一次性资格），用户必须知道这个后果。
 * 只说「库存会释放」会让人以为还能再来一次。
 */
async function onCancel(order) {
  try {
    await ElMessageBox.confirm(
      `取消后库存会释放，但本场活动的抢购资格不再恢复（每人限购 1 件）。积分尚未扣除，不会涉及退款。`,
      '确认取消',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  acting.value = order.orderNo
  try {
    await cancelSeckillOrder(order.orderNo)
    ElMessage.success('已取消')
    await load(true)
  } catch {
    // 已超时或已支付时后端返回 15013，拦截器会提示
  } finally {
    acting.value = null
  }
}

function payDeadline(order) {
  const end = new Date(order.expireTime).getTime()
  const left = end - nowMs.value
  if (left <= 0) {
    return '即将关闭'
  }
  const total = Math.floor(left / 1000)
  const m = String(Math.floor(total / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return `剩余 ${m}:${s}`
}

onMounted(() => {
  load(true)
  ticker = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (ticker) {
    clearInterval(ticker)
  }
})
</script>

<template>
  <div>
    <PageHeader title="我的秒杀" :back-to="{ name: 'mall' }" back-text="返回商城" />

    <el-skeleton v-if="loading && !orders.length" :rows="3" animated class="card skeleton" />

    <el-empty v-else-if="!orders.length" :image-size="88" description="还没有秒杀记录" />

    <div v-for="order in orders" :key="order.id" class="card order">
      <div class="order-main">
        <div class="order-head">
          <span class="order-name">{{ order.goodsName }}</span>
          <el-tag size="small" :type="mapOr(MALL_GOODS_TYPE, order.goodsType).tag">
            {{ mapOr(MALL_GOODS_TYPE, order.goodsType).label }}
          </el-tag>
          <el-tag size="small" :type="mapOr(SECKILL_ORDER_STATUS, order.status).tag">
            {{ order.statusName }}
          </el-tag>
        </div>

        <div class="order-meta text-muted">
          <span>订单号 {{ order.orderNo }}</span>
          <span class="points">消耗 {{ order.pointsCost }} 积分</span>
          <span>下单 {{ order.createTime }}</span>
          <span v-if="order.payTime">支付 {{ order.payTime }}</span>
          <span v-if="order.finishTime">发放 {{ order.finishTime }}</span>
          <span v-if="order.closeTime">关闭 {{ order.closeTime }}</span>
        </div>
      </div>

      <div class="order-actions">
        <!-- 待支付时同时给出倒计时：超时后订单会由服务端自动关闭并释放库存，
             用户需要在它消失之前知道自己还剩多少时间 -->
        <span v-if="order.payable" class="countdown text-muted">{{ payDeadline(order) }}</span>

        <!-- payable / cancellable 都由服务端算好，前端不按 status 自己判断：
             规则只有一处，改了状态机也不会漏改这里 -->
        <el-button
          v-if="order.payable"
          type="primary"
          size="small"
          :loading="acting === order.orderNo"
          @click="onPay(order)"
        >
          用积分支付
        </el-button>
        <el-button
          v-if="order.cancellable"
          text
          type="danger"
          size="small"
          :loading="acting === order.orderNo"
          @click="onCancel(order)"
        >
          取消
        </el-button>
      </div>
    </div>

    <div v-if="hasMore" class="more">
      <el-button :loading="loading" @click="load(false)">加载更多</el-button>
    </div>
  </div>
</template>

<style scoped>
.skeleton {
  padding: 24px;
}

.order {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  margin-bottom: 10px;
}

.order-main {
  min-width: 0;
}

.order-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.order-name {
  font-size: 15px;
  font-weight: 600;
}

.order-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

/* 消耗的积分是这一行里最该被看见的数字，单独给积分色 */
.points {
  color: var(--points);
}

.order-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.countdown {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.more {
  padding: 12px;
  text-align: center;
}

@media (max-width: 767px) {
  .order {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
