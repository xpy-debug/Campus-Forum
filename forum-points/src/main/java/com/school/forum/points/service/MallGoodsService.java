package com.school.forum.points.service;

import com.school.forum.common.result.PageResult;
import com.school.forum.points.api.MallGoodsForm;
import com.school.forum.points.entity.MallGoods;
import com.school.forum.points.vo.MallGoodsVO;

import java.util.List;

/**
 * 商品：用户侧只读浏览，管理侧增删改。
 *
 * <p><b>两类方法写在同一个服务里，但调用方完全不同</b>：
 * 用户侧（{@link #listOnSale}、{@link #detail}）由 {@code MallController} 调用，
 * 管理侧（{@link #page}、{@link #create} 等）只由 {@code MallAdminApiImpl} 调用。
 * 分成两个服务也可以，但那样「商品」这件事的规则会散在两处，
 * 而其中一处（用户侧）必须知道「什么状态可见」这个约束——
 * 它和管理侧的「什么状态可改」是同一套状态机的两面，放在一起才不会各改各的。
 *
 * <p>管理侧方法接收 {@code api} 包的表单，而不是再造一个内部 DTO：
 * 两者字段完全一致，复制一份只会带来同步维护的负担，而这份负担没有任何收益。
 */
public interface MallGoodsService {

    // ==================== 用户侧 ====================

    /**
     * 在售商品列表。
     *
     * <p>不走缓存，每次直接查库：{@code canExchange} 依赖调用者余额，
     * 而库存与已兑数随兑换即时变化，缓存任何一个都会让用户读到过期结论。
     *
     * @param userId 调用者，用于判定能否兑换
     */
    List<MallGoodsVO> listOnSale(Long userId);

    /** 商品详情。已下架或不存在时抛 16003 / 16004 */
    MallGoodsVO detail(Long userId, Long goodsId);

    // ==================== 管理侧 ====================

    /**
     * 商品列表（管理端），**不过滤状态**。
     *
     * @param status 状态筛选，null 表示全部
     * @param page   页码，从 1 开始
     */
    PageResult<MallGoods> page(Integer status, int page, int size);

    /** 商品实体。不存在时返回 null，由调用方决定抛什么错 */
    MallGoods get(Long goodsId);

    /** 新建商品，返回新商品 ID */
    Long create(MallGoodsForm form);

    /** 编辑商品，只覆盖表单中非 null 的字段 */
    void update(Long goodsId, MallGoodsForm form);

    /** 上架 / 下架 / 转为草稿 */
    void changeStatus(Long goodsId, int status);
}
