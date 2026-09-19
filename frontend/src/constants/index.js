/**
 * 后端枚举在前端的映射表。
 *
 * <p>集中放在这里，而不是散落在各个模板的条件表达式里：同一个状态值
 * 可能在列表、详情、管理页三处出现，三处各写一遍映射，改文案时必然漏掉一处。
 */

/** 帖子类型，对应 Post.TYPE_* */
export const POST_TYPE = {
  0: { label: '', tag: '' },
  1: { label: '置顶', tag: 'danger' },
  2: { label: '精华', tag: 'warning' },
  3: { label: '公告', tag: 'primary' }
}

/** 列表排序方式。与后端 PostSort 的取值为准，多写一个后端不认的值只会静默退回默认排序 */
export const SORT_OPTIONS = [
  { value: 'latest', label: '最新' },
  { value: 'hot', label: '最热' }
]

export const PAGE_SIZE = 20

/** 角色码，与后端 LoginUser.ROLE_* 一致。注意是「最低角色码」的包含关系，不是互斥集合 */
export const ROLE_MODERATOR = 1
export const ROLE_ADMIN = 2

/** 点赞目标类型，与后端 UserLike.TARGET_* 一致 */
export const TARGET_POST = 1
export const TARGET_COMMENT = 2

/** 商品类型，对应 MallGoods.TYPE_*。管理端与用户端共用 */
export const MALL_GOODS_TYPE = {
  1: { label: '优惠券', tag: 'warning' },
  2: { label: '实物', tag: 'danger' },
  3: { label: '虚拟物品', tag: 'success' }
}

/**
 * 商品状态，对应 MallGoods.STATUS_*。
 *
 * <p>只有管理端能看到草稿与已下架的商品——用户侧接口在 SQL 里就按
 * `status = 1` 过滤了，前端不需要也不应该在这里做二次过滤。
 */
export const MALL_GOODS_STATUS = {
  0: { label: '草稿', tag: 'info' },
  1: { label: '上架中', tag: 'success' },
  2: { label: '已下架', tag: 'info' }
}

/** 兑换订单状态，对应 MallOrder.STATUS_* */
export const MALL_ORDER_STATUS = {
  0: { label: '待发放', tag: 'warning' },
  1: { label: '已完成', tag: 'success' },
  2: { label: '已取消', tag: 'info' }
}

/**
 * 秒杀活动状态，对应 SeckillActivity.STATUS_*。
 *
 * 状态由服务端每分钟的刷新任务按时间窗维护，前端不自己按时间算——
 * 两边各算一次，就会在「活动刚开始的那一分钟里」出现列表说进行中、
 * 详情说未开始的矛盾
 */
export const SECKILL_ACTIVITY_STATUS = {
  0: { label: '即将开始', tag: 'warning' },
  1: { label: '进行中', tag: 'success' },
  2: { label: '已结束', tag: 'info' },
  3: { label: '已下线', tag: 'info' }
}

/**
 * 秒杀订单状态，对应 SeckillOrder.STATUS_*。
 *
 * 比商城多一档「待支付」——秒杀有中间态，用户需要在 15 分钟内用积分完成支付。
 * 这也正是秒杀与即时完成的商城兑换在状态机上的唯一区别
 */
export const SECKILL_ORDER_STATUS = {
  0: { label: '待支付', tag: 'warning' },
  1: { label: '待发放', tag: 'success' },
  2: { label: '已取消', tag: 'info' },
  3: { label: '超时关闭', tag: 'info' },
  4: { label: '已退款', tag: 'info' },
  5: { label: '已完成', tag: 'success' }
}

/** 取出映射项，取不到时给一个可读的兜底，避免界面出现空白或 undefined */
export function mapOr(dict, key, fallback = { label: '未知', tag: 'info' }) {
  return dict[key] ?? fallback
}
