package com.school.forum.seckill.controller;

import com.school.forum.common.result.PageResult;
import com.school.forum.common.result.Result;
import com.school.forum.infrastructure.web.auth.CurrentUserId;
import com.school.forum.infrastructure.web.auth.RequireLogin;
import com.school.forum.seckill.service.SeckillService;
import com.school.forum.seckill.vo.SeckillActivityVO;
import com.school.forum.seckill.vo.SeckillOrderVO;
import com.school.forum.seckill.vo.SeckillResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分秒杀（用户侧）。上下文路径为 {@code /api}，故实际路径是 {@code /api/seckill/*}。
 *
 * <p><b>它只是一个薄转发层。</b>抢购的全部逻辑（含 Lua 预扣、消息投递）
 * 都在 {@link SeckillService#grab} 里——放在 Controller 里的话，
 * 「限流 + 预扣 + 落消息」这段时序就长在 HTTP 层上，
 * 未来想加一个「活动结束前 10 秒自动开抢」的入口就要把它复制一遍。
 *
 * <p><b>与积分商城的 {@code MallController} 是并列的两套接口，不复用。</b>
 * 两者的流量模型与库存语义完全不同（见 ADR-010）。硬凑成一个 Controller
 * 只会让每个方法里都出现「这次走哪条路」的分支。
 *
 * <p><b>用户侧没有任何管理能力</b>，改商品、改活动、发货全部在
 * {@code forum-admin} 的 {@code /admin/seckill/**} 下，
 * 由 {@code @RequireRole(ROLE_ADMIN)} 在拦截器层挡住。
 */
@RestController
@RequestMapping("/seckill")
@RequireLogin
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    /** 秒杀活动列表。顺序为「进行中 → 即将开始 → 已结束」 */
    @GetMapping("/activities")
    public Result<List<SeckillActivityVO>> activities() {
        return Result.ok(seckillService.listActivities());
    }

    /**
     * 抢购。
     *
     * <p>返回的是「排队中」而不是订单本身：Redis 预扣成功就立刻返回，
     * 订单由 MQ 消费者异步落库。前端拿 orderNo 轮询
     * {@code GET /seckill/{activityId}/result}。
     *
     * <p>重复点击是安全的：Lua 会返回同一个 orderNo（幂等重放），不会重复扣库存。
     */
    @PostMapping("/{activityId}/grab")
    public Result<SeckillResultVO> grab(@CurrentUserId Long userId,
                                        @PathVariable Long activityId) {
        return Result.ok(seckillService.grab(userId, activityId));
    }

    /** 轮询抢购结果：PENDING / SUCCESS / FAILED / TIMEOUT / CANCELLED */
    @GetMapping("/{activityId}/result")
    public Result<SeckillResultVO> result(@CurrentUserId Long userId,
                                          @PathVariable Long activityId) {
        return Result.ok(seckillService.result(userId, activityId));
    }

    /**
     * 支付（用积分）。
     *
     * <p>积分不足返回 16002，此时订单仍是「待支付」，用户可以在超时前再试，
     * 或者等它自动关闭后把库存还回去。**不需要为「积分不足」单独写一条补偿链路**，
     * 这正是把扣分放在支付而不是抢占时刻的收益。
     */
    @PostMapping("/orders/{orderNo}/pay")
    public Result<SeckillOrderVO> pay(@CurrentUserId Long userId,
                                      @PathVariable String orderNo) {
        return Result.ok(seckillService.pay(userId, orderNo));
    }

    /**
     * 取消（仅本人、仅待支付）。
     *
     * <p><b>用 POST 而不是 DELETE</b>，与商城取消订单同一条理由：
     * 这不是「删除这张订单」，而是「让它进入已取消状态并回补库存」，
     * 订单行仍然存在，用户还要在记录里看到它。
     *
     * <p>取消后**不能重新抢购**——每人限购 1 件是活动期内的一次性资格。
     */
    @PostMapping("/orders/{orderNo}/cancel")
    public Result<SeckillOrderVO> cancel(@CurrentUserId Long userId,
                                         @PathVariable String orderNo) {
        return Result.ok(seckillService.cancelByOwner(userId, orderNo));
    }

    /**
     * 我的秒杀订单。
     *
     * @param cursor 上一页返回的 nextCursor，首页不传
     */
    @GetMapping("/orders")
    public Result<PageResult<SeckillOrderVO>> myOrders(@CurrentUserId Long userId,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false, defaultValue = "20") int size) {
        return Result.ok(seckillService.myOrders(userId, cursor, size));
    }
}
