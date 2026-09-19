package com.school.forum.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.points.entity.MallGoods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商城商品数据访问。
 *
 * <p><b>两个条件更新是防超卖的全部手段</b>，没有 Redis 预扣兜在前面
 * （理由见《02-架构设计》ADR-008：商城没有瞬时峰值，
 * 引入 Redis 库存只会凭空多出一份需要同步的状态）。
 *
 * <p>条件的写法要让 InnoDB 能在**同一把行锁内**判定并修改：
 * {@code WHERE id = ? AND stock >= 1} 命中的行在 UPDATE 期间被排他锁住，
 * 另一个并发请求必须等锁释放后重新读取，此时 {@code stock} 已是 0，
 * 它的条件不成立、影响 0 行。这是「先查库存再判断再更新」做不到的——
 * 那三步之间没有任何锁保护。
 */
@Mapper
public interface MallGoodsMapper extends BaseMapper<MallGoods> {

    /**
     * 扣一件库存（兑换）。
     *
     * <p>{@code sold_count} 与 {@code stock} 在同一句里一起变，两者不可能脱节。
     * 若分成两句 UPDATE，中间失败就会留下「库存扣了但销量没加」的状态。
     *
     * @return 影响行数。0 表示库存不足或商品已下架，调用方应返回 16005 并回滚
     */
    @Update("UPDATE t_mall_goods SET stock = stock - 1, sold_count = sold_count + 1 "
            + "WHERE id = #{goodsId} AND status = 1 AND stock >= 1")
    int deductStock(@Param("goodsId") Long goodsId);

    /**
     * 回退一件库存（取消兑换）。
     *
     * <p>条件带 {@code sold_count >= 1}：那是「确实卖出过一件」的证明。
     * 少了这个条件，一个被重复提交的取消请求就会把库存越退越多——
     * 而这正是 {@code sold_count} 存在的意义：它不只是展示用的销量，
     * 也是库存回退的守门人。
     *
     * <p>不限制 {@code status}：商品在用户下单后被下架，取消时仍须退库存，
     * 否则那件货就被永久冻结了。
     *
     * @return 影响行数。0 说明无需回退（次数对不上），由调用方决定是否告警
     */
    @Update("UPDATE t_mall_goods SET stock = stock + 1, sold_count = sold_count - 1 "
            + "WHERE id = #{goodsId} AND sold_count >= 1")
    int rollbackStock(@Param("goodsId") Long goodsId);
}
