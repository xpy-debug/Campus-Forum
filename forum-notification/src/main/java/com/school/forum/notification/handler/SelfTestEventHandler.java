package com.school.forum.notification.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.common.constant.MqTopic;
import com.school.forum.common.constant.RedisKey;
import com.school.forum.infrastructure.mq.core.ConsumeResult;
import com.school.forum.infrastructure.mq.core.EventHandler;
import com.school.forum.infrastructure.mq.core.EventPublisher;
import com.school.forum.notification.event.SelfTestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 链路自检消费者。
 *
 * <p><b>这是本项目里最简单的 {@link EventHandler} 实现，可以作为写新消费者的模板：</b>
 * <ol>
 *   <li>实现 {@code topic()} 返回订阅的主题（取自 {@code MqTopic} 常量）</li>
 *   <li>实现 {@code eventType()} 返回事件类型，供反序列化使用</li>
 *   <li>实现 {@code handle()} 写业务逻辑，返回 {@link ConsumeResult}</li>
 * </ol>
 * <b>不需要任何 MQ 产品相关的注解</b>——不写 {@code @KafkaListener}，
 * 也不写 {@code @RabbitListener}。注册成 Spring Bean 之后，
 * 由 {@code KafkaEventSubscriber} 或 {@code RabbitMqEventSubscriber}
 * 自动发现并绑定。这正是切换 MQ 不需要改业务代码的原因。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfTestEventHandler implements EventHandler<SelfTestEvent> {

    /** 最近一次自检结果，JSON 格式 */
    private static final String KEY_LAST = RedisKey.PREFIX + "selftest:last";

    /** 自检消息累计接收数 */
    private static final String KEY_COUNT = RedisKey.PREFIX + "selftest:count";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;

    @Override
    public String topic() {
        return MqTopic.SELF_TEST;
    }

    @Override
    public Class<SelfTestEvent> eventType() {
        return SelfTestEvent.class;
    }

    @Override
    public ConsumeResult handle(SelfTestEvent event) {
        long now = System.currentTimeMillis();
        long e2eMillis = now - event.getSentAtMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", event.getEventId());
        result.put("note", event.getNote());
        // 记录是哪个 MQ 实现收到的。同一个 Redis 里可能先后存入两家实现的记录，
        // 有这个字段才能确认「现在生效的到底是哪一个」
        result.put("provider", eventPublisher.provider());
        result.put("sentAtMillis", event.getSentAtMillis());
        result.put("receivedAtMillis", now);
        // 端到端延迟 = MQ 传输 + 排队 + 消费调度。它不等于 MQ 的纯网络延迟，
        // 但对选型来说这个数才是用户真正感知到的延迟。
        result.put("e2eMillis", e2eMillis);

        try {
            stringRedisTemplate.opsForValue().set(KEY_LAST,
                    objectMapper.writeValueAsString(result), Duration.ofHours(1));
            Long count = stringRedisTemplate.opsForValue().increment(KEY_COUNT);
            stringRedisTemplate.expire(KEY_COUNT, Duration.ofHours(1));
            log.info("自检消息已消费。provider={}, eventId={}, 端到端 {} ms, 累计 {} 条",
                    eventPublisher.provider(), event.getEventId(), e2eMillis, count);
        } catch (Exception e) {
            // 写 Redis 失败不影响消息处理本身的正确性，
            // 但会让人误以为「消息没到」。所以记 ERROR 而不是吞掉。
            log.error("自检结果写入 Redis 失败", e);
        }

        // 返回 SUCCESS 后，抽象层会 ack 消息（Kafka 提交 offset / RabbitMQ basicAck）
        return ConsumeResult.SUCCESS;
    }

    /**
     * 消费者组名。
     *
     * <p>刻意加 {@code selftest-} 前缀：Kafka 的消费组是全局的，
     * 如果自检消费者和业务消费者用了同一个组名，会互相抢消息——
     * 自检消息被业务逻辑消费掉，或业务消息被自检逻辑吞掉，两种都是灾难。
     */
    @Override
    public String consumerGroup() {
        return "selftest-handler";
    }
}
