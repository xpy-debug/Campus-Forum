package com.school.forum.forum.consumer;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.forum.entity.UserLike;
import com.school.forum.forum.event.LikeChangedEvent;
import com.school.forum.forum.mapper.CommentMapper;
import com.school.forum.forum.mapper.PostMapper;
import com.school.forum.forum.mapper.UserLikeMapper;
import com.school.forum.forum.support.TargetDelta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一批点赞事件落到 MySQL。
 *
 * <p><b>单独成类而不是写成 {@code LikeBatchWriter} 的私有方法：</b>
 * {@code @Transactional} 靠 Spring 代理生效，而代理只拦截「从其他 Bean 发起的调用」。
 * 若把落库逻辑写成 writer 的私有方法，由它自己的定时任务调用，
 * 就是自调用（self-invocation），注解会被完全忽略——事务看起来加了，
 * 实际每一步都在各自的自动提交连接上跑，中途失败就会留下「记录写了、计数没更新」的脏数据。
 *
 * <p>为什么事务是必须的：一次 flush 里有三种写操作（插入点赞行、删除点赞行、更新计数），
 * 它们描述的是同一个事实，只做一半比完全不做更难排查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeBatchPersister {

    private final UserLikeMapper userLikeMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final StringRedisTemplate redis;

    @Transactional(rollbackFor = Exception.class)
    public void persist(List<LikeChangedEvent> batch) {
        List<LikeChangedEvent> effective = batch.stream()
                .filter(event -> !isStale(event))
                .toList();
        List<LikeChangedEvent> deduped = latestPerUserTarget(effective);
        if (deduped.isEmpty()) {
            return;
        }

        List<TargetDelta> postDeltas = new ArrayList<>();
        List<TargetDelta> commentDeltas = new ArrayList<>();
        for (Map.Entry<TargetKey, List<LikeChangedEvent>> entry : groupByTarget(deduped).entrySet()) {
            int delta = applyTarget(entry.getValue());
            if (delta != 0) {
                TargetKey target = entry.getKey();
                (target.targetType() == UserLike.TARGET_POST ? postDeltas : commentDeltas)
                        .add(new TargetDelta(target.targetId(), delta));
            }
        }

        // 计数更新放在最后，且按目标聚合后一次发出：
        // 一批 200 条点赞可能落在几十个帖子上，逐条 UPDATE 就是几十次往返
        if (!postDeltas.isEmpty()) {
            postMapper.batchAddLikeCount(postDeltas);
        }
        if (!commentDeltas.isEmpty()) {
            commentMapper.batchAddLikeCount(commentDeltas);
        }
        log.debug("点赞批量落库完成。事件数={}, 帖子数={}, 评论数={}",
                deduped.size(), postDeltas.size(), commentDeltas.size());
    }

    /**
     * 处理同一个目标下的一批事件，返回应该加到计数上的增量。
     *
     * <p><b>增量取自「数据库实际影响的行数」，而不是事件条数。</b>
     * 这是整条链路能自我纠偏的关键：某次点赞因为唯一索引冲突被跳过（重复消费），
     * 或者某次取消点赞删了 0 行（那条记录本来就不在），都不会让计数漂移。
     * 若按事件条数累加，一次重复投递就会让计数永久多 1。
     */
    private int applyTarget(List<LikeChangedEvent> events) {
        List<UserLike> toInsert = new ArrayList<>();
        List<UserLike> toDelete = new ArrayList<>();
        for (LikeChangedEvent event : events) {
            UserLike row = new UserLike();
            row.setUserId(event.getUserId());
            row.setTargetType(event.getTargetType());
            row.setTargetId(event.getTargetId());
            row.setTargetOwnerId(event.getTargetOwnerId());
            // 用事件发生时刻而不是落库时刻：用户看到的「点赞时间」应当是他点击的那一刻，
            // 而不是半秒后这批消息被 flush 的时刻
            row.setCreateTime(event.getOccurredAt());
            (event.isLiked() ? toInsert : toDelete).add(row);
        }
        // 空集合必须跳过：foreach 会拼出 `IN ()`，那不是合法的 SQL
        int inserted = toInsert.isEmpty() ? 0 : userLikeMapper.batchInsertIgnore(toInsert);
        int deleted = toDelete.isEmpty() ? 0 : userLikeMapper.batchDelete(toDelete);
        return inserted - deleted;
    }

    /**
     * 乱序守卫：只处理比 Redis 中记录的时间戳更新的事件。
     *
     * <p>点赞与取消点赞是两个主题，跨主题没有顺序保证——用户快速「点赞→取消」时，
     * 取消完全可能先被消费，最终落库结果就与用户看到的界面相反。
     *
     * <p>时间戳由生产者在操作发生时写入 Redis，<b>消费端只读不写</b>。
     * 这一点很重要：如果消费端也去更新它，一个较早的事件处理完就会把值改小，
     * 后面那个更新的、真正该生效的事件反而会被判为「过期」而跳过。
     *
     * <p>由此可以得到一条不变式：<b>时间戳最大的那个事件永远不会被跳过</b>
     * （除非有比它更晚的操作发生，而那正是应当生效的操作）。
     * 它描述的又是一个状态而非增量，所以最终状态一定收敛到用户的最后一次操作。
     */
    private boolean isStale(LikeChangedEvent event) {
        String stored = redis.opsForValue().get(
                RedisKey.likeTimestamp(event.getTargetType(), event.getTargetId(), event.getUserId()));
        if (stored == null) {
            return false;
        }
        try {
            return Long.parseLong(stored) > event.occurredAtMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 同一个 (用户, 目标) 在一批里出现多次时，只保留最后一次。
     *
     * <p>用户连点两下「点赞→取消」时，两个事件可能被同一批捞到一起。
     * 先插后删虽然结果也对，但会白白产生一次插入和一次删除，
     * 还会让 {@code create_time} 出现一个转瞬即逝的中间态。
     */
    private List<LikeChangedEvent> latestPerUserTarget(List<LikeChangedEvent> events) {
        Map<String, LikeChangedEvent> latest = new HashMap<>();
        for (LikeChangedEvent event : events) {
            String key = event.getUserId() + ":" + event.getTargetType() + ":" + event.getTargetId();
            LikeChangedEvent previous = latest.get(key);
            if (previous == null || event.occurredAtMillis() >= previous.occurredAtMillis()) {
                latest.put(key, event);
            }
        }
        return new ArrayList<>(latest.values());
    }

    private Map<TargetKey, List<LikeChangedEvent>> groupByTarget(List<LikeChangedEvent> events) {
        Map<TargetKey, List<LikeChangedEvent>> groups = new HashMap<>();
        for (LikeChangedEvent event : events) {
            groups.computeIfAbsent(new TargetKey(event.getTargetType(), event.getTargetId()),
                    key -> new ArrayList<>()).add(event);
        }
        return groups;
    }

    private record TargetKey(int targetType, Long targetId) {
    }
}
