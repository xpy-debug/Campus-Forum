package com.school.forum.seckill.service;

import com.school.forum.common.result.PageResult;
import com.school.forum.seckill.event.SeckillOrderEvent;
import com.school.forum.seckill.event.SeckillTimeoutEvent;
import com.school.forum.seckill.vo.SeckillActivityVO;
import com.school.forum.seckill.vo.SeckillOrderVO;
import com.school.forum.seckill.vo.SeckillResultVO;

import java.util.List;

/**
 * 秒杀：抢购、落库、支付、取消、超时回补、预热与对账。
 *
 * <p><b>为什么读写放在同一个服务里。</b>秒杀的正确性来自「Redis 与 DB 的两份状态
 * 按固定顺序相互校正」：抢购只写 Redis，落库只写 DB，取消两边都写。
 * 把读与写拆到两个类里，等于把这条时序线切成两段，
 * 而调用方就成了唯一知道「谁先谁后」的地方——那正是最容易写错的部分。
 *
 * <p><b>两类调用方，两种语义：</b>
 * <ul>
 *   <li>用户请求（{@link #grab}、{@link #pay}、{@link #cancelByOwner}）：
 *       失败必须让用户知道原因，因此抛 {@code BizException}；</li>
 *   <li>MQ 消费者与定时任务（{@link #createOrder}、{@link #closeTimeout}、
 *       {@link #scanTimeoutOrders}）：<b>必须幂等且不抛异常</b>——
 *       抛出去会被当成「系统异常」而重试，但这些场景重试多少次结果都一样，
 *       只会白占消费线程、拖慢整条链路。</li>
 * </ul>
 */
public interface SeckillService {

    // ==================== 用户侧 ====================

    /**
     * 秒杀活动列表（用户侧）。
     *
     * <p>顺序固定为「进行中 → 即将开始 → 已结束」，同组内按开始时间倒序：
     * 用户打开页面最想知道的是「现在能抢什么」，而不是「历史上发生过什么」。
     */
    List<SeckillActivityVO> listActivities();

    /**
     * 抢购。
     *
     * <p>全同步完成「校验 + Redis 原子预扣 + 写本地消息表」，然后立即返回
     * 「排队中」与订单号。订单的落库由 MQ 消费者异步完成。
     *
     * @return {@code PENDING} + orderNo；重复点击返回的是同一张单（幂等重放）
     * @throws com.school.forum.common.exception.BizException
     *         15003 未开始 / 15004 已结束 / 15005 未预热 / 15001 已抢光 / 15002 已达限购
     */
    SeckillResultVO grab(Long userId, Long activityId);

    /**
     * 查询抢购结果，供前端轮询。
     *
     * <p>不依赖结果键的存活：结果键（TTL 60 秒）过期后仍会回查数据库的订单表
     * （走 {@code uk_user_activity} 索引）。否则用户刷新一次页面就再也看不到
     * 「我抢到了什么」，而订单其实好好地躺在库里。
     */
    SeckillResultVO result(Long userId, Long activityId);

    /**
     * 支付（用积分）。
     *
     * @throws com.school.forum.common.exception.BizException
     *         15012 订单不存在或非本人 / 15013 订单状态异常 / 16002 积分不足
     */
    SeckillOrderVO pay(Long userId, String orderNo);

    /** 取消（仅本人、仅待支付）。重复取消返回 15013 */
    SeckillOrderVO cancelByOwner(Long userId, String orderNo);

    /** 我的秒杀订单（游标分页） */
    PageResult<SeckillOrderVO> myOrders(Long userId, String cursor, int size);

    // ==================== 消费者调用 ====================

    /**
     * 落库：扣 DB 库存 + 生成订单 + 投递超时延迟消息。
     *
     * <p><b>幂等由调用方（消费者）与数据库共同保证</b>：{@code IdempotentGuard} 挡掉
     * 重复的 eventId，{@code uk_order_no} 与 {@code uk_user_activity} 兜底。
     * 因此本方法遇到唯一键冲突时**不吞异常**——让事务回滚（连带撤销已扣的 DB 库存）
     * 才是正确的，调用方捕获后按「已下单」处理即可。
     */
    void createOrder(SeckillOrderEvent event);

    /** 超时关闭并回补库存。幂等：订单已支付或已关闭时静默返回 */
    void closeTimeout(SeckillTimeoutEvent event);

    /**
     * 标记抢购结果为「已生成订单」。
     *
     * <p>由消费者在事务提交之后调用。**唯一键冲突时也要调用它**——
     * {@code DuplicateKeyException} 的含义是「这张单已经在库里了」，
     * 对用户而言与成功没有区别，结果也应当是 SUCCESS。
     *
     * <p>它只写 Redis 的结果键（一个给前端看的状态），因此必须在事务之外调用：
     * 放在事务里的话，事务回滚时这个键不会跟着回滚，
     * 于是前端会看到一个「成功」而数据库里什么都没有。
     */
    void markOrderCreated(Long activityId, Long userId, String orderNo);

    // ==================== 定时任务调用 ====================

    /**
     * 预热即将开始的活动：写入库存、活动快照与预热标记。
     *
     * @return 本次实际完成预热的活动数
     */
    int warmupActivities();

    /** 按时间窗刷新活动状态（未开始 / 进行中 / 已结束） */
    int refreshActivityStatus();

    /**
     * 兜底扫描超时订单并关闭。
     *
     * <p>与延迟消息互补：延迟消息保证及时性，本方法保证可靠性
     * （outbox 积压或消息丢失时，最多晚一个扫描周期）。
     *
     * @return 本次关闭的订单数
     */
    int scanTimeoutOrders();

    /**
     * 库存对账：以 DB 为准修正 Redis 的库存键。
     *
     * <p>{@code force=false}（定时任务）时会跳过<b>正在进行中</b>的活动——
     * 洪峰期间 DB 的 {@code available_stock} 落后于 Redis（消费尚未落库），
     * 此时覆盖 Redis 等于把库存调高，直接造成超卖。
     * {@code force=true}（管理员应急入口）不做这个限制，由人承担后果。
     *
     * @return 是否发生了修正，以及修正前后的值
     */
    ReconcileOutcome reconcile(Long activityId, boolean force);

    /**
     * 对账所有活动（定时任务入口）。
     *
     * @return 本次实际修正过的活动数
     */
    int reconcileAll();

    /**
     * 手动预热单场活动（管理端应急入口）。
     *
     * <p>预热任务连续失败、或活动马上就要开始而定时扫描还没跑到时用它。
     *
     * @return 本次是否为「首次预热」。已预热过返回 {@code false}（快照仍会被刷新）
     */
    boolean warmupOne(Long activityId);

    // ==================== 管理侧 ====================

    /** 标记已发放：已支付 → 已完成 */
    void finish(String orderNo);

    /**
     * 管理员取消订单。
     *
     * <p>未支付的订单直接关闭；已支付的订单<b>退回积分</b>并把库存从
     * {@code sold} 退回 {@code available}。两条路的差别是「积分有没有被扣过」，
     * 因此必须分开处理——合并成一个方法就会埋下「该退分的没退」的隐患。
     */
    void cancelByAdmin(String orderNo, String remark);

    /** 对账结果。{@code fixed=false} 表示本来就一致，无需修正 */
    record ReconcileOutcome(Long activityId,
                            Integer redisStock,
                            Integer dbAvailable,
                            boolean fixed) {
    }
}
