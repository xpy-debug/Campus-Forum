<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import {
  adminCancelOrder,
  adminChangeGoodsStatus,
  adminCreateGoods,
  adminFinishOrder,
  adminListGoods,
  adminListOrders,
  adminUpdateGoods
} from '@/api/mall'
import { MALL_GOODS_STATUS, MALL_GOODS_TYPE, MALL_ORDER_STATUS, PAGE_SIZE, mapOr } from '@/constants'

const tab = ref('goods')

// ==================== 商品 ====================

const goods = ref([])
const goodsTotal = ref(0)
const goodsPage = ref(1)
const goodsStatus = ref(null)
const goodsLoading = ref(false)

const dialogVisible = ref(false)
const editingId = ref(null)
const submitting = ref(false)
const formRef = ref()

/**
 * 表单字段与后端 MallGoodsForm 一一对应。
 *
 * <p>这里用的是**完整对象**而不是「只传改动的字段」：后端对 null 的约定是
 * 「不修改」，而表单里每个字段都有输入框，用户改没改都应当以表单显示的值为准。
 * 唯一例外是库存——编辑时后端不接受库存变更（在途兑换会让直接改库存算错），
 * 因此编辑态的库存输入框是禁用的，提交时也不带上它。
 */
const form = reactive({
  name: '',
  coverImage: '',
  description: '',
  type: 1,
  pointsPrice: 100,
  stock: 0,
  status: 0,
  sortOrder: 0
})

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择商品类型', trigger: 'change' }],
  pointsPrice: [{ required: true, message: '请输入兑换所需积分', trigger: 'blur' }]
}

async function loadGoods() {
  goodsLoading.value = true
  try {
    const page = await adminListGoods({
      // 空值不传：后端用「参数为 null」表示全部，传空字符串会被当成非法状态值
      status: goodsStatus.value ?? undefined,
      page: goodsPage.value,
      size: PAGE_SIZE
    })
    goods.value = page.list
    goodsTotal.value = Number(page.total ?? 0)
  } finally {
    goodsLoading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    name: '',
    coverImage: '',
    description: '',
    type: 1,
    pointsPrice: 100,
    stock: 0,
    // 新建默认草稿：商品往往要分几步配好，先上架会让用户看到半成品
    status: 0,
    sortOrder: 0
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    coverImage: row.coverImage,
    description: row.description,
    type: row.type,
    pointsPrice: row.pointsPrice,
    stock: row.stock,
    status: row.status,
    sortOrder: row.sortOrder
  })
  dialogVisible.value = true
}

async function submitForm() {
  await formRef.value.validate()
  submitting.value = true
  try {
    const payload = { ...form }
    if (editingId.value) {
      // 编辑时不提交库存：库存由兑换与取消的条件更新维护，
      // 后台直接改会与在途的兑换互相覆盖
      delete payload.stock
      await adminUpdateGoods(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await adminCreateGoods(payload)
      ElMessage.success('已创建，默认为草稿状态')
    }
    dialogVisible.value = false
    await loadGoods()
  } finally {
    submitting.value = false
  }
}

async function changeStatus(row, status) {
  await adminChangeGoodsStatus(row.id, status)
  ElMessage.success('状态已更新')
  await loadGoods()
}

// ==================== 兑换订单 ====================

const orders = ref([])
const ordersTotal = ref(0)
const ordersPage = ref(1)
const ordersStatus = ref(null)
const ordersLoading = ref(false)

async function loadOrders() {
  ordersLoading.value = true
  try {
    const page = await adminListOrders({
      status: ordersStatus.value ?? undefined,
      page: ordersPage.value,
      size: PAGE_SIZE
    })
    orders.value = page.list
    ordersTotal.value = Number(page.total ?? 0)
  } finally {
    ordersLoading.value = false
  }
}

/**
 * 发货。
 *
 * <p>用 prompt 收集发放备注（快递单号、领取地点）而不是直接提交：
 * 这张备注是后续对账与用户询问时的唯一凭据，而它的内容只有经手人知道。
 * 用户点取消时视为放弃操作，不提交空备注。
 */
async function finish(order) {
  let remark
  try {
    const result = await ElMessageBox.prompt('填写发放说明（快递单号、领取地点等），可留空', '标记已发放', {
      confirmButtonText: '确认发放',
      cancelButtonText: '取消',
      inputPlaceholder: '可留空'
    })
    remark = result.value
  } catch {
    return
  }
  await adminFinishOrder(order.id, remark || undefined)
  ElMessage.success('已标记发放')
  await loadOrders()
}

async function cancel(order) {
  let remark
  try {
    const result = await ElMessageBox.prompt(
      `将退回 ${order.pointsCost} 积分给用户，并释放一件库存。请填写取消原因。`,
      '确认取消订单',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '再想想' }
    )
    remark = result.value
  } catch {
    return
  }
  await adminCancelOrder(order.id, remark || undefined)
  ElMessage.success('已取消，积分与库存已退回')
  await loadOrders()
}

function onTabChange(name) {
  // 切页签时才拉数据：管理页的两个列表都可能很大，没必要一进来就查两次
  if (name === 'goods' && !goods.value.length) {
    loadGoods()
  }
  if (name === 'orders' && !orders.value.length) {
    loadOrders()
  }
}

onMounted(loadGoods)
</script>

<template>
  <div>
    <PageHeader
      title="商城管理"
      subtitle="只有管理员能看到本页面。后端的写接口全部由 @RequireRole(ROLE_ADMIN) 拦截，普通用户即便直接构造请求也会被拒绝并记录日志。"
    />

    <el-tabs v-model="tab" class="card tabs" @tab-change="onTabChange">
      <!-- ==================== 商品 ==================== -->
      <el-tab-pane label="商品管理" name="goods">
        <div class="toolbar">
          <el-select v-model="goodsStatus" placeholder="全部状态" clearable style="width: 140px" @change="((goodsPage = 1), loadGoods())">
            <el-option v-for="(item, key) in MALL_GOODS_STATUS" :key="key" :label="item.label" :value="Number(key)" />
          </el-select>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建商品</el-button>
        </div>

        <el-table :data="goods" v-loading="goodsLoading" row-key="id">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="mapOr(MALL_GOODS_TYPE, row.type).tag">
                {{ mapOr(MALL_GOODS_TYPE, row.type).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="pointsPrice" label="积分" width="90" />
          <el-table-column label="库存/已兑" width="110">
            <template #default="{ row }">{{ row.stock }} / {{ row.soldCount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="mapOr(MALL_GOODS_STATUS, row.status).tag">
                {{ mapOr(MALL_GOODS_STATUS, row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="权重" width="80" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.status !== 1" text type="success" @click="changeStatus(row, 1)">上架</el-button>
              <el-button v-if="row.status === 1" text type="warning" @click="changeStatus(row, 2)">下架</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="goodsPage"
          :page-size="PAGE_SIZE"
          :total="goodsTotal"
          layout="prev, pager, next, total"
          class="pager"
          @current-change="loadGoods"
        />
      </el-tab-pane>

      <!-- ==================== 订单 ==================== -->
      <el-tab-pane label="兑换订单" name="orders">
        <div class="toolbar">
          <el-select v-model="ordersStatus" placeholder="全部状态" clearable style="width: 140px" @change="((ordersPage = 1), loadOrders())">
            <el-option v-for="(item, key) in MALL_ORDER_STATUS" :key="key" :label="item.label" :value="Number(key)" />
          </el-select>
        </div>

        <el-table :data="orders" v-loading="ordersLoading" row-key="id">
          <el-table-column prop="orderNo" label="订单号" width="180" />
          <el-table-column label="下单人" width="130">
            <template #default="{ row }">
              <!-- 用户注销后昵称为 null，显示 ID 让人至少能定位到是谁 -->
              {{ row.nickname || `用户 ${row.userId}` }}
            </template>
          </el-table-column>
          <el-table-column prop="goodsName" label="商品" min-width="120" show-overflow-tooltip />
          <el-table-column prop="pointsCost" label="积分" width="80" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="mapOr(MALL_ORDER_STATUS, row.status).tag">
                {{ mapOr(MALL_ORDER_STATUS, row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="下单时间" width="160" />
          <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 0">
                <el-button text type="primary" @click="finish(row)">发货</el-button>
                <el-button text type="danger" @click="cancel(row)">取消</el-button>
              </template>
              <span v-else class="text-muted">无</span>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="ordersPage"
          :page-size="PAGE_SIZE"
          :total="ordersTotal"
          layout="prev, pager, next, total"
          class="pager"
          @current-change="loadOrders"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 商品表单 ==================== -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑商品' : '新建商品'"
      width="560px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio v-for="(item, key) in MALL_GOODS_TYPE" :key="key" :value="Number(key)">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="所需积分" prop="pointsPrice">
          <el-input-number v-model="form.pointsPrice" :min="1" :max="1000000" />
          <span class="hint text-muted">不能为 0：0 分商品会让「积分不足」的判断形同虚设</span>
        </el-form-item>
        <el-form-item label="库存">
          <el-input-number v-model="form.stock" :min="0" :disabled="Boolean(editingId)" />
          <span v-if="editingId" class="hint text-muted">
            库存由兑换与取消自动维护，不在这里修改
          </span>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio v-for="(item, key) in MALL_GOODS_STATUS" :key="key" :value="Number(key)">
              {{ item.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排序权重">
          <el-input-number v-model="form.sortOrder" :min="-9999" :max="9999" />
          <span class="hint text-muted">越大越靠前</span>
        </el-form-item>
        <el-form-item label="封面图">
          <el-input v-model="form.coverImage" placeholder="图片地址，可留空" maxlength="512" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="1024" show-word-limit />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">保存</el-button>
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
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.pager {
  justify-content: flex-end;
  margin-top: 16px;
}

.hint {
  margin-left: 12px;
  color: var(--ink-3);
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
