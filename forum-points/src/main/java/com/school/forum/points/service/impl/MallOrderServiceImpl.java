package com.school.forum.points.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.forum.common.constant.RedisKey;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.PageResult;
import com.school.forum.points.convert.MallConverter;
import com.school.forum.points.dto.MallOrderCreateRequest;
import com.school.forum.points.entity.MallGoods;
import com.school.forum.points.entity.MallOrder;
import com.school.forum.points.mapper.MallGoodsMapper;
import com.school.forum.points.mapper.MallOrderMapper;
import com.school.forum.points.service.MallOrderService;
import com.school.forum.points.service.PointsAccountService;
import com.school.forum.points.support.PageSizes;
import com.school.forum.points.vo.MallOrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 兑换订单实现。
 *
 * <p>全类没有一处显式锁，也没有 {@code version} 乐观锁列：所有的并发安全都来自
 * <b>带条件的 UPDATE</b>。库存、积分、订单状态三处各自有一个
 * {@code WHERE ... AND ...}，影响 0 行就意味着「这次操作的前提不成立了」，
 * 直接抛业务异常让事务回滚。数据库在同一把行锁内同时完成判断与修改，
 * 这是「先查再判再改」做不到的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MallOrderServiceImpl implements MallOrderService {

    /** 订单号里的日期部分。用 BASIC_ISO_DATE 直接得到 yyyyMMdd，不必再拼格式化串 */
    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final MallOrderMapper orderMapper;
    private final MallGoodsMapper goodsMapper;
    private final PointsAccountService accountService;
    private final StringRedisTemplate redis;

    // ==================== 下单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MallOrderVO create(Long userId, MallOrderCreateRequest request) {
        MallGoods goods = goodsMapper.selectById(request.getGoodsId());
        if (goods == null) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        if (!goods.onSale()) {
            throw new BizException(ErrorCode.GOODS_OFFLINE);
        }

        int price = goods.getPointsPrice() == null ? 0 : goods.getPointsPrice();

        // 先扣库存：售罄是最常见的失败原因（也是并发下最激烈的竞争点），
        // 让它尽早失败，不必先分配一次订单号、再插入一行注定被回滚的订单
        if (goodsMapper.deductStock(goods.getId()) == 0) {
            throw new BizException(ErrorCode.GOODS_STOCK_NOT_ENOUGH);
        }

        // 余额的**快速失败**。真正的保证在 spendForOrder 的条件更新里：
        // 这里读到的余额可能已经被另一个并发请求花掉了，那次会由条件更新拦下
        if (accountService.balanceOf(userId) < price) {
            throw new BizException(ErrorCode.POINTS_NOT_ENOUGH);
        }

        MallOrder order = new MallOrder();
        order.setOrderNo(nextOrderNo());
        order.setUserId(userId);
        order.setGoodsId(goods.getId());
        // 三处快照：商品改名、改类型、调价之后，这张订单展示的仍是兑换当时的样子
        order.setGoodsName(goods.getName());
        order.setGoodsType(goods.getType());
        order.setPointsCost(price);
        order.setStatus(MallOrder.STATUS_PENDING);
        order.setRemark(request.getRemark() == null ? "" : request.getRemark());
        // 显式写创建时间：列上有 DEFAULT，但显式写之后插入完的对象就是完整的，
        // 不必为了返回 createTime 再查一次库
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);

        accountService.spendForOrder(userId, price, order.getOrderNo(), order.getGoodsName());

        return MallConverter.toOrderVO(order);
    }

    /**
     * 生成订单号：{@code M + yyyyMMdd + 6 位日内序号}。
     *
     * <p>用 Redis {@code INCR} 而不是数据库自增：订单号要在**插入之前**拿到
     * （它要写进流水表的业务标识里），而数据库自增只能等插入之后才知道。
     * 也不能用「当天下单数 + 1」——两个并发下单会算出同一个序号。
     *
     * <p>序号超过 6 位时格式化结果自然变长，这里不做截断：校园论坛一天不可能
     * 兑换一百万单，真到了那一天，说明业务规模早已超出这套方案的设计前提。
     */
    private String nextOrderNo() {
        String day = LocalDate.now().format(ORDER_NO_DATE);
        String key = RedisKey.mallOrderNoSeq(day);

        Long seq = redis.opsForValue().increment(key);
        if (seq == null) {
            // Redis 是硬依赖：拿不到序号就没法生成订单号，也就不能建订单。
            // 与其退化成「订单号冲突后再重试」，不如直接失败让上游看到真实原因
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        if (seq == 1L) {
            // 只在序号从 1 开始时设一次 TTL。每天都设一遍会多一次往返，
            // 而这个键只活到当天结束后的第二天，没必要精确到秒
            redis.expire(key, Duration.ofDays(2));
        }
        return "M" + day + String.format("%06d", seq);
    }

    // ==================== 用户侧查询与取消 ====================

    @Override
    public PageResult<MallOrderVO> listMine(Long userId, String cursor, int size) {
        int safeSize = PageSizes.clamp(size);
        // 多查一条用于判断有没有下一页，因此不需要 COUNT
        List<MallOrder> orders = orderMapper.selectByCursor(userId, parseCursor(cursor), safeSize + 1);
        return PageResult.ofCursor(orders, safeSize, o -> String.valueOf(o.getId()))
                .map(MallConverter::toOrderVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MallOrderVO cancelByOwner(Long userId, Long orderId) {
        MallOrder order = requireOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return cancel(order, null);
    }

    // ==================== 管理侧 ====================

    @Override
    public PageResult<MallOrder> page(Integer status, int page, int size) {
        Page<MallOrder> result = orderMapper.selectPage(
                Page.of(page, size),
                Wrappers.<MallOrder>lambdaQuery()
                        .eq(status != null, MallOrder::getStatus, status)
                        .orderByDesc(MallOrder::getId));
        return PageResult.ofPage(result.getRecords(), result.getTotal(), size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finish(Long orderId, String remark) {
        if (orderMapper.markFinished(orderId) == 0) {
            throw new BizException(ErrorCode.MALL_ORDER_STATUS_ILLEGAL);
        }
        updateRemarkIfPresent(orderId, remark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MallOrderVO cancelByAdmin(Long orderId, String remark) {
        return cancel(requireOrder(orderId), remark);
    }

    // ==================== 内部方法 ====================

    /**
     * 取消订单并退回积分与库存。
     *
     * <p><b>顺序是有讲究的</b>：先改状态。这一步是整段流程的闸门，
     * 它同时挡住了「重复取消」和「并发取消」——第二次调用影响 0 行，
     * 直接抛异常，后面两个退回动作根本不会执行。
     * 若先退积分再改状态，两个并发请求会各自退一次，用户凭空多出一份积分。
     *
     * <p>退回库存失败只告警不回滚：那说明数据已经不一致（卖出的件数对不上），
     * 但用户的积分已经退对了。此时把整个取消回滚掉，等于让用户为系统的
     * 数据问题买单——他既没拿到货，也拿不回积分。
     */
    private MallOrderVO cancel(MallOrder order, String remark) {
        if (orderMapper.markCancelled(order.getId()) == 0) {
            throw new BizException(ErrorCode.MALL_ORDER_STATUS_ILLEGAL);
        }

        if (goodsMapper.rollbackStock(order.getGoodsId()) == 0) {
            log.warn("取消兑换时库存回退失败，商品可能与订单数据不一致。orderNo={}, goodsId={}",
                    order.getOrderNo(), order.getGoodsId());
        }

        accountService.refundOrder(order.getUserId(), value(order.getPointsCost()),
                order.getOrderNo(), order.getGoodsName());

        updateRemarkIfPresent(order.getId(), remark);

        // 重新查一次，让返回的 finishTime/cancelTime 是数据库里的真实值
        // （cancel_time 由 NOW() 写入，实体里没有）
        return MallConverter.toOrderVO(orderMapper.selectById(order.getId()));
    }

    private void updateRemarkIfPresent(Long orderId, String remark) {
        if (remark != null && !remark.isBlank()) {
            orderMapper.updateRemark(orderId, remark);
        }
    }

    private MallOrder requireOrder(Long orderId) {
        MallOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.MALL_ORDER_NOT_FOUND);
        }
        return order;
    }

    /** 非法游标退回第一页，与积分流水的处理一致 */
    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(cursor.trim());
        } catch (NumberFormatException e) {
            log.warn("兑换记录游标非法，回退到第一页。cursor={}", cursor);
            return null;
        }
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
