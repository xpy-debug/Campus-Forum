<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Calendar, Coin, Trophy } from '@element-plus/icons-vue'
import { getAccount, listRecords, signin, signinStatus } from '@/api/points'
import { PAGE_SIZE } from '@/constants'

const account = ref({ balance: 0, totalEarned: 0, totalSpent: 0 })
const status = ref(null)
const records = ref([])
const cursor = ref('')
const hasMore = ref(false)

const loading = ref(false)
const signing = ref(false)

/** 月历只展示当前月，不提供翻页：签到是「这个月」的事，能翻到上个月只会让人想问「我能补签吗」 */
const calendarDate = ref(new Date())

const yearMonthText = computed(() => {
  if (!status.value) {
    return ''
  }
  const [year, month] = status.value.yearMonth.split('-')
  return `${year} 年 ${Number(month)} 月`
})

/**
 * 已签日期的集合。
 *
 * <p>用 Set 而不是数组 `includes`：月历每次渲染会对每个格子查一次，
 * 35 个格子 × 31 个日期是 1000 多次比较，Set 是一次哈希。
 */
const signedSet = computed(() => new Set(status.value?.signedDates ?? []))

/** 全勤进度：以阈值为分母。超过阈值就是满格 */
const bonusProgress = computed(() => {
  if (!status.value) {
    return 0
  }
  return Math.min(100, Math.round((status.value.signedDays / status.value.bonusThreshold) * 100))
})

async function loadStatus() {
  status.value = await signinStatus()
  calendarDate.value = new Date()
}

async function loadAccount() {
  account.value = await getAccount()
}

async function loadRecords(reset) {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const page = await listRecords({
      cursor: reset ? undefined : cursor.value || undefined,
      size: PAGE_SIZE
    })
    records.value = reset ? page.list : [...records.value, ...page.list]
    cursor.value = page.nextCursor || ''
    hasMore.value = page.hasMore
  } finally {
    loading.value = false
  }
}

async function onSignin() {
  if (signing.value || status.value?.signedToday) {
    return
  }
  signing.value = true
  try {
    // 重复签到不会走进这里（按钮已禁用），但真发生了也不该报错：
    // 后端返回的是 success=false 的正常结果，提示语直接用它的 message
    const result = await signin()
    if (result.success) {
      ElMessage.success(result.message)
    } else {
      ElMessage.info(result.message)
    }
    await Promise.all([loadStatus(), loadAccount(), loadRecords(true)])
  } finally {
    signing.value = false
  }
}

/**
 * el-calendar 的 data.type 有 current-month / prev-month / next-month 三种。
 * 只有当前月的格子参与签到判定——上下月残留的日期虽然在网格里，
 * 但不属于本次查询的月份，标成已签到会让人以为跨月也能签。
 */
function isCurrentMonth(data) {
  return data.type === 'current-month'
}

onMounted(async () => {
  await Promise.all([loadStatus(), loadAccount(), loadRecords(true)])
})
</script>

<template>
  <div>
    <div class="card overview">
      <div class="balance">
        <span class="label">我的积分</span>
        <span class="value">{{ account.balance }}</span>
      </div>

      <div class="stats">
        <div class="stat">
          <span class="text-muted">累计获得</span>
          <strong>{{ account.totalEarned }}</strong>
        </div>
        <div class="stat">
          <span class="text-muted">累计消耗</span>
          <strong>{{ account.totalSpent }}</strong>
        </div>
        <div class="stat">
          <span class="text-muted">本月签到</span>
          <strong>{{ status?.signedDays ?? 0 }} 天</strong>
        </div>
        <div class="stat">
          <span class="text-muted">连续签到</span>
          <strong>{{ status?.continuousDays ?? 0 }} 天</strong>
        </div>
      </div>

      <el-button
        type="primary"
        size="large"
        :icon="Calendar"
        :loading="signing"
        :disabled="status?.signedToday"
        @click="onSignin"
      >
        {{ status?.signedToday ? '今日已签到' : `签到 +${status?.signinPoints ?? 0}` }}
      </el-button>
    </div>

    <div v-if="status" class="card bonus">
      <div class="bonus-head">
        <span class="bonus-title">
          <el-icon><Trophy /></el-icon>
          月度全勤：当月签到超过 {{ status.bonusThreshold }} 天，额外奖励 {{ status.bonusPoints }} 积分
        </span>
        <el-tag v-if="status.signedDays > status.bonusThreshold" type="success">已达成</el-tag>
        <el-tag v-else-if="!status.canGetBonus" type="info">本月已无法达成</el-tag>
        <el-tag v-else type="warning">
          还差 {{ status.bonusThreshold + 1 - status.signedDays }} 天
        </el-tag>
      </div>
      <el-progress :percentage="bonusProgress" :show-text="false" :stroke-width="10" />
      <p class="text-muted tip">
        奖励在次月初由系统自动发放到账户，无需手动领取。
        <!-- 说明「为什么月初才发」而不是让用户以为是漏发了 -->
      </p>
    </div>

    <div class="card calendar-card">
      <el-calendar v-model="calendarDate">
        <template #header>
          <span class="cal-title">{{ yearMonthText }}</span>
        </template>
        <template #date-cell="{ data }">
          <div
            class="cell"
            :class="{
              signed: signedSet.has(data.day),
              today: data.day === status?.today,
              outside: !isCurrentMonth(data)
            }"
          >
            <span class="cell-day">{{ Number(data.day.slice(-2)) }}</span>
            <span v-if="signedSet.has(data.day)" class="cell-dot" />
          </div>
        </template>
      </el-calendar>
    </div>

    <div class="card records">
      <h3 class="section-title">
        <el-icon><Coin /></el-icon>
        积分明细
      </h3>

      <el-empty v-if="!records.length" :image-size="72" description="还没有积分记录，签到试试" />

      <ul v-else class="record-list">
        <li v-for="record in records" :key="record.id" class="record">
          <div class="record-main">
            <span class="record-type">{{ record.bizTypeName }}</span>
            <span class="text-muted record-remark">{{ record.remark }}</span>
          </div>
          <div class="record-side">
            <span class="record-amount" :class="{ minus: record.changeAmount < 0 }">
              {{ record.changeAmount > 0 ? '+' : '' }}{{ record.changeAmount }}
            </span>
            <span class="text-muted record-balance">余额 {{ record.balanceAfter }}</span>
          </div>
        </li>
      </ul>

      <div v-if="hasMore" class="more">
        <el-button :loading="loading" @click="loadRecords(false)">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 22px 24px;
  margin-bottom: 12px;
}

.balance {
  display: flex;
  flex-direction: column;
}

.balance .label {
  color: var(--ink-2);
  font-size: 13px;
}

.balance .value {
  color: var(--brand);
  font-size: 34px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
}

.stats {
  display: flex;
  flex: 1;
  gap: 28px;
}

.stat {
  display: flex;
  flex-direction: column;
  font-size: 13px;
}

.stat strong {
  color: var(--ink);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.bonus {
  padding: 18px 24px;
  margin-bottom: 12px;
}

.bonus-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.bonus-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.tip {
  margin: 10px 0 0;
  font-size: 12px;
}

.calendar-card {
  padding: 8px 12px;
  margin-bottom: 12px;
}

.cal-title {
  font-weight: 600;
}

.cell {
  position: relative;
  display: grid;
  place-items: center;
  height: 100%;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

/* 上下月残留的格子淡化，但仍保留位置——直接隐藏会让月历的星期对齐错位 */
.cell.outside {
  color: var(--ink-3);
}

.cell.today {
  outline: 1px solid var(--brand);
}

.cell.signed {
  color: var(--brand-contrast);
  background: var(--brand-solid);
}

.cell-dot {
  position: absolute;
  bottom: 4px;
  width: 4px;
  height: 4px;
  background: currentcolor;
  border-radius: 50%;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 18px 24px 10px;
  font-size: 15px;
  font-weight: 600;
}

.record-list {
  margin: 0;
  padding: 0 24px 10px;
  list-style: none;
}

.record {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 0;
  border-bottom: 1px solid var(--line);
}

.record:last-child {
  border-bottom: none;
}

.record-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.record-type {
  font-weight: 500;
  white-space: nowrap;
}

.record-remark {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-side {
  display: flex;
  align-items: baseline;
  gap: 12px;
  white-space: nowrap;
}

.record-amount {
  color: var(--ok);
  font-size: 16px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.record-amount.minus {
  color: var(--danger);
}

.record-balance {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.more {
  padding: 12px 24px 20px;
  text-align: center;
}

@media (max-width: 767px) {
  .overview {
    flex-wrap: wrap;
    gap: 18px;
    padding: 18px 18px;
  }

  .stats {
    flex-basis: 100%;
    flex-wrap: wrap;
    gap: 18px 24px;
  }

  .record-list {
    padding: 0 18px 10px;
  }

  .section-title {
    padding: 16px 18px 10px;
  }
}
</style>
