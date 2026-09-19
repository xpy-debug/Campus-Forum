<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { adminListGoods } from '@/api/mall'
import {
  adminCancelSeckillOrder,
  adminChangeActivityStatus,
  adminCreateActivity,
  adminFinishSeckillOrder,
  adminListActivities,
  adminListSeckillOrders,
  adminReconcileActivity,
  adminUpdateActivity,
  adminWarmupActivity
} from '@/api/seckill'
import {
  MALL_GOODS_TYPE,
  PAGE_SIZE,
  SECKILL_ACTIVITY_STATUS,
  SECKILL_ORDER_STATUS,
  mapOr
} from '@/constants'

const tab = ref('activities')

// ==================== 活动 ====================

const activities = ref([])
const activityTotal = ref(0)
const activityPage = ref(1)
const activityStatus = ref(null)
const activityLoading = ref(false)

const goodsOptions = ref([])

const dialogVisible = ref(false)
const editing = ref(null)
const form = reactive({
  goodsId: null,
  name: '',
  pointsCost: null,
  totalStock: 100,
  perUserLimit: 1,
  startTime: '',
  endTime: '',
  payTimeoutSec: 900,
  warmupMinutes: 10,
  status: 0
})

async function loadActivities(page = activityPage.value) {
  activityLoading.value = true
  try {
    const result = await adminListActivities({
      status: activityStatus.value === null ? undefined : activityStatus.value,
      page,
      size: PAGE_SIZE
    })
    activities.value = result.list
    activityTotal.value = result.total
    activityPage.value = page
  } finally {
    activityLoading.value = false
  }
}

async function loadGoodsOptions() {
  // 商品下拉只需要 id 与名称，拿一页足够——校园商城的商品数量是个位到几十
  const result = await adminListGoods({ page: 1, size: 100 })
  goodsOptions.value = result.list
}

function openCreate() {
  editing.value = null
  const now = new Date()
  const later = new Date(now.getTime() + 60 * 60 * 1000)
  Object.assign(form, {
    goodsId: null,
    name: '',
    pointsCost: null,
    totalStock: 100,
    perUserLimit: 1,
    startTime: toText(now),
    endTime: toText(later),
    payTimeoutSec: 900,
    warmupMinutes: 10,
    status: 0
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    goodsId: row.goodsId,
    name: row.name,
    pointsCost: row.pointsCost,
    // 库存编辑时不可改（后端会忽略），这里保留展示避免看起来像「被清空了」
    totalStock: row.totalStock,
    perUserLimit: row.perUserLimit,
    startTime: row.startTime,
    endTime: row.endTime,
    payTimeoutSec: row.payTimeoutSec,
    warmupMinutes: row.warmupMinutes,
    status: row.status
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.goodsId) {
    ElMessage.warning('请选择商品')
    return
  }
  // 编辑时不提交库存：后端刻意不允许通过编辑调整总量（会让库存恒等式失去基准）
  const payload = { ...form }
  if (editing.value) {
    delete payload.totalStock
  }
  if (editing.value) {
    await adminUpdateActivity(editing.value.id, payload)
  } else {
    await adminCreateActivity(payload)
  }
  dialogVisible.value = false
  ElMessage.success(editing.value ? '已保存' : '已创建（默认未开始，预热后用户可抢）')
  await loadActivities()
}

async function changeStatus(row, status) {
  await adminChangeActivityStatus(row.id, status)
  ElMessage.success('已更新')
  await loadActivities()
}

async function warmup(row) {
  const first = await adminWarmupActivity(row.id)
  ElMessage.success(first ? '预热完成' : '此前已预热，快照已刷新')
}

/**
 * 强制对账。
 *
 * 提示里说明「不跳过进行中的活动」是必要的：定时任务会跳过它们以避免
 * 洪峰期间用落后的 DB 值覆盖 Redis 而超卖，人工调用等于接手这个风险。
 */
async function reconcile(row) {
  try {
    await ElMessageBox.confirm(
      '对账会以数据库为准覆盖 Redis 的库存。进行中的活动若仍有请求在途' +
        '（本地消息表里还有待落库的秒杀消息），覆盖会导致超卖。确认当前没有在途请求后再继续。',
      '确认强制对账',
      { type: 'warning', confirmButtonText: '确认对账', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const result = await adminReconcileActivity(row.id)
  if (result.fixed) {
    ElMessage.warning(`已修正：Redis ${result.redisStock} → ${result.dbAvailable}`)
  } else {
    ElMessage.success('库存一致，无需修正')
  }
  await loadActivities()
}

// ==================== 订单 ====================

const orders = ref([])
const orderTotal = ref(0)
const orderPage = ref(1)
const orderStatus = ref(null)
const orderLoading = ref(false)

async function loadOrders(page = orderPage.value) {
  orderLoading.value = true
  try {
    const result = await adminListSeckillOrders({
      status: orderStatus.value === null ? undefined : orderStatus.value,
      page,
      size: PAGE_SIZE
    })
    orders.value = result.list
    orderTotal.value = result.total
    orderPage.value = page
  } finally {
    orderLoading.value = false
  }
}

async function finishOrder(row) {
  try {
    const { value } = await ElMessageBox.prompt('发放说明（领取地点、快递单号等，可留空）', '标记已发放', {
      confirmButtonText: '确认发放',
      cancelButtonText: '取消',
      inputValue: ''
    })
    await adminFinishSeckillOrder(row.orderNo, value || undefined)
    ElMessage.success('已标记发放')
    await loadOrders()
  } catch {
    // 用户取消，或后端返回状态异常（15013）
  }
}

async function cancelOrder(row) {
  try {
    const { value } = await ElMessageBox.prompt('取消原因', '取消订单', {
      type: 'warning',
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：实物已无库存'
    })
    await adminCancelSeckillOrder(row.orderNo, value || undefined)
    ElMessage.success(row.status === 0 ? '已取消' : '已取消并退回积分')
    await loadOrders()
  } catch {
    // 用户取消，或后端返回状态异常
  }
}

function toText(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

onMounted(() => {
  loadActivities()
  loadGoodsOptions()
  loadOrders()
})
</script>

<template>
  <div>
    <PageHeader title="秒杀管理" subtitle="活动、库存与订单" />

    <el-tabs v-model="tab" class="card tabs">
      <!-- ==================== 活动 ==================== -->
      <el-tab-pane label="活动" name="activities">
        <div class="toolbar">
          <el-select v-model="activityStatus" placeholder="全部状态" clearable style="width: 160px">
            <el-option
              v-for="(item, key) in SECKILL_ACTIVITY_STATUS"
              :key="key"
              :label="item.label"
              :value="Number(key)"
            />
          </el-select>
          <el-button @click="loadActivities(1)">查询</el-button>
          <div class="spacer" />
          <el-button type="primary" @click="openCreate">新建秒杀活动</el-button>
        </div>

        <el-table v-loading="activityLoading" :data="activities" border>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="活动" min-width="180" />
          <el-table-column label="商品" min-width="150">
            <template #default="{ row }">
              <span>{{ row.goodsName }}</span>
              <el-tag size="small" class="ml8" :type="mapOr(MALL_GOODS_TYPE, row.goodsType).tag">
                {{ mapOr(MALL_GOODS_TYPE, row.goodsType).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="pointsCost" label="秒杀价" width="90" />
          <!-- 三层库存全给出来：管理员才能区分「还剩多少能卖」与「多少被未支付订单占着」 -->
          <el-table-column label="库存（可售/锁定/已售）" width="170">
            <template #default="{ row }">
              {{ row.availableStock }} / {{ row.lockedStock }} / {{ row.soldCount }}
              <span class="text-muted">（共 {{ row.totalStock }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="时间窗" min-width="200">
            <template #default="{ row }">
              <div class="text-muted small">{{ row.startTime }}</div>
              <div class="text-muted small">{{ row.endTime }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="mapOr(SECKILL_ACTIVITY_STATUS, row.status).tag">
                {{ row.statusName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="290">
            <template #default="{ row }">
              <el-button
                v-if="row.status !== 1"
                link
                type="success"
                @click="changeStatus(row, 1)"
              >
                上线
              </el-button>
              <el-button
                v-if="row.status !== 3"
                link
                type="info"
                @click="changeStatus(row, 3)"
              >
                下线
              </el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="warning" @click="warmup(row)">预热</el-button>
              <el-button link @click="reconcile(row)">对账</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="prev, pager, next, total"
            :total="Number(activityTotal)"
            :page-size="PAGE_SIZE"
            :current-page="activityPage"
            @current-change="loadActivities"
          />
        </div>
      </el-tab-pane>

      <!-- ==================== 订单 ==================== -->
      <el-tab-pane label="订单" name="orders">
        <div class="toolbar">
          <el-select v-model="orderStatus" placeholder="全部状态" clearable style="width: 160px">
            <el-option
              v-for="(item, key) in SECKILL_ORDER_STATUS"
              :key="key"
              :label="item.label"
              :value="Number(key)"
            />
          </el-select>
          <el-button @click="loadOrders(1)">查询</el-button>
        </div>

        <el-table v-loading="orderLoading" :data="orders" border>
          <el-table-column prop="orderNo" label="订单号" width="170" />
          <el-table-column prop="goodsName" label="商品" min-width="140" />
          <el-table-column label="下单人" min-width="120">
            <template #default="{ row }">
              <span>{{ row.nickname || '已注销用户' }}</span>
              <span class="text-muted small">（{{ row.userId }}）</span>
            </template>
          </el-table-column>
          <el-table-column prop="pointsCost" label="积分" width="80" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="mapOr(SECKILL_ORDER_STATUS, row.status).tag">
                {{ row.statusName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="expireTime" label="支付截止" width="170" />
          <el-table-column prop="createTime" label="下单时间" width="170" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button v-if="row.status === 1" link type="primary" @click="finishOrder(row)">
                标记发放
              </el-button>
              <el-button
                v-if="row.status === 0 || row.status === 1 || row.status === 5"
                link
                type="danger"
                @click="cancelOrder(row)"
              >
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="prev, pager, next, total"
            :total="Number(orderTotal)"
            :page-size="PAGE_SIZE"
            :current-page="orderPage"
            @current-change="loadOrders"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 新建 / 编辑 ==================== -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑秒杀活动' : '新建秒杀活动'"
      width="520px"
    >
      <el-form label-width="110px">
        <el-form-item label="商品" required>
          <!-- 只提交 goodsId：商品名与封面由服务端取回后快照，
               否则一次伪造请求就能造出「不存在的商品」的活动 -->
          <el-select v-model="form.goodsId" placeholder="选择积分商城的商品" style="width: 100%">
            <el-option
              v-for="g in goodsOptions"
              :key="g.id"
              :label="`${g.name}（原价 ${g.pointsPrice} 积分）`"
              :value="g.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="活动名称" required>
          <el-input v-model="form.name" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="秒杀价" required>
          <el-input-number v-model="form.pointsCost" :min="1" :max="1000000" />
        </el-form-item>
        <el-form-item label="活动库存">
          <el-input-number
            v-model="form.totalStock"
            :min="1"
            :max="1000000"
            :disabled="!!editing"
          />
          <span v-if="editing" class="text-muted small ml8">库存不可编辑</span>
        </el-form-item>
        <el-form-item label="每人限购">
          <el-input-number v-model="form.perUserLimit" :min="1" :max="10" />
          <span class="text-muted small ml8">当前仅支持 1</span>
        </el-form-item>
        <el-form-item label="开始时间" required>
          <el-date-picker
            v-model="form.startTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间" required>
          <el-date-picker
            v-model="form.endTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="支付超时">
          <el-input-number v-model="form.payTimeoutSec" :min="30" :max="86400" :step="60" />
          <span class="text-muted small ml8">秒</span>
        </el-form-item>
        <el-form-item label="提前预热">
          <el-input-number v-model="form.warmupMinutes" :min="0" :max="1440" />
          <span class="text-muted small ml8">分钟</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option
              v-for="(item, key) in SECKILL_ACTIVITY_STATUS"
              :key="key"
              :label="item.label"
              :value="Number(key)"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tabs {
  padding: 6px 20px 20px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.spacer {
  flex: 1;
}

.pager {
  padding: 12px 0;
  text-align: right;
}

.ml8 {
  margin-left: 8px;
}

.small {
  font-size: 12px;
}

/* 表格在窄屏下横向滚动，而不是把列压扁 */
@media (max-width: 767px) {
  .tabs {
    padding: 6px 12px 16px;
  }

  .toolbar {
    flex-wrap: wrap;
  }
}
</style>
