package com.school.forum.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.points.entity.MallOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 兑换订单数据访问。
 *
 * <p><b>状态流转一律用条件更新，而不是读出来判一下再写回去。</b>
 * 两个并发请求同时取消同一个订单时，「读出来都是待发放」这一步必然双双通过，
 * 接着就是两次退款——用户凭空多出一份积分。
 * 加上 {@code AND status = 0} 之后，只有第一个请求能改到行，
 * 第二个影响 0 行，据此直接拒绝即可，不需要任何显式锁。
 */
@Mapper
public interface MallOrderMapper extends BaseMapper<MallOrder> {

    /**
     * 待发放 → 已完成（管理员标记发放）。
     *
     * @return 影响行数。0 表示订单不存在或已不是待发放状态，调用方应返回 16007
     */
    @Update("UPDATE t_mall_order SET status = 1, finish_time = NOW() "
            + "WHERE id = #{orderId} AND status = 0")
    int markFinished(@Param("orderId") Long orderId);

    /**
     * 待发放 → 已取消（用户或管理员取消）。
     *
     * <p>这个条件更新必须与「退积分 + 退库存」在同一个事务里。
     * 它是整个取消流程的第一道闸门：影响 0 行就不再执行任何一个退回动作，
     * 从根上排除了「重复取消导致重复退款」。
     *
     * @return 影响行数。0 表示订单已不是待发放状态，调用方应返回 16007
     */
    @Update("UPDATE t_mall_order SET status = 2, cancel_time = NOW() "
            + "WHERE id = #{orderId} AND status = 0")
    int markCancelled(@Param("orderId") Long orderId);

    /**
     * 写入处理备注（管理员发放时填写，或取消时记录原因）。
     *
     * <p>与状态更新分开是有意的：备注是可选信息，不该影响状态流转的成败。
     * 管理员填写备注失败，不应该导致发放动作被回滚。
     */
    @Update("UPDATE t_mall_order SET remark = #{remark} WHERE id = #{orderId}")
    int updateRemark(@Param("orderId") Long orderId, @Param("remark") String remark);

    /**
     * 我的兑换记录（倒序游标分页）。
     *
     * <p>走 {@code idx_user_id (user_id, id DESC)}：{@code id < ?} 直接定位到扫描起点，
     * 翻到第几页都是同样代价。写成 {@code LIMIT offset, size} 的话，
     * 老用户翻自己的历史记录会越翻越慢——而兑换记录恰恰是一份只会增长的账。
     *
     * <p>放在 XML 里是因为带可选的游标条件，注解里写动态 SQL 需要
     * {@code <script>} 包装，可读性明显更差。
     *
     * @param cursor 上一页最后一条的 ID，null 表示第一页
     * @param limit  调用方传 size + 1
     */
    List<MallOrder> selectByCursor(@Param("userId") Long userId,
                                   @Param("cursor") Long cursor,
                                   @Param("limit") int limit);
}
