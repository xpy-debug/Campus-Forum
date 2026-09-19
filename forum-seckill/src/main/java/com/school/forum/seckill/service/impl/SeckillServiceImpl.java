package com.school.forum.seckill.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.common.constant.MqTopic;
import com.school.forum.common.constant.RedisKey;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.PageResult;
import com.school.forum.infrastructure.mq.core.EventPublisher;
import com.school.forum.infrastructure.mq.core.MqProperties;
import com.school.forum.infrastructure.mq.mapper.MqOutboxMapper;
import com.school.forum.infrastructure.mq.outbox.MqOutboxMessage;
import com.school.forum.points.api.PointsSpendApi;
import com.school.forum.seckill.config.SeckillProperties;
import com.school.forum.seckill.convert.SeckillConverter;
import com.school.forum.seckill.entity.SeckillActivity;
import com.school.forum.seckill.entity.SeckillOrder;
import com.school.forum.seckill.event.SeckillOrderEvent;
import com.school.forum.seckill.event.SeckillTimeoutEvent;
import com.school.forum.seckill.mapper.SeckillActivityMapper;
import com.school.forum.seckill.mapper.SeckillOrderMapper;
import com.school.forum.seckill.service.SeckillService;
import com.school.forum.seckill.vo.SeckillActivityVO;
import com.school.forum.seckill.vo.SeckillOrderVO;
import com.school.forum.seckill.vo.SeckillResultVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀实现。整体时序见《03-功能设计》第 6 节。
 *
 * <p><b>本类有一个贯穿全篇的约束：Redis 写入一律发生在数据库事务之外。</b>
 * 项目在《04》6.1 节把「禁止在事务中做 Redis 写操作」列为硬性禁止事项，
 * 理由是 Redis 不会跟着事务回滚：事务里写了 Redis、随后回滚，
 * 就得到一个「Redis 说成功、数据库说没发生」的悬空状态。
 *
 * <p>因此本类不使用 {@code @Transactional}，而是显式地用 {@link TransactionTemplate}
 * 圈出纯粹的数据库动作，Redis 的读写都放在模板之外。顺序也是刻意的：
 * <b>抢购时 Redis 在前、DB 在后</b>（Redis 是准入门槛），
 * <b>取消时 DB 在前、Redis 在后</b>（DB 状态是闸门，闸门没放开就不该回补库存）。
 */
@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    /** 订单号里的日期部分：BASIC_ISO_DATE 直接得到 yyyyMMdd */
    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    /** 秒杀订单号前缀，与商城的 {@code M} 区分开，一眼能看出订单来自哪条链路 */
    private static final String ORDER_NO_PREFIX = "S";

    /** 活动快照 Hash 的字段名 */
    private static final String F_STATUS = "status";
    private static final String F_START = "startMs";
    private static final String F_END = "endMs";
    private static final String F_LIMIT = "limit";

    /** 用户维度的抢购限流键前缀（10 秒窗口） */
    private static final String GRAB_LIMIT_PREFIX = RedisKey.PREFIX + "limit:seckill:grab:";

    /** 分页大小的兜底区间。与 {@code PageSizes} 同值，但不跨模块引用它（见下） */
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 50;

    private static final RedisScript<List> GRAB_SCRIPT = loadScript("lua/seckill_grab.lua");

    private final SeckillActivityMapper activityMapper;
    private final SeckillOrderMapper orderMapper;
    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;
    private final PointsSpendApi pointsSpendApi;
    private final MqOutboxMapper outboxMapper;
    private final MqProperties mqProperties;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final SeckillProperties properties;
    private final TransactionTemplate tx;

    public SeckillServiceImpl(SeckillActivityMapper activityMapper,
                              SeckillOrderMapper orderMapper,
                              StringRedisTemplate redis,
                              RedissonClient redissonClient,
                              PointsSpendApi pointsSpendApi,
                              MqOutboxMapper outboxMapper,
                              MqProperties mqProperties,
                              EventPublisher eventPublisher,
                              ObjectMapper objectMapper,
                              SeckillProperties properties,
                              PlatformTransactionManager transactionManager) {
        this.activityMapper = activityMapper;
        this.orderMapper = orderMapper;
        this.redis = redis;
        this.redissonClient = redissonClient;
        this.pointsSpendApi = pointsSpendApi;
        this.outboxMapper = outboxMapper;
        this.mqProperties = mqProperties;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.properties = properties;
        // 显式构造而不是给类加 @Transactional：本类需要精确控制「哪几行在事务里」，
        // 而这个粒度的控制正是注解给不了的（注解只能圈住整个方法）
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ==================== 用户侧 ====================

    @Override
    public List<SeckillActivityVO> listActivities() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillActivity> activities = activityMapper.selectList(
                Wrappers.<SeckillActivity>lambdaQuery()
                        .ne(SeckillActivity::getStatus, SeckillActivity.STATUS_OFFLINE));

        // 排序在内存里做而不是写进 SQL：活动量级是个位数到几十，
        // 而「进行中优先」这种顺序用 ORDER BY 表达需要 CASE WHEN，可读性反而更差
        return activities.stream()
                .sorted(Comparator.comparingInt((SeckillActivity a) -> rank(a, now))
                        .thenComparing(SeckillActivity::getStartTime,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(a -> SeckillConverter.toActivityVO(a, now))
                .toList();
    }

    @Override
    public SeckillResultVO grab(Long userId, Long activityId) {
        Snapshot snapshot = readSnapshot(activityId);
        if (snapshot == null) {
            // 快照缺失：可能是还没预热，也可能是活动根本还没开始/已结束。
            // 这条路径是冷的（正常情况下抢购时快照一定在，用户也不会去点一个
            // 界面上已置灰的按钮），所以值得查一次库把话说准确——
            // 「活动尚未开始」和「活动太火爆」对用户是两件完全不同的事，
            // 前者他知道什么时候再来，后者他只会以为系统坏了
            throw coldPathRejection(activityId);
        }

        long now = System.currentTimeMillis();
        if (snapshot.status() == SeckillActivity.STATUS_OFFLINE) {
            throw new BizException(ErrorCode.ACTIVITY_OFFLINE);
        }
        if (now > snapshot.endMs()) {
            throw new BizException(ErrorCode.SECKILL_ENDED);
        }
        if (now < snapshot.startMs()) {
            throw new BizException(ErrorCode.SECKILL_NOT_STARTED);
        }

        enforceGrabRateLimit(userId);

        String day = LocalDate.now().format(ORDER_NO_DATE);
        List<String> keys = List.of(
                RedisKey.seckillStock(activityId),
                RedisKey.seckillBought(activityId),
                RedisKey.seckillOrderNo(activityId, userId),
                RedisKey.seckillOrderNoSeq(day));

        List<?> result = redis.execute(GRAB_SCRIPT, keys,
                String.valueOf(userId),
                String.valueOf(snapshot.limit()),
                ORDER_NO_PREFIX + day,
                String.valueOf(properties.getOrderNoSeqTtlSeconds()));

        long code = (result == null || result.isEmpty()) ? Long.MIN_VALUE : toLong(result.get(0));

        // 负数状态码是脚本的约定返回值，不是异常（见 seckill_grab.lua 的注释）
        if (code == -1) {
            throw new BizException(ErrorCode.SECKILL_SOLD_OUT);
        }
        if (code == -2) {
            throw new BizException(ErrorCode.SECKILL_ALREADY_JOINED);
        }
        if (code == -3) {
            throw new BizException(ErrorCode.SECKILL_NOT_WARMED_UP);
        }
        if (result == null || result.size() < 2) {
            // 脚本返回了非预期的结构，属于部署或脚本版本不一致，必须让它可见
            log.error("秒杀脚本返回结构异常。activityId={}, userId={}, result={}", activityId, userId, result);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }

        String orderNo = String.valueOf(result.get(1));

        // 本地消息表：与「待发消息」相同的思路——Redis 已经扣了库存，
        // 这一步必须可靠。写在事务里由 MqOutboxDispatcher 补发，
        // 进程在两步之间崩溃时库存会泄漏一件（宁可少卖不可超卖，由对账修正）
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setUserId(userId);
        event.setActivityId(activityId);
        event.setOrderNo(orderNo);
        tx.executeWithoutResult(status -> outboxMapper.insert(toOutboxMessage(event)));

        markResult(activityId, userId, SeckillResultVO.PENDING);
        return SeckillResultVO.pending(orderNo);
    }

    @Override
    public SeckillResultVO result(Long userId, Long activityId) {
        String status = redis.opsForValue().get(RedisKey.seckillResult(activityId, userId));
        if (status == null) {
            // 结果键可能已过期（TTL 只有 60 秒）。
            // 这时不能直接说「没抢到」——订单可能早就落库了，回查一次数据库才是准的。
            // 查询走 uk_user_activity 唯一索引，代价与主键查询同量级
            SeckillOrder order = findOrder(userId, activityId);
            if (order != null) {
                return settled(SeckillResultVO.SUCCESS, order);
            }
            String orderNo = redis.opsForValue().get(RedisKey.seckillOrderNo(activityId, userId));
            return orderNo == null ? SeckillResultVO.none() : SeckillResultVO.pending(orderNo);
        }
        if (SeckillResultVO.PENDING.equals(status)) {
            return SeckillResultVO.pending(
                    redis.opsForValue().get(RedisKey.seckillOrderNo(activityId, userId)));
        }
        if (SeckillResultVO.FAILED.equals(status)) {
            return new SeckillResultVO(SeckillResultVO.FAILED, null, "很遗憾，未能抢到", null);
        }
        return settled(status, findOrder(userId, activityId));
    }

    @Override
    public SeckillOrderVO pay(Long userId, String orderNo) {
        SeckillOrder order = requireOwnOrder(userId, orderNo);
        int points = value(order.getPointsCost());
        int qty = quantity(order);

        tx.executeWithoutResult(status -> {
            // ① 状态机当闸门：只有待支付能付。影响 0 行 = 重复支付 / 已取消 / 非本人
            if (orderMapper.markPaid(orderNo, userId) == 0) {
                throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
            }
            // ② 扣积分。余额不足抛 16002，整笔回滚 —— 订单留在待支付，等超时释放库存。
            //    秒杀链路上只有这一处扣积分，抢购阶段从不碰它（ADR-010）
            pointsSpendApi.spendForSeckill(userId, points, orderNo, order.getGoodsName());
            // ③ 库存三层模型：locked → sold
            if (activityMapper.markSold(order.getActivityId(), qty) == 0) {
                log.error("秒杀支付时库存状态异常，已回滚。orderNo={}, activityId={}",
                        orderNo, order.getActivityId());
                throw new BizException(ErrorCode.STOCK_DEDUCT_FAILED);
            }
        });

        markResult(order.getActivityId(), userId, SeckillResultVO.SUCCESS);
        return SeckillConverter.toOrderVO(findOrderByNo(orderNo));
    }

    @Override
    public SeckillOrderVO cancelByOwner(Long userId, String orderNo) {
        SeckillOrder order = requireOwnOrder(userId, orderNo);
        if (!closeOrder(order, SeckillOrder.STATUS_CANCELLED)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
        }
        return SeckillConverter.toOrderVO(findOrderByNo(orderNo));
    }

    @Override
    public PageResult<SeckillOrderVO> myOrders(Long userId, String cursor, int size) {
        int safeSize = clampPageSize(size);
        // 多查一条用于判断有没有下一页，因此不需要 COUNT
        List<SeckillOrder> rows = orderMapper.selectByCursor(userId, parseCursor(cursor), safeSize + 1);
        return PageResult.ofCursor(rows, safeSize, o -> String.valueOf(o.getId()))
                .map(SeckillConverter::toOrderVO);
    }

    // ==================== 消费者调用 ====================

    @Override
    public void createOrder(SeckillOrderEvent event) {
        Long activityId = event.getActivityId();
        Long userId = event.getUserId();

        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            log.error("秒杀落库时活动不存在，已忽略。activityId={}, orderNo={}", activityId, event.getOrderNo());
            markResult(activityId, userId, SeckillResultVO.FAILED);
            return;
        }

        int qty = 1;
        Boolean created = tx.execute(status -> {
            // ① DB 层防超卖的最后一道防线。条件更新影响 0 行 = Redis 与 DB 漂移
            if (activityMapper.deductStock(activityId, qty) == 0) {
                return false;
            }
            SeckillOrder order = new SeckillOrder();
            order.setOrderNo(event.getOrderNo());
            order.setUserId(userId);
            order.setActivityId(activityId);
            order.setGoodsId(activity.getGoodsId());
            order.setGoodsName(activity.getGoodsName());
            order.setGoodsType(activity.getGoodsType());
            order.setPointsCost(activity.getPointsCost());
            order.setQuantity(qty);
            order.setStatus(SeckillOrder.STATUS_UNPAID);
            order.setExpireTime(LocalDateTime.now()
                    .plusSeconds(activity.getPayTimeoutSec() == null ? 900 : activity.getPayTimeoutSec()));
            // 唯一键冲突（uk_order_no / uk_user_activity）在这里抛出并让事务回滚，
            // 连带撤销上面那条库存扣减 —— 这正是我们想要的：重复消息不会扣两次库存。
            // 消费者会捕获它并按「已下单」处理
            orderMapper.insert(order);

            // ② 超时取消的延迟消息。Kafka 无原生延迟，publishDelay 会写进
            //    t_mq_message（next_retry_time = now + payTimeoutSec），与本次事务同提交
            SeckillTimeoutEvent timeout = new SeckillTimeoutEvent();
            timeout.setUserId(userId);
            timeout.setActivityId(activityId);
            timeout.setOrderNo(event.getOrderNo());
            eventPublisher.publishDelay(MqTopic.SECKILL_TIMEOUT, event.getOrderNo(), timeout,
                    order.getExpireTime().isAfter(LocalDateTime.now())
                            ? (int) Duration.between(LocalDateTime.now(), order.getExpireTime()).toSeconds()
                            : 0);
            return true;
        });

        if (!Boolean.TRUE.equals(created)) {
            // 漂移：Redis 扣了，DB 却已经没有可售库存（或活动已下线）。
            // 回补 Redis 并把用户的这次占用释放掉——这是**唯一**会移除
            // seckill:bought 标记的地方，因为用户不该为一个系统错误永久失去资格
            log.error("秒杀库存漂移，Redis 与 DB 不一致，已回补。activityId={}, userId={}, orderNo={}",
                    activityId, userId, event.getOrderNo());
            rollbackReservation(activityId, userId);
            markResult(activityId, userId, SeckillResultVO.FAILED);
            return;
        }
        markResult(activityId, userId, SeckillResultVO.SUCCESS);
    }

    @Override
    public void markOrderCreated(Long activityId, Long userId, String orderNo) {
        markResult(activityId, userId, SeckillResultVO.SUCCESS);
    }

    @Override
    public void closeTimeout(SeckillTimeoutEvent event) {
        SeckillOrder order = findOrderByNo(event.getOrderNo());
        if (order == null) {
            // 订单还没落库（消费积压超过支付超时）。不抛异常：重试也解决不了，
            // 交给每 5 分钟的超时扫描兜底，那时订单一定已经在库里了
            log.warn("超时消息到达但订单尚未落库，交由定时扫描兜底。orderNo={}", event.getOrderNo());
            return;
        }
        if (!closeOrder(order, SeckillOrder.STATUS_TIMEOUT)) {
            // 已支付或已关闭：这是幂等路径的正常结果，不是错误
            log.info("超时关闭未生效（订单已支付或已关闭）。orderNo={}", event.getOrderNo());
        }
    }

    // ==================== 定时任务调用 ====================

    @Override
    public int warmupActivities() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillActivity> candidates = activityMapper.selectList(
                Wrappers.<SeckillActivity>lambdaQuery()
                        .ne(SeckillActivity::getStatus, SeckillActivity.STATUS_OFFLINE)
                        .gt(SeckillActivity::getEndTime, now));

        int warmed = 0;
        for (SeckillActivity activity : candidates) {
            if (!activity.shouldWarmup(now)) {
                continue;
            }
            // 先看标记再做加锁：已预热的占绝大多数，这一句能省掉每轮一次的
            // Redisson 往返。加锁只是为了「同一活动不被两个实例同时预热」，
            // 真正的幂等由 SETNX 保证
            if (Boolean.TRUE.equals(redis.hasKey(RedisKey.seckillWarmup(activity.getId())))) {
                refreshSnapshot(activity);
                continue;
            }
            RLock lock = redissonClient.getLock(RedisKey.seckillWarmupLock(activity.getId()));
            boolean locked = false;
            try {
                locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
                if (!locked) {
                    continue;
                }
                if (doWarmup(activity)) {
                    warmed++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("秒杀活动预热失败 activityId={}", activity.getId(), e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        return warmed;
    }

    @Override
    public int refreshActivityStatus() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillActivity> activities = activityMapper.selectList(
                Wrappers.<SeckillActivity>lambdaQuery()
                        .ne(SeckillActivity::getStatus, SeckillActivity.STATUS_OFFLINE));

        int updated = 0;
        for (SeckillActivity activity : activities) {
            int expected = activity.statusAt(now);
            if (Objects.equals(activity.getStatus(), expected)) {
                continue;
            }
            SeckillActivity update = new SeckillActivity();
            update.setId(activity.getId());
            update.setStatus(expected);
            activityMapper.updateById(update);
            updated++;
        }
        return updated;
    }

    @Override
    public int scanTimeoutOrders() {
        List<SeckillOrder> orders = orderMapper.selectTimeout(
                LocalDateTime.now(), properties.getTimeoutScanBatchSize());
        int closed = 0;
        for (SeckillOrder order : orders) {
            try {
                if (closeOrder(order, SeckillOrder.STATUS_TIMEOUT)) {
                    closed++;
                }
            } catch (Exception e) {
                // 单笔失败不影响其余订单：这条扫描本身就是兜底路径，
                // 让一笔异常订单挡住后面所有订单是最糟的处理方式
                log.error("超时订单关闭失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        return closed;
    }

    @Override
    public ReconcileOutcome reconcile(Long activityId, boolean force) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        int db = value(activity.getAvailableStock());

        // 进行中的活动不对账：洪峰期间 DB 的 available 落后于 Redis（消费还没落库），
        // 用 DB 覆盖 Redis 等于把库存调高，直接超卖。force=true 是人工应急入口
        if (!force && activity.inTimeWindow(LocalDateTime.now())) {
            return new ReconcileOutcome(activityId, null, db, false);
        }

        String key = RedisKey.seckillStock(activityId);
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            // 库存键不存在说明根本没预热过，此时没有「Redis 侧的库存」可对，
            // 贸然写入反而会造出一个没有预热标记的库存键
            return new ReconcileOutcome(activityId, null, db, false);
        }

        int redisStock = parseInt(raw);
        if (redisStock == db) {
            return new ReconcileOutcome(activityId, redisStock, db, false);
        }

        log.error("秒杀库存不一致，以 DB 为准修正。activityId={} redis={} db={} diff={}",
                activityId, redisStock, db, db - redisStock);
        redis.opsForValue().set(key, String.valueOf(db), Duration.ofHours(properties.getStockTtlHours()));
        return new ReconcileOutcome(activityId, redisStock, db, true);
    }

    @Override
    public int reconcileAll() {
        List<SeckillActivity> activities = activityMapper.selectList(
                Wrappers.<SeckillActivity>lambdaQuery()
                        .ne(SeckillActivity::getStatus, SeckillActivity.STATUS_OFFLINE));

        int fixed = 0;
        for (SeckillActivity activity : activities) {
            try {
                // force=false：正在进行中的活动会被跳过，理由见 reconcile 的注释。
                // Activity 自己的循环里逐场 try：一场活动的异常不该让后面所有活动都不对账
                if (reconcile(activity.getId(), false).fixed()) {
                    fixed++;
                }
            } catch (Exception e) {
                log.error("秒杀库存对账失败 activityId={}", activity.getId(), e);
            }
        }
        return fixed;
    }

    @Override
    public boolean warmupOne(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        if (activity.getEndTime() != null && activity.getEndTime().isBefore(LocalDateTime.now())) {
            // 已结束的活动没有预热的意义：用户点进来也只会得到「活动已结束」
            throw new BizException(ErrorCode.SECKILL_ENDED);
        }
        return doWarmup(activity);
    }

    // ==================== 管理侧 ====================

    @Override
    public void finish(String orderNo) {
        tx.executeWithoutResult(status -> {
            if (orderMapper.markFinished(orderNo) == 0) {
                throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
            }
        });
    }

    @Override
    public void cancelByAdmin(String orderNo, String remark) {
        SeckillOrder order = findOrderByNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (remark != null && !remark.isBlank()) {
            log.info("管理员取消秒杀订单。orderNo={}, remark={}", orderNo, remark);
        }

        if (order.unpaid()) {
            // 积分从未被扣过，因此只需要关单 + 回补库存
            if (!closeOrder(order, SeckillOrder.STATUS_CANCELLED)) {
                throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
            }
            return;
        }

        if (order.paid()) {
            int points = value(order.getPointsCost());
            int qty = quantity(order);
            Boolean refunded = tx.execute(status -> {
                // ① 状态机当闸门：已支付/已完成 → 已退款。影响 0 行 = 已经退过款
                if (orderMapper.markRefunded(orderNo) == 0) {
                    return false;
                }
                // ② 退积分。与扣分同一套逻辑，只是流水类型不同（biz_type=7）
                pointsSpendApi.refundSeckill(order.getUserId(), points, orderNo, order.getGoodsName());
                // ③ 库存 sold → available。少这一步，库存恒等式仍然成立，
                //    但那件货再也回不到「可售」了
                if (activityMapper.refundSold(order.getActivityId(), qty) == 0) {
                    log.error("秒杀退款时库存状态异常，已回滚。orderNo={}, activityId={}",
                            orderNo, order.getActivityId());
                    throw new BizException(ErrorCode.STOCK_DEDUCT_FAILED);
                }
                return true;
            });
            if (!Boolean.TRUE.equals(refunded)) {
                throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
            }
            restoreRedisStock(order.getActivityId(), qty);
            markResult(order.getActivityId(), order.getUserId(), SeckillResultVO.CANCELLED);
            return;
        }

        // 已取消 / 超时关闭 / 已退款：终态，不能再取消
        throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
    }

    // ==================== 内部：库存与结果 ====================

    /**
     * 关闭一张待支付订单并回补库存。返回 false 表示订单已不是待支付（幂等返回）。
     *
     * <p><b>顺序不能变：先改 DB 状态，再回补 Redis。</b>
     * DB 的条件更新是闸门——它保证「回补」这件事全局只发生一次。
     * 反过来先回补 Redis、再改状态的话，两个并发的取消请求会各自回补一次，
     * 凭空多出一件库存，而库存恒等式看起来还是对的（Redis 侧的偏差要等对账才发现）。
     */
    private boolean closeOrder(SeckillOrder order, int status) {
        int qty = quantity(order);
        Boolean changed = tx.execute(s -> {
            if (orderMapper.markClosed(order.getOrderNo(), status) == 0) {
                return false;
            }
            if (activityMapper.rollbackLocked(order.getActivityId(), qty) == 0) {
                // 库存对不上说明数据已经不一致，但用户的订单状态是准的。
                // 只告警不回滚：让用户为系统的数据问题买单（订单没取消、库存也没退）更糟
                log.error("秒杀取消时库存回补失败，活动库存可能与订单不一致。orderNo={}, activityId={}",
                        order.getOrderNo(), order.getActivityId());
            }
            return true;
        });

        if (!Boolean.TRUE.equals(changed)) {
            return false;
        }
        restoreRedisStock(order.getActivityId(), qty);

        // 删掉「分配键」（activity + user → orderNo），但**不删** seckill:bought 里的计数。
        //
        // 这两个动作必须成对理解。留着分配键的话，用户再次点击抢购会命中 Lua 的
        // 幂等重放分支，拿回同一张**已取消**的订单——既没有重新分配库存的机会，
        // 也不会有任何提示，界面上看起来就像「点了没反应」。
        // 删掉它之后，再次抢购会落到限购判定上，得到明确的 15002（已达限购），
        // 与「每人限购 1 件是活动期内的一次性资格」这条产品规则对上。
        //
        // 反过来若连 bought 计数一起删，用户就能反复「取消—重抢」，
        // 限购形同虚设，而 t_seckill_order 的 uk_user_activity 会把第二单挡在 DB 外，
        // 表现为「抢到了却没有订单」。
        redis.delete(RedisKey.seckillOrderNo(order.getActivityId(), order.getUserId()));

        markResult(order.getActivityId(), order.getUserId(),
                status == SeckillOrder.STATUS_TIMEOUT ? SeckillResultVO.TIMEOUT : SeckillResultVO.CANCELLED);
        return true;
    }

    /** 回补 Redis 库存。库存键不在（活动已结束 / 未预热）就什么都不做 */
    private void restoreRedisStock(Long activityId, int qty) {
        String key = RedisKey.seckillStock(activityId);
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.opsForValue().increment(key, qty);
        }
    }

    /**
     * 释放一次「漂移」造成的无效占用：回补库存、移除限购标记、删掉订单号。
     *
     * <p>只在 {@link #createOrder} 发现 Redis 与 DB 不一致时调用，是最罕见的路径。
     * 三步不是原子的——但这条路径本身已经说明系统处于异常状态，
     * 追求原子性只会把复杂度加在一个几乎不会执行的分支上。对账任务会兜住残留偏差。
     */
    private void rollbackReservation(Long activityId, Long userId) {
        restoreRedisStock(activityId, 1);
        redis.opsForHash().delete(RedisKey.seckillBought(activityId), String.valueOf(userId));
        redis.delete(RedisKey.seckillOrderNo(activityId, userId));
    }

    private void markResult(Long activityId, Long userId, String status) {
        Duration ttl = SeckillResultVO.PENDING.equals(status)
                ? Duration.ofSeconds(properties.getResultTtlSeconds())
                : Duration.ofHours(properties.getResultSettledTtlHours());
        redis.opsForValue().set(RedisKey.seckillResult(activityId, userId), status, ttl);
    }

    // ==================== 内部：预热 ====================

    private boolean doWarmup(SeckillActivity activity) {
        String stockKey = RedisKey.seckillStock(activity.getId());
        // SETNX 而不是 SET：本方法每分钟都会被调用，用 SET 会把已经扣减过的库存
        // 不断重置回初始值 —— 这是最直接的一种超卖成因
        Boolean firstTime = redis.opsForValue().setIfAbsent(stockKey,
                String.valueOf(value(activity.getAvailableStock())),
                Duration.ofHours(properties.getStockTtlHours()));

        refreshSnapshot(activity);
        redis.opsForValue().set(RedisKey.seckillWarmup(activity.getId()), "1",
                Duration.ofHours(properties.getStockTtlHours()));

        if (Boolean.TRUE.equals(firstTime)) {
            log.info("秒杀活动预热完成 activityId={} stock={}", activity.getId(), activity.getAvailableStock());
        }
        return Boolean.TRUE.equals(firstTime);
    }

    /**
     * 刷新活动快照。
     *
     * <p><b>每次都刷，且不受 SETNX 保护</b>——因为状态与时间窗是会变的：
     * 「未开始」要变成「进行中」，「进行中」要变成「已结束」。
     * 若把快照也做成「只写一次」，抢购接口会一直拿着活动刚开始时的状态判断，
     * 活动结束了还能抢。库存则相反：它只能初始化一次。
     */
    private void refreshSnapshot(SeckillActivity activity) {
        Map<String, String> snapshot = new HashMap<>(4);
        snapshot.put(F_STATUS, String.valueOf(
                activity.statusAt(LocalDateTime.now())));
        snapshot.put(F_START, String.valueOf(
                activity.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
        snapshot.put(F_END, String.valueOf(
                activity.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
        snapshot.put(F_LIMIT, String.valueOf(value(activity.getPerUserLimit()) == 0
                ? 1 : value(activity.getPerUserLimit())));

        String key = RedisKey.seckillActivityInfo(activity.getId());
        redis.opsForHash().putAll(key, snapshot);
        redis.expire(key, Duration.ofHours(properties.getStockTtlHours()));
    }

    private Snapshot readSnapshot(Long activityId) {
        Map<Object, Object> raw = redis.opsForHash().entries(RedisKey.seckillActivityInfo(activityId));
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new Snapshot(
                    Integer.parseInt(String.valueOf(raw.get(F_STATUS))),
                    Long.parseLong(String.valueOf(raw.get(F_START))),
                    Long.parseLong(String.valueOf(raw.get(F_END))),
                    Integer.parseInt(String.valueOf(raw.get(F_LIMIT))));
        } catch (RuntimeException e) {
            // 快照结构不对说明是脏数据（比如上线时字段改名）。当作「未预热」处理让预热任务重写它，
            // 比抛异常要好：活动本身是好的，用户不该为此买单
            log.error("秒杀活动快照结构异常，按未预热处理。activityId={}, raw={}", activityId, raw, e);
            return null;
        }
    }

    // ==================== 内部：其他 ====================

    /**
     * 快照缺失时给出准确的拒绝理由（冷路径，允许查一次库）。
     *
     * <p>为什么不能统一返回 15005「活动太火爆」：活动未开始时预热任务本来就不会
     * 写快照（预热窗口是「开始前 N 分钟」到「结束」），因此「未开始」这个**正常状态**
     * 会恒定命中「快照缺失」。把它们混为一谈，用户就会在活动开始前看到
     * 「活动太火爆」而不是「活动尚未开始」——前者让人以为系统坏了，
     * 后者才告诉他什么时候回来。
     */
    private BizException coldPathRejection(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        if (activity.getStatus() != null && activity.getStatus() == SeckillActivity.STATUS_OFFLINE) {
            return new BizException(ErrorCode.ACTIVITY_OFFLINE);
        }
        LocalDateTime now = LocalDateTime.now();
        if (activity.getStartTime() != null && now.isBefore(activity.getStartTime())) {
            return new BizException(ErrorCode.SECKILL_NOT_STARTED);
        }
        if (activity.getEndTime() != null && now.isAfter(activity.getEndTime())) {
            return new BizException(ErrorCode.SECKILL_ENDED);
        }
        // 在时间窗内却没有快照，才是真正的「没准备好」
        return new BizException(ErrorCode.SECKILL_NOT_WARMED_UP);
    }

    private void enforceGrabRateLimit(Long userId) {
        String key = GRAB_LIMIT_PREFIX + userId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(10));
        }
        if (count != null && count > properties.getGrabRateLimitPerTenSeconds()) {
            // 限流不是正确性防线（Lua 才是），因此这里是「先自增后判断」，
            // 没有追求原子性：多放过去一两个请求，后果只是多一次 Lua 执行
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    private MqOutboxMessage toOutboxMessage(SeckillOrderEvent event) {
        MqOutboxMessage message = new MqOutboxMessage();
        message.setMessageId(event.getEventId());
        message.setProvider(eventPublisher.provider());
        message.setTopic(MqTopic.SECKILL_ORDER);
        message.setRoutingKey(event.routingKey());
        message.setBizType(SeckillOrderEvent.class.getSimpleName());
        message.setBizKey(event.getOrderNo());
        message.setPayload(toJson(event));
        message.setHeaders("{}");
        message.setStatus(MqOutboxMessage.STATUS_PENDING);
        message.setRetryCount(0);
        message.setMaxRetry(mqProperties.getOutbox().getMaxRetry());
        // next_retry_time 是调度器的扫描条件，必须给当前时间；
        // 留空的话 `next_retry_time <= now` 永远不成立，消息发不出去
        message.setNextRetryTime(LocalDateTime.now());
        message.setErrorMsg("");
        return message;
    }

    private String toJson(SeckillOrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化失败无法降级：没有 payload 就没有消息。抛出去让本次抢购失败并回滚，
            // 比写一条空消息进表、由消费者在对端报「无法解析」要好定位得多
            throw new IllegalStateException("秒杀事件序列化失败 orderNo=" + event.getOrderNo(), e);
        }
    }

    private SeckillOrder findOrder(Long userId, Long activityId) {
        return orderMapper.selectOne(Wrappers.<SeckillOrder>lambdaQuery()
                .eq(SeckillOrder::getUserId, userId)
                .eq(SeckillOrder::getActivityId, activityId));
    }

    private SeckillOrder findOrderByNo(String orderNo) {
        return orderMapper.selectOne(Wrappers.<SeckillOrder>lambdaQuery()
                .eq(SeckillOrder::getOrderNo, orderNo));
    }

    /**
     * 取本人的订单。
     *
     * <p>「不是本人的订单」也返回 15012（订单不存在），而不是 10003（无权限）：
     * 后者等于告诉调用者「这个订单号是真实存在的」，把订单号变成可以枚举的信息。
     */
    private SeckillOrder requireOwnOrder(Long userId, String orderNo) {
        SeckillOrder order = findOrderByNo(orderNo);
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private SeckillResultVO settled(String status, SeckillOrder order) {
        String message = switch (status) {
            case SeckillResultVO.TIMEOUT -> "超时未支付，已关闭";
            case SeckillResultVO.CANCELLED -> "已取消";
            default -> "抢购成功";
        };
        String orderNo = order == null ? null : order.getOrderNo();
        return new SeckillResultVO(status, orderNo, message, SeckillConverter.toOrderVO(order));
    }

    private static int rank(SeckillActivity activity, LocalDateTime now) {
        if (activity.getStatus() != null && activity.getStatus() == SeckillActivity.STATUS_ENDED) {
            return 2;
        }
        if (activity.inTimeWindow(now)) {
            return 0;
        }
        return now.isBefore(activity.getStartTime()) ? 1 : 2;
    }

    private static int quantity(SeckillOrder order) {
        return order.getQuantity() == null || order.getQuantity() < 1 ? 1 : order.getQuantity();
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.MIN_VALUE;
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 分页大小兜底。
     *
     * <p>与 {@code forum-points} 的 {@code PageSizes} 同值，但**刻意不引用它**：
     * 秒杀域对其他业务域只使用 {@code api} 包的契约，而 {@code support} 属于内部实现。
     * 为一个 3 行的区间判断去跨模块依赖，换来的耦合远大于省下的重复。
     */
    private static int clampPageSize(int size) {
        return Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(cursor.trim());
        } catch (NumberFormatException e) {
            log.warn("秒杀订单游标非法，回退到第一页。cursor={}", cursor);
            return null;
        }
    }

    private static RedisScript<List> loadScript(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            DefaultRedisScript<List> script = new DefaultRedisScript<>();
            script.setScriptText(StreamUtils.copyToString(in, StandardCharsets.UTF_8));
            script.setResultType(List.class);
            return script;
        } catch (IOException e) {
            throw new IllegalStateException("加载秒杀 Lua 脚本失败：" + path, e);
        }
    }

    /** 活动快照。字段少且只读，用 record 而不是再建一个 Hash 的实体 */
    private record Snapshot(int status, long startMs, long endMs, int limit) {
    }
}
