package com.school.forum.infrastructure.mq.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.infrastructure.mq.core.BaseEvent;
import com.school.forum.infrastructure.mq.core.EventPublisher;
import com.school.forum.infrastructure.mq.mapper.MqOutboxMapper;
import com.school.forum.infrastructure.mq.outbox.MqOutboxMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Kafka 实现的消息发送器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "forum.mq", name = "provider", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    public static final String PROVIDER = "kafka";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MqOutboxMapper outboxMapper;

    @Override
    public void publish(String topic, BaseEvent event) {
        publish(topic, event.routingKey(), event);
    }

    @Override
    public void publish(String topic, String routingKey, BaseEvent event) {
        try {
            doSend(topic, routingKey, objectMapper.writeValueAsString(event), event.getEventId());
        } catch (Exception e) {
            log.error("Kafka 消息序列化失败，消息已丢弃。topic={}, eventId={}", topic, event.getEventId(), e);
        }
    }

    @Override
    public void publishRaw(String topic, String routingKey, String payload) {
        doSend(topic, routingKey, payload, "-");
    }

    private void doSend(String topic, String routingKey, String payload, String eventId) {
        // 不阻塞等待 Broker 确认：发送结果由回调处理。
        // 如果在这里 get() 等确认，MQ 的一次网络往返会直接叠加到接口响应时间上，
        // 而点赞这类接口的目标是 P99 < 100ms，经不起这么等。
        kafkaTemplate.send(topic, routingKey, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // 必须记日志：异步发送的失败不会有任何其他出口，
                        // 静默失败意味着消息凭空消失，且没有任何痕迹
                        log.error("Kafka 消息发送失败。topic={}, key={}, eventId={}",
                                topic, routingKey, eventId, ex);
                    } else if (log.isDebugEnabled()) {
                        var meta = result.getRecordMetadata();
                        log.debug("Kafka 消息已发送。topic={}, partition={}, offset={}, eventId={}",
                                meta.topic(), meta.partition(), meta.offset(), eventId);
                    }
                });
    }

    /**
     * 延迟投递。
     *
     * <p><b>Kafka 没有原生的延迟消息能力</b>，这是它与 RabbitMQ 最实质的差异之一。
     * 常见的三种绕法：
     * <ol>
     *   <li>写本地消息表，到期由调度器投递 —— <b>本项目采用</b>，崩溃安全</li>
     *   <li>投到中间 topic，消费者拿到后 sleep 再转发 —— 会长时间占用消费线程，
     *       延迟超过 {@code max.poll.interval.ms} 时还会触发 rebalance，不推荐</li>
     *   <li>自研时间轮 —— 实现复杂度高，且进程重启后内存中的定时器全丢</li>
     * </ol>
     *
     * <p>代价是延迟消息这条链路要经过 MySQL，吞吐远低于正常消息，
     * 端到端延迟也远高于 RabbitMQ 的 TTL+DLX 方案。这个差距本身就是选型结论的一部分。
     */
    @Override
    public void publishDelay(String topic, String routingKey, BaseEvent event, int delaySeconds) {
        try {
            MqOutboxMessage message = new MqOutboxMessage();
            message.setMessageId(event.getEventId());
            message.setProvider(PROVIDER);
            message.setTopic(topic);
            message.setRoutingKey(routingKey);
            message.setBizType(event.getClass().getSimpleName());
            message.setBizKey(routingKey);
            message.setPayload(objectMapper.writeValueAsString(event));
            message.setHeaders("{}");
            message.setStatus(MqOutboxMessage.STATUS_PENDING);
            message.setRetryCount(0);
            message.setMaxRetry(5);
            // 延迟就体现在这里：调度器只投递 next_retry_time 已到的消息
            message.setNextRetryTime(LocalDateTime.now().plusSeconds(delaySeconds));
            message.setErrorMsg("");
            outboxMapper.insert(message);
            log.debug("延迟消息已写入本地消息表。topic={}, delay={}s, eventId={}",
                    topic, delaySeconds, event.getEventId());
        } catch (Exception e) {
            log.error("写入延迟消息失败。topic={}, eventId={}", topic, event.getEventId(), e);
        }
    }

    @Override
    public String provider() {
        return PROVIDER;
    }
}
