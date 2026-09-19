package com.school.forum.points.controller;

import com.school.forum.common.result.PageResult;
import com.school.forum.common.result.Result;
import com.school.forum.infrastructure.web.auth.CurrentUserId;
import com.school.forum.infrastructure.web.auth.RequireLogin;
import com.school.forum.points.dto.MallOrderCreateRequest;
import com.school.forum.points.service.MallGoodsService;
import com.school.forum.points.service.MallOrderService;
import com.school.forum.points.vo.MallGoodsVO;
import com.school.forum.points.vo.MallOrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分商城（用户侧）。上下文路径为 {@code /api}，故实际路径是 {@code /api/mall/*}。
 *
 * <p><b>用户侧只有浏览与下单，没有任何修改商品的接口。</b>
 * 这不是「前端不显示按钮」式的约束，而是接口层面的：本类里根本不存在
 * 能改 {@code t_mall_goods} 的方法。改商品的能力只在
 * {@code forum-admin} 的 {@code /admin/mall/goods} 下，
 * 且由 {@code @RequireRole(ROLE_ADMIN)} 在拦截器层挡住——
 * 前者让越权在编译期就不可能，后者让越权在运行期被拒绝。
 *
 * <p>整个类要求登录，包括只读的商品列表：{@code canExchange} 要按调用者余额算，
 * 而「未登录时余额视为 0」只会让界面显示一堆不可兑换的商品，
 * 不如直接要求登录——想换东西本来就需要一个账号。
 */
@RestController
@RequestMapping("/mall")
@RequireLogin
@RequiredArgsConstructor
public class MallController {

    private final MallGoodsService goodsService;
    private final MallOrderService orderService;

    /**
     * 在售商品列表。
     *
     * <p>不分页：校园商城的商品数量是个位到几十的量级，一屏能看完，
     * 分页只会给前端增加一个没有收益的状态。
     *
     * <p>也不缓存，直接查库：库存与已兑数会随兑换即时变化，
     * 缓存一份只会让用户读到过期的数字（「刚兑换成功，剩余件数却不变」）。
     */
    @GetMapping("/goods")
    public Result<List<MallGoodsVO>> goods(@CurrentUserId Long userId) {
        return Result.ok(goodsService.listOnSale(userId));
    }

    /** 商品详情。已下架返回 16004，不存在返回 16003 */
    @GetMapping("/goods/{goodsId}")
    public Result<MallGoodsVO> goodsDetail(@CurrentUserId Long userId,
                                           @PathVariable Long goodsId) {
        return Result.ok(goodsService.detail(userId, goodsId));
    }

    /**
     * 兑换商品。
     *
     * <p>返回完整订单而不只是订单号：下单成功要立刻展示「换了什么、花了多少、什么时候到」，
     * 让前端再查一次既多一次往返，也可能因为主从延迟查不到刚建的行。
     */
    @PostMapping("/orders")
    public Result<MallOrderVO> createOrder(@CurrentUserId Long userId,
                                           @RequestBody @Valid MallOrderCreateRequest request) {
        return Result.ok(orderService.create(userId, request));
    }

    /**
     * 我的兑换记录。
     *
     * @param cursor 上一页返回的 nextCursor，首页不传
     */
    @GetMapping("/orders")
    public Result<PageResult<MallOrderVO>> myOrders(@CurrentUserId Long userId,
                                                    @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false, defaultValue = "20") int size) {
        return Result.ok(orderService.listMine(userId, cursor, size));
    }

    /**
     * 取消兑换。
     *
     * <p><b>用 POST 而不是 DELETE：</b>取消不是「删除这张订单」，而是「让它进入已取消状态
     * 并把积分退回来」——订单行仍然存在，用户还要能在记录里看到它。
     * 更关键的是这个动作有副作用且不可撤销（积分退回来就退回来了），
     * 不适合一个语义上应当幂等的 DELETE。
     */
    @PostMapping("/orders/{orderId}/cancel")
    public Result<MallOrderVO> cancelOrder(@CurrentUserId Long userId,
                                           @PathVariable Long orderId) {
        return Result.ok(orderService.cancelByOwner(userId, orderId));
    }
}
