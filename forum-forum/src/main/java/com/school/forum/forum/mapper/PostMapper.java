package com.school.forum.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.forum.entity.Post;
import com.school.forum.forum.support.TargetDelta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子数据访问。
 *
 * <p><b>这里的方法都对应 {@code PostMapper.xml} 中的手写 SQL</b>，
 * 因为 {@code BaseMapper} 覆盖不了游标分页与批量计数更新这两类需求。
 *
 * <p><b>为什么按排序方式分成三个方法，而不是一个方法加排序参数：</b>
 * 用 {@code ${sortColumn}} 拼列名是 SQL 注入的经典入口，而用 {@code ORDER BY CASE ...}
 * 又会让优化器无法使用索引。三种排序各自对应一个复合索引，
 * 写成三条语句是唯一能让 {@code EXPLAIN} 走对索引的写法。
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 按 (publish_time, id) 游标分页。用于 {@code latest} 排序，以及精华区（此时 type=2）。
     *
     * <p>走 {@code idx_board_list (board_id, status, type, publish_time DESC, id DESC)}：
     * 前三个等值条件 + 一个行值范围比较，排序完全由索引提供，无 filesort。
     *
     * <p>{@code boardId} 传 null 表示跨板块的全站信息流（首页）。此时索引的最左列
     * 没有等值条件，排序只能靠 filesort——<b>这是首页唯一的性能取舍</b>，
     * 帖子量大到排序成为瓶颈时的解法是加一个不含 board_id 的索引，
     * 而不是把首页砍掉或强行套用一个板块的索引。
     *
     * @param cursorTime 上一页最后一条的发布时间，首页传 null
     * @param cursorId   上一页最后一条的 id，首页传 null
     */
    List<Post> selectPageByTime(@Param("boardId") Long boardId,
                                @Param("type") int type,
                                @Param("cursorTime") LocalDateTime cursorTime,
                                @Param("cursorId") Long cursorId,
                                @Param("size") int size);

    /** 按 (hot_score, id) 游标分页，走 {@code idx_board_hot} */
    List<Post> selectPageByHot(@Param("boardId") Long boardId,
                               @Param("cursorScore") BigDecimal cursorScore,
                               @Param("cursorId") Long cursorId,
                               @Param("size") int size);

    /**
     * 置顶与公告帖（{@code type != 0}）。
     *
     * <p><b>刻意不与普通帖合并成一条 SQL。</b>{@code type != 0} 是范围条件，
     * 一旦它参与排序，MySQL 就无法既用同一个索引完成范围扫描又保证有序输出，
     * 必然退化成 filesort（《04-数据库设计》4.2 节有实测）。
     * 本方法的结果集极小（每板块通常不到 10 条），走 {@code idx_pinned}
     * 且允许少量排序开销，由服务层拼接到列表顶部。
     */
    List<Post> selectPinned(@Param("boardId") Long boardId);

    /**
     * 批量调整点赞数。
     *
     * <p><b>为什么必须 {@code CAST(... AS SIGNED)}：</b>{@code like_count} 是
     * {@code INT UNSIGNED}，而取消点赞的增量是负数。MySQL 中「无符号 + 有符号负数」
     * 的结果仍按无符号处理，1 + (-3) 会直接报 "value is out of range" 而中断整条语句。
     * 先转成有符号算出结果，再由 {@code GREATEST(0, ...)} 兜底到 0。
     *
     * @param deltas 同一批中标点赞数变化的帖子，按 targetId 聚合后去重
     */
    int batchAddLikeCount(@Param("deltas") List<TargetDelta> deltas);

    /** 调整评论数，并同步更新最后评论时间（{@code last_comment_time} 是「最新回复」排序的依据） */
    int addCommentCount(@Param("postId") Long postId,
                        @Param("delta") int delta,
                        @Param("lastCommentTime") LocalDateTime lastCommentTime);

    /** 批量刷回浏览数 */
    int batchAddViewCount(@Param("deltas") List<TargetDelta> deltas);
}
