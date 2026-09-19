package com.school.forum.infrastructure.mq.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.common.constant.MqTopic;
import com.school.forum.infrastructure.mq.core.BaseEvent;
import com.school.forum.infrastructure.mq.core.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RabbitMQ 实现的消息发送器。
 *
 * <p><b>⚠ 这里有一个抽象层无法抹平的概念差异，必须说清楚：</b>
 *
 * <p>{@link EventPublisher#publish(String, String, BaseEvent)} 的第二个参数在两家产品里
 * 含义完全不同：
 * <ul>
 *   <li><b>Kafka</b>：它是<b>分区键</b>。同一个键的消息进同一分区，因此天然保序，
 *       且不同键之间并行。用业务键（如 {@code post:123}）能同时拿到「同帖有序」和「跨帖并行」。</li>
 *   <li><b>RabbitMQ</b>：它是<b>路由键</b>，唯一作用是决定消息投递到哪些队列，
 *       由绑定规则匹配。如果这里填业务键 {@code post:123}，
 *       它会匹配不到任何绑定（绑定用的是主题名 {@code forum.like}），消息直接被丢弃。</li>
 * </ul>
 *
 * <p>所以 RabbitMQ 侧的做法是：<b>路由键固定用主题名，业务键放进消息头
 * {@code x-biz-key}</b>。代价是 <b>RabbitMQ 拿不到按键保序</b>——
 * 它只能保证单队列内有序，而队列一旦有多个并发消费者，连这个也保不住。
 *
 * <p>这不是实现缺陷，而是两个产品的本质区别：Kafka 的顺序性建立在分区模型上，
 * RabbitMQ 的顺序性建立在队列模型上。对「点赞 / 取消点赞」这种
 * <b>同一对象的操作必须按序生效</b>的场景，这个差异会真实影响正确性，
 * 是选型时必须称重的因素之一。要弥补的话，RabbitMQ 侧得靠
 * 一致性哈希交换机把同一业务键固定到同一队列，或者用单消费者队列牺牲吞吐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "forum.mq", name = "provider", havingValue = "rabbit")
public class RabbitMqEventPublisher implements EventPublisher {

    public static final String PROVIDER = "rabbit";

    /** 业务键的消息头名。消费侧读它做业务维度的排查与统计 */
    public static final String HEADER_BIZ_KEY = "x-biz-key";

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final ObjectMapper objectMapper;

    /** 已声明过的延迟队列，避免每条延迟消息都发一次 declare（那是一次网络往返） */
    private final Set<String> declaredDelayQueues = ConcurrentHashMap.newKeySet();

    @Override
    public void publish(String topic, BaseEvent event) {
        publish(topic, event.routingKey(), event);
    }

    @Override
    public void publish(String topic, String routingKey, BaseEvent event) {
        try {
            doSend(topic, routingKey, objectMapper.writeValueAsString(event), event.getEventId());
        } catch (Exception e) {
            log.error("RabbitMQ 消息序列化失败，消息已丢弃。topic={}, eventId={}", topic, event.getEventId(), e);
        }
    }

    @Override
    public void publishRaw(String topic, String routingKey, String payload) {
        doSend(topic, routingKey, payload, "-");
    }

    private void doSend(String topic, String routingKey, String payload, String eventId) {
        rabbitTemplate.convertAndSend(MqTopic.EXCHANGE, topic, payload, message -> {
            MessageProperties props = message.getMessageProperties();
            // 路由键已经用于路由（= topic），业务键只能放头里带走
            props.setHeader(HEADER_BIZ_KEY, routingKey);
            // 持久化：Broker 重启后消息仍在。压测时必须固定为 PERSISTENT，
            // 否则测出来的是「内存队列」的性能，与生产环境不可比。
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setContentEncoding(StandardCharsets.UTF_8.name());
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            return message;
        });
        if (log.isDebugEnabled()) {
            log.debug("RabbitMQ 消息已发送。exchange={}, routingKey={}, bizKey={}, eventId={}",
                    MqTopic.EXCHANGE, topic, routingKey, eventId);
        }
    }

    /**
     * 延迟投递。<b>这是 RabbitMQ 相对 Kafka 明显占优的地方</b>——
     * 用原生 TTL + 死信队列即可实现，消息全程留在 Broker 上，
     * 不占用消费线程，也不依赖数据库。
     *
     * <p>与 Kafka 侧实现的对比：
     * <table border="1">
     *   <caption>延迟消息的两种实现</caption>
     *   <tr><th></th><th>RabbitMQ（本方法）</th><th>Kafka（本地消息表）</th></tr>
     *   <tr><td>依赖</td><td>Broker 自身</td><td>MySQL + 调度线程</td></tr>
     *   <tr><td>精度</td><td>毫秒级，但队首阻塞时会有额外延迟</td><td>受调度间隔限制（默认 1 秒）</td></tr>
     *   <tr><td>吞吐</td><td>与普通消息相同</td><td>受数据库写入能力限制</td></tr>
     *   <tr><td>崩溃安全</td><td>是（消息持久化在 Broker）</td><td>是（消息持久化在表里）</td></tr>
     * </table>
     */
    @Override
    public void publishDelay(String topic, String routingKey, BaseEvent event, int delaySeconds) {
        try {
            String queueName = RabbitMqConfig.delayQueueName(topic, delaySeconds);
            declareDelayQueueOnce(queueName, topic, delaySeconds);

            String payload = objectMapper.writeValueAsString(event);
            // 发到默认交换机（""），routing key 直接写队列名——
            // 默认交换机是 direct 类型且每个队列都自动以队列名绑定，
            // 因此这样能精确投递到指定队列，不需要再为延迟队列建一套绑定。
            rabbitTemplate.convertAndSend("", queueName, payload, message -> {
                MessageProperties props = message.getMessageProperties();
                props.setHeader(HEADER_BIZ_KEY, routingKey);
                props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                return message;
            });
            log.debug("RabbitMQ 延迟消息已投递。queue={}, delay={}s, eventId={}",
                    queueName, delaySeconds, event.getEventId());
        } catch (Exception e) {
            log.error("RabbitMQ 延迟消息投递失败。topic={}, eventId={}", topic, event.getEventId(), e);
        }
    }

    /**
     * 延迟队列按需声明。
     *
     * <p>之所以不在启动时一次性把所有延迟队列建好：延迟时长是调用方传进来的任意值，
     * 启动时并不知道会有哪些。按需声明 + 本地缓存是常见做法。
     * 缓存只为了避免重复的 declare 网络往返；即使缓存失效导致重复声明，
     * RabbitMQ 的队列声明是幂等的（参数一致时不会报错）。
     *
     * <p><b>注意这里只声明队列、不声明绑定。</b>延迟队列不挂在自己的业务交换机上——
     * 消息是发到默认交换机（{@code ""}）、以队列名作 routing key 直接入队的。
     * 真正需要绑定的是它死信之后的去向，而那是队列自身的
     * {@code x-dead-letter-exchange} / {@code x-dead-letter-routing-key} 参数决定的，
     * 也不需要额外的 Binding 对象。
     */
    private void declareDelayQueueOnce(String queueName, String topic, int delaySeconds) {
        if (declaredDelayQueues.add(queueName)) {
            amqpAdmin.declareQueue(RabbitMqConfig.delayQueue(topic, delaySeconds));
            log.info("已声明延迟队列。queue={}, delay={}s", queueName, delaySeconds);
        }
    }

    @Override
    public String provider() {
        return PROVIDER;
    }
}
