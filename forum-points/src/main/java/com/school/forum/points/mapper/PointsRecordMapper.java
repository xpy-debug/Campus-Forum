package com.school.forum.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.points.entity.PointsRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 积分流水数据访问。
 *
 * <p>只有插入与查询，没有更新与删除——流水表是不可变的（见 {@link PointsRecord}）。
 */
@Mapper
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {

    /**
     * 判断某笔业务是否已经记过账。
     *
     * <p><b>这是月度奖励任务的幂等判据。</b>任务每天重跑一次「结算上月」，
     * 必须能分辨「这个用户上月已经发过了」与「还没发」。判据用
     * {@code (user_id, biz_type, biz_id)} 而不是「任务有没有跑过」——
     * 后者要求任务自己维护一份进度状态，而进度状态本身就可能丢。
     * 直接问流水表「这笔账记过没有」，是唯一不会失效的问法。
     *
     * <p>注意：这里的查询只是**快路径**。真正的保障是
     * {@code uk_user_biz} 唯一索引——并发下两个实例可能同时查到「没记过」，
     * 然后双双插入，此时唯一索引会让其中一个失败。所以调用方必须能接受
     * {@code DuplicateKeyException}，而不是依赖这个方法的结果做最终裁决。
     */
    @Select("SELECT COUNT(*) FROM t_points_record "
            + "WHERE user_id = #{userId} AND biz_type = #{bizType} AND biz_id = #{bizId}")
    int countByBiz(@Param("userId") Long userId,
                   @Param("bizType") int bizType,
                   @Param("bizId") String bizId);

    /**
     * 按游标查某用户的流水（倒序）。
     *
     * <p>游标就是上一页最后一条的 {@code id}。这里不需要帖子列表那种
     * base64 编码的复合游标：{@code id} 已经是单调递增且唯一的，
     * 一个十进制整数就足够定位，编码一层反而让排查问题时看不懂游标是什么。
     *
     * <p>条件写成 {@code id < #{cursor}} 而不是 {@code LIMIT offset, size}：
     * 后者在翻到第 500 页时要先扫过前 10000 行再丢掉，而积分流水是只增的表，
     * 深翻页恰恰是它的常态（老用户翻自己的历史记录）。
     * 走 {@code idx_user_id (user_id, id DESC)} 时，{@code id < ?} 直接定位到起点。
     *
     * @param cursor 上一页最后一条的 ID，null 表示第一页
     * @param limit  调用方传 size + 1，多查的一条用于判断还有没有下一页
     */
    List<PointsRecord> selectByCursor(@Param("userId") Long userId,
                                      @Param("cursor") Long cursor,
                                      @Param("limit") int limit);
}
