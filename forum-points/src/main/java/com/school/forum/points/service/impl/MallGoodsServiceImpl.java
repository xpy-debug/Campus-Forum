package com.school.forum.points.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.PageResult;
import com.school.forum.points.api.MallGoodsForm;
import com.school.forum.points.convert.MallConverter;
import com.school.forum.points.entity.MallGoods;
import com.school.forum.points.mapper.MallGoodsMapper;
import com.school.forum.points.service.MallGoodsService;
import com.school.forum.points.service.PointsAccountService;
import com.school.forum.points.vo.MallGoodsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品实现。
 *
 * <p><b>用户侧列表不做缓存，直接查库。</b>商品是个位数到几十的量级，
 * 一次 {@code selectList} 对 MySQL 毫无压力；而库存与已兑数会随兑换
 * 即时变化，缓存一份含它们的实体只会让用户看到过期的数字
 * （「刚兑换成功，剩余件数却不变」）。省下的那点查询不值得一次一致性困惑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MallGoodsServiceImpl implements MallGoodsService {

    private final MallGoodsMapper goodsMapper;
    private final PointsAccountService accountService;

    // ==================== 用户侧 ====================

    @Override
    public List<MallGoodsVO> listOnSale(Long userId) {
        int balance = accountService.balanceOf(userId);
        return goodsMapper.selectList(Wrappers.<MallGoods>lambdaQuery()
                        .eq(MallGoods::getStatus, MallGoods.STATUS_ON_SALE)
                        .orderByDesc(MallGoods::getSortOrder)
                        .orderByDesc(MallGoods::getId))
                .stream()
                .map(goods -> MallConverter.toGoodsVO(goods, balance))
                .toList();
    }

    @Override
    public MallGoodsVO detail(Long userId, Long goodsId) {
        MallGoods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        if (!goods.onSale()) {
            // 已下架与不存在的区别对用户没有意义，但对排查问题有意义：
            // 16004 明确告诉我们「这个商品存在过」，不必再去翻后台的操作记录
            throw new BizException(ErrorCode.GOODS_OFFLINE);
        }
        return MallConverter.toGoodsVO(goods, accountService.balanceOf(userId));
    }

    // ==================== 管理侧 ====================

    @Override
    public PageResult<MallGoods> page(Integer status, int page, int size) {
        // 管理端用页码分页（要显示总数、要能跳页），与用户侧的游标分页是两套约定，
        // 见 PageResult 的类注释
        Page<MallGoods> result = goodsMapper.selectPage(
                Page.of(page, size),
                Wrappers.<MallGoods>lambdaQuery()
                        .eq(status != null, MallGoods::getStatus, status)
                        .orderByDesc(MallGoods::getSortOrder)
                        .orderByDesc(MallGoods::getId));
        return PageResult.ofPage(result.getRecords(), result.getTotal(), size);
    }

    @Override
    public MallGoods get(Long goodsId) {
        return goodsId == null ? null : goodsMapper.selectById(goodsId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(MallGoodsForm form) {
        MallGoods goods = new MallGoods();
        applyForm(goods, form);
        // 新建时状态缺省为草稿而不是上架：商品刚建好往往还没配图或定价没定，
        // 直接上架会让用户看到一个半成品
        if (form.getStatus() == null) {
            goods.setStatus(MallGoods.STATUS_DRAFT);
        }
        if (form.getStock() == null) {
            goods.setStock(0);
        }
        goods.setSoldCount(0);
        if (form.getSortOrder() == null) {
            goods.setSortOrder(0);
        }

        goodsMapper.insert(goods);
        return goods.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long goodsId, MallGoodsForm form) {
        MallGoods goods = requireGoods(goodsId);
        applyForm(goods, form);

        // 按 ID 更新，且只更新非 null 字段（MP 的 updateById 默认行为）。
        // stock 与 sold_count 故意不在这里维护：库存由兑换与取消的条件更新负责，
        // 让后台直接改库存会与在途的兑换互相覆盖
        goods.setStock(null);
        goods.setSoldCount(null);
        goodsMapper.updateById(goods);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long goodsId, int status) {
        MallGoods goods = requireGoods(goodsId);

        MallGoods update = new MallGoods();
        update.setId(goods.getId());
        update.setStatus(status);
        goodsMapper.updateById(update);
    }

    /**
     * 把表单里非 null 的字段写到实体上。
     *
     * <p>「null 表示不修改」是 {@code MallGoodsForm} 的约定，这里如实实现。
     * 空字符串则如实写入——「把描述清空」与「不动描述」是两种意图，
     * 用 {@code ""} 与 {@code null} 区分。
     */
    private void applyForm(MallGoods goods, MallGoodsForm form) {
        if (form.getName() != null) {
            goods.setName(form.getName());
        }
        if (form.getCoverImage() != null) {
            goods.setCoverImage(form.getCoverImage());
        }
        if (form.getDescription() != null) {
            goods.setDescription(form.getDescription());
        }
        if (form.getType() != null) {
            goods.setType(form.getType());
        }
        if (form.getPointsPrice() != null) {
            goods.setPointsPrice(form.getPointsPrice());
        }
        if (form.getStatus() != null) {
            goods.setStatus(form.getStatus());
        }
        if (form.getSortOrder() != null) {
            goods.setSortOrder(form.getSortOrder());
        }
        // 库存只在新建时写入，编辑接口忽略它——理由见 update 方法
        if (goods.getId() == null && form.getStock() != null) {
            goods.setStock(form.getStock());
        }
    }

    private MallGoods requireGoods(Long goodsId) {
        MallGoods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        return goods;
    }
}
