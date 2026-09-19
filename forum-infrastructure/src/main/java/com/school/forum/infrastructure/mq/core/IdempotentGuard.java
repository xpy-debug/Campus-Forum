package com.school.forum.infrastructure.mq.core;

import com.school.forum.common.constant.RedisKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 消费幂等守卫。基于 Redis SETNX 实现「同一条消息在同一个消费者组内只被处理一次」。
 *
 * <p><b>为什么不用 {@code t_mq_consume_log} 的唯一索引做幂等？</b>
 * 因为那意味着每条消息都要写一次 MySQL。压测场景下 MySQL 会先于 MQ 达到瓶颈，
 * 测出来的就不再是 MQ 的吞吐差异了。Redis SETNX 是内存操作，
 * 开销比 MQ 本身小一个数量级，不会污染对比结果。
 *
 * <p><b>为什么它不能作为唯一的幂等防线：</b>
 * <ul>
 *   <li>有 TTL，过期后的重复消息拦不住</li>
 *   <li>Redis 主从切换时可能丢失刚写入的 key</li>
 *   <li>「先 SETNX 成功、再执行业务、最后写库」之间进程崩溃，Redis 里有记录但数据库里没有</li>
 * </ul>
 * 真正可靠的幂等必须落在<b>数据库唯一索引</b>上：
 * 点赞靠 {@code t_user_like} 的 {@code (user_id, target_type, target_id)}，
 * 秒杀订单靠 {@code t_seckill_order} 的 {@code (activity_id, user_id)}。
 * 本类只是挡住绝大部分重复、降低下游压力的一道前置过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentGuard {

    /** key 形如 {@code forum:mq:consumed:{group}:{eventId}} */
    private static final String KEY_PREFIX = RedisKey.PREFIX + "mq:consumed:";

    private final StringRedisTemplate stringRedisTemplate;
    private final MqProperties properties;

    /**
     * 尝试获取处理权。
     *
     * @return {@code true} 表示首次处理，应继续执行业务；
     *         {@code false} 表示已处理过，调用方应直接 ack 并跳过
     */
    public boolean tryAcquire(String consumerGroup, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            // 没有 eventId 就无法去重。放行而不是拦截——丢消息比重复处理更糟，
            // 且不是所有历史消息都带 eventId。记 WARN 以便发现异常生产者。
            log.warn("消息缺少 eventId，跳过幂等校验。consumerGroup={}", consumerGroup);
            return true;
        }
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                    buildKey(consumerGroup, eventId),
                    "1",
                    Duration.ofHours(properties.getIdempotentTtlHours()));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // 【失败开放】Redis 不可用时放行，而不是拦截。
            // 拦截会导致 Redis 一挂就丢光所有消息；放行最多造成重复消费，
            // 而重复消费由数据库唯一索引兜底。两害相权取其轻。
            log.error("幂等校验异常，本次放行。consumerGroup={}, eventId={}", consumerGroup, eventId, e);
            return true;
        }
    }

    /**
     * 释放处理权。<b>业务处理返回 RETRY 时必须调用。</b>
     *
     * <p>这是最容易写错的一步：如果只 acquire 不 release，
     * 那么「第一次处理失败 → 消息重新入队 → 第二次处理时 SETNX 失败 →
     * 被当成重复消息丢弃」——消息就永久丢失了，而且日志上看起来一切正常，
     * 极难排查。
     */
    public void release(String consumerGroup, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(buildKey(consumerGroup, eventId));
        } catch (Exception e) {
            log.error("释放幂等标记失败，该消息在重试时可能被误判为重复。consumerGroup={}, eventId={}",
                    consumerGroup, eventId, e);
        }
    }

    private String buildKey(String consumerGroup, String eventId) {
        return KEY_PREFIX + consumerGroup + ":" + eventId;
    }
}
