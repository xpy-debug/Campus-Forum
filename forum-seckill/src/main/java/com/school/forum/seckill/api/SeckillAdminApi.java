package com.school.forum.seckill.api;

import com.school.forum.common.result.PageResult;

/**
 * 秒杀管理端对 {@code forum-admin} 暴露的契约。
 *
 * <p>与 {@code MallAdminApi} 的约定一致：{@code forum-admin} 只依赖本接口，
 * 而它的每个方法都对应一个必然需要管理员权限的操作。
 * <b>本接口不做权限校验</b>——那由 {@code @RequireRole} 在拦截器层完成（ADR-009）。
 *
 * <p>判断标准同样一致：把 forum-seckill 的实现代码整体删掉、只留 api 包，
 * 项目仍应编译通过。因此这里只出现本包内的 DTO。
 */
public interface SeckillAdminApi {

    /**
     * 活动列表（管理端），**不过滤状态**。
     *
     * @param status 状态筛选，null 表示全部
     * @param page   页码，从 1 开始
     */
    PageResult<SeckillActivityAdminVO> listActivities(Integer status, int page, int size);

    /** 活动详情。不存在时返回 null */
    SeckillActivityAdminVO getActivity(Long activityId);

    /**
     * 新建活动。
     *
     * <p>服务端会根据 {@code form.goodsId} 从积分商城取回商品资料并快照进活动行。
     * 商品不存在时返回 16003。
     *
     * @return 新活动 ID
     */
    Long createActivity(SeckillActivityForm form);

    /**
     * 编辑活动。只覆盖 form 中非 null 的字段。
     *
     * <p><b>库存不在可编辑范围内</b>：{@code totalStock} 仅在新建时生效，
     * 活动开始后改总量会让「总数 = 可售 + 锁定 + 已售」这条恒等式失去基准。
     * 需要调库存应当新建一场活动，或由 DBA 在明确知晓后果的情况下处理。
     */
    void updateActivity(Long activityId, SeckillActivityForm form);

    /** 上下线：0 未开始 / 1 进行中 / 2 已结束 / 3 已下线 */
    void changeActivityStatus(Long activityId, int status);

    /**
     * 手动预热（预热任务失败或活动即将开始而扫描还没跑到时的应急入口）。
     *
     * @return 是否为「本次首次写入库存」。已预热过返回 false（快照仍会刷新）
     */
    boolean warmup(Long activityId);

    /**
     * 强制库存对账。
     *
     * <p>与定时任务的区别是它**不跳过进行中的活动**——定时任务跳过是为了避免
     * 洪峰期间用落后的 DB 值覆盖 Redis 而超卖；人工调用意味着调用者
     * 已经确认当前没有在途请求，愿意承担这个后果。
     */
    ReconcileVO reconcile(Long activityId);

    // ==================== 订单 ====================

    /** 全部秒杀订单。下单人昵称由实现补齐 */
    PageResult<SeckillOrderAdminVO> listOrders(Integer status, int page, int size);

    /** 标记已发放：已支付 → 已完成。状态不符时返回 15013 */
    void finishOrder(String orderNo);

    /**
     * 管理员取消订单。
     *
     * <p>未支付的订单直接关闭；已支付的订单会**退回积分**并把库存从已售退回可售。
     */
    void cancelOrder(String orderNo, String remark);

    /**
     * 对账结果。
     *
     * @param redisStock 修正前 Redis 里的值。null 表示库存键不存在（未预热）
     * @param dbAvailable DB 的 available_stock
     * @param fixed      是否发生了修正
     */
    record ReconcileVO(Long activityId, Integer redisStock, Integer dbAvailable, boolean fixed) {
    }
}
