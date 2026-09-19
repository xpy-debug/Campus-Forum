package com.school.forum.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.forum.entity.Comment;
import com.school.forum.forum.support.TargetDelta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评论数据访问。
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 一级评论分页，按楼层升序。
     *
     * <p>楼层是递增的，所以游标可以只用一个 {@code floor}（首页传 null）：
     * {@code floor > ?} 天然是全序的，不需要像帖子那样拿 id 来决胜——
     * 这正是「排序列全为 ASC 时隐式主键已满足要求」的情形，
     * 索引 {@code idx_post_l1} 末尾不必追加 {@code id DESC}。
     *
     * <p>{@code root_id = 0} 与 {@code level = 1} 是等价的两种表达，
     * 用 level 是因为它是 idx_post_l1 的第三个列，等值条件能直接被索引吃掉。
     */
    List<Comment> selectRootPage(@Param("postId") Long postId,
                                 @Param("cursorFloor") Integer cursorFloor,
                                 @Param("size") int size);

    /**
     * 批量取每个根评论下最早的若干条回复。
     *
     * <p>用窗口函数 {@code ROW_NUMBER() OVER (PARTITION BY root_id ORDER BY create_time)}
     * 一次查完。<b>若不这样做，20 条一级评论就要查 20 次二级评论</b>——
     * 典型 N+1，在评论数多的帖子上会直接拖垮详情页。
     *
     * @param rootIds 一级评论 ID 列表，调用方需保证非空
     */
    List<Comment> selectTopReplies(@Param("rootIds") List<Long> rootIds,
                                   @Param("limit") int limit);

    /**
     * 取帖子内最大的楼层号，用于给 Redis 发号器播种。
     *
     * <p>只在计数键不存在时调用（Redis 冷启动，或该帖第一次收到评论）。
     * 不播种就会从 1 重新开始，而库里已经有 1-5 楼，于是出现两个「1 楼」。
     *
     * @return 无评论时返回 null
     */
    Integer selectMaxFloor(@Param("postId") Long postId);

    /**
     * 调整子回复数（仅一级评论维护）。
     *
     * <p>同样需要 {@code CAST(... AS SIGNED)}：{@code reply_count} 是无符号列，
     * 删除评论时的负数增量会让语句直接报错。
     */
    int addReplyCount(@Param("rootId") Long rootId, @Param("delta") int delta);

    /** 软删除。保留占位行，前端渲染成「该评论已删除」，避免对话上下文断裂 */
    int softDelete(@Param("id") Long id);

    /**
     * 批量调整评论点赞数。与 {@code PostMapper.batchAddLikeCount} 同构，
     * 因为 {@code t_user_like} 本就是一张统一表，两类目标的计数更新逻辑完全一样。
     */
    int batchAddLikeCount(@Param("deltas") List<TargetDelta> deltas);
}
