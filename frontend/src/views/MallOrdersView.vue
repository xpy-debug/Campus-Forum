<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { cancelOrder, listMyOrders } from '@/api/mall'
import { MALL_GOODS_TYPE, MALL_ORDER_STATUS, PAGE_SIZE, mapOr } from '@/constants'

const orders = ref([])
const cursor = ref('')
const hasMore = ref(false)
const loading = ref(false)

async function load(reset) {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const page = await listMyOrders({
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
 * 取消兑换。
 *
 * <p>确认框里写明「积分会退回」：这是用户最关心的一点，
 * 也是他敢不敢点取消的唯一依据。
 */
async function onCancel(order) {
  try {
    await ElMessageBox.confirm(
      `取消后 ${order.pointsCost} 积分将退回账户，商品库存也会一并释放。`,
      '确认取消兑换',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  await cancelOrder(order.id)
  ElMessage.success('已取消，积分已退回')
  // 只刷新这一条也可以，但重新加载第一页更简单，也顺带修正了总数与顺序
  await load(true)
}

onMounted(() => load(true))
</script>

<template>
  <div>
    <PageHeader title="我的兑换" :back-to="{ name: 'mall' }" back-text="返回商城" />

    <el-skeleton v-if="loading && !orders.length" :rows="3" animated class="card skeleton" />

    <el-empty v-else-if="!orders.length" :image-size="88" description="还没有兑换记录" />

    <div v-for="order in orders" :key="order.id" class="card order">
      <div class="order-main">
        <div class="order-head">
          <span class="order-name">{{ order.goodsName }}</span>
          <el-tag size="small" :type="mapOr(MALL_GOODS_TYPE, order.goodsType).tag">
            {{ mapOr(MALL_GOODS_TYPE, order.goodsType).label }}
          </el-tag>
          <el-tag size="small" :type="mapOr(MALL_ORDER_STATUS, order.status).tag">
            {{ order.statusName }}
          </el-tag>
        </div>

        <div class="order-meta text-muted">
          <span>订单号 {{ order.orderNo }}</span>
          <span class="points">消耗 {{ order.pointsCost }} 积分</span>
          <span>下单 {{ order.createTime }}</span>
          <span v-if="order.finishTime">发放 {{ order.finishTime }}</span>
          <span v-if="order.cancelTime">取消 {{ order.cancelTime }}</span>
        </div>
      </div>

      <!-- 能否取消由后端的 cancellable 决定，前端不按 status 自己判断：
           规则只有一处，改了状态机也不会漏改这里 -->
      <el-button v-if="order.cancellable" text type="danger" @click="onCancel(order)">
        取消兑换
      </el-button>
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
