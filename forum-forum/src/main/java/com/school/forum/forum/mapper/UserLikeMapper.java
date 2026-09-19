package com.school.forum.forum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.forum.entity.UserLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 点赞记录数据访问。
 */
@Mapper
public interface UserLikeMapper extends BaseMapper<UserLike> {

    /**
     * 批量写入点赞记录，冲突即跳过（{@code INSERT IGNORE}）。
     *
     * <p><b>为什么不是普通 INSERT：</b>消费端可能重复消费同一条消息，
     * 而 {@code uk_user_target} 唯一索引会让第二次插入失败。用 {@code INSERT IGNORE}
     * 让数据库直接跳过冲突行，比抛异常再由上层捕获高效得多——异常在 JVM 里
     * 构造堆栈的代价很高，几千 QPS 下会明显拖慢消费。
     *
     * <p><b>它带来的幂等语义：</b>「插入成功」与「已经存在」都算处理完成，
     * 这一点对 at-least-once 的消息投递是必需的。
     */
    int batchInsertIgnore(@Param("list") List<UserLike> list);

    /**
     * 批量删除点赞记录（取消点赞）。
     *
     * <p>用行值构造器 {@code (user_id, target_type, target_id) IN ((?,?,?), ...)}
     * 一次删掉整批，避免逐条 DELETE 的往返开销。
     */
    int batchDelete(@Param("list") List<UserLike> list);

    /**
     * 统计某个目标当前的点赞行数。
     *
     * <p>用于 Redis 计数丢失后的重建（冷启动自愈）：Redis 里的
     * {@code like:count} 键不存在时，以本方法的结果播种。
     * 走 {@code uk_user_target} 的前缀（user_id 不匹配，实际走 idx_target 的
     * target_type + target_id 前缀）。
     */
    int countByTarget(@Param("targetType") int targetType, @Param("targetId") Long targetId);
}
