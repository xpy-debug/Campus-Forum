package com.school.forum.forum.service;

import com.school.forum.forum.vo.LikeVO;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 点赞。帖子与评论共用一套实现，靠 {@code targetType} 区分。
 *
 * <p><b>本模块的核心链路。</b>接口的实现承诺是「立即返回」：Redis 是实时真相
 * （用户点完立刻看到数字变化），MySQL 是最终真相（由消费端批量落库）。
 * 因此这里的方法<b>不会</b>等待数据库写入完成。
 */
public interface LikeService {

    /**
     * 点赞。重复点赞不报错，返回当前状态（幂等）。
     */
    LikeVO like(Long userId, Integer targetType, Long targetId);

    /**
     * 取消点赞。未点赞时同样不报错。
     */
    LikeVO unlike(Long userId, Integer targetType, Long targetId);

    /**
     * 用 Redis 的实时计数覆盖数据库的冗余计数。
     *
     * <p>为什么需要「覆盖」而不是「直接取」：Redis 里的计数键可能不存在
     * （进程重启、缓存淘汰、冷启动）。此时绝不能返回 0，而应当回退到
     * 数据库的 {@code like_count} 列——它是上一轮同步后的值，虽不是最新，
     * 但远比 0 接近真相。调用方把实体上的计数作为基准传进来即可。
     *
     * @param dbCounts 目标 ID → 数据库冗余计数。返回结果的键集合与它一致
     */
    Map<Long, Long> mergeLikeCounts(int targetType, Map<Long, Long> dbCounts);

    /**
     * 批量判断当前用户点赞过哪些目标。
     *
     * <p>用 Pipeline 一次网络往返完成 N 次 {@code SISMEMBER}。
     * 一页 20 条帖子若逐条查询，就是 20 次 Redis 往返，光 RTT 就要 20-40ms。
     *
     * @param userId 未登录时传 null，直接返回空集合
     */
    Set<Long> likedTargets(Long userId, int targetType, Collection<Long> targetIds);
}
