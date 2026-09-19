package com.school.forum.infrastructure.mq.kafka;

import com.school.forum.infrastructure.mq.core.ConsumeResult;
import com.school.forum.infrastructure.mq.core.EventHandler;
import com.school.forum.infrastructure.mq.core.MessageDispatcher;
import com.school.forum.infrastructure.mq.core.MqProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kafka 消费端。<b>启动时为每个 {@link EventHandler} 建一个监听容器</b>。
 *
 * <p><b>为什么不用 {@code @KafkaListener} 注解：</b>那个注解会把 Kafka 这个概念
 * 直接钉死在业务模块的代码里。业务模块只要沾上它，切换 MQ 就必须改代码，
 * 「同一份业务代码跑两家 MQ 做对比」这个前提就不成立了。
 * 本类把所有 Kafka 细节收敛在 infrastructure 一层：业务方只实现
 * {@link EventHandler}，由本类在启动时扫描并绑定。
 *
 * <p>实现 {@link SmartLifecycle} 而不是在构造器里启动容器，是为了让所有
 * Handler Bean 都完成初始化之后再开始消费——否则可能在依赖还没就绪时收到消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "forum.mq", name = "provider", havingValue = "kafka")
public class KafkaEventSubscriber implements SmartLifecycle {

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final List<EventHandler<?>> handlers;
    private final MessageDispatcher dispatcher;
    private final MqProperties properties;

    private final List<ConcurrentMessageListenerContainer<String, String>> containers = new ArrayList<>();

    /**
     * 每条消息已重试的次数，键为 {@code topic-partition-offset}。
     *
     * <p><b>为什么需要自己维护：</b>Kafka 的消息头是生产者写入的、不可变的，
     * 消费端无法像 RabbitMQ 那样「把重试次数加一后重新入队」。
     * 好在 {@code ack.nack(duration)} 的做法是把消费者 seek 回原 offset 重读<b>同一条消息</b>，
     * 所以 {@code topic-partition-offset} 在重试期间保持不变，用它做键是可靠的。
     *
     * <p><b>局限：</b>这是进程内状态，发生 rebalance 或重启后会归零，
     * 重试次数重新开始计。对「防止毒消息卡死消费」这个目的来说够用
     * （最坏情况是多重试几轮），但不要用它做精确的投递次数统计。
     */
    private final Map<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    @Override
    public void start() {
        if (handlers.isEmpty()) {
            log.warn("没有发现任何 EventHandler，Kafka 消费者未启动");
            running = true;
            return;
        }
        for (EventHandler<?> handler : handlers) {
            try {
                containers.add(createContainer(handler));
            } catch (Exception e) {
                log.error("创建 Kafka 监听容器失败。handler={}, topic={}",
                        handler.getClass().getName(), handler.topic(), e);
            }
        }
        containers.forEach(ConcurrentMessageListenerContainer::start);
        running = true;
        log.info("Kafka 消费者已启动，共 {} 个容器，并发度 {}",
                containers.size(), properties.getKafka().getConcurrency());
    }

    private ConcurrentMessageListenerContainer<String, String> createContainer(EventHandler<?> handler) {
        ContainerProperties containerProps = new ContainerProperties(handler.topic());
        containerProps.setGroupId(groupId(handler));
        containerProps.setMessageListener(buildListener(handler));

        // 手动提交：由我们根据业务处理结果决定 ack 还是 nack。
        // 用 MANUAL_IMMEDIATE 而不是 MANUAL：前者在处理完一条后立即提交，
        // 后者要等整批处理完才提交。压测时批次可能很大，
        // MANUAL 模式下「已成功处理的消息」在批次结束前不会被提交，
        // 一旦崩溃就会整批重复消费，反而放大了重复率。
        containerProps.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        container.setConcurrency(properties.getKafka().getConcurrency());
        container.setBeanName("kafka-listener-" + handler.consumerGroup());

        log.info("注册 Kafka 消费者。topic={}, group={}, eventType={}",
                handler.topic(), groupId(handler), handler.eventType().getSimpleName());
        return container;
    }

    /**
     * 用 {@link AcknowledgingMessageListener} 而不是 {@code MessageListener}：
     * 后者只提供 {@code onMessage(record)} 一个方法，拿不到 {@code Acknowledgment}，
     * 也就无法手动提交位点——而手动提交正是「处理成功才 ack、失败要重试」
     * 这套语义的前提。名字里少了个 Acknowledging，能力差的是整个可靠性保证。
     */
    private AcknowledgingMessageListener<String, String> buildListener(EventHandler<?> handler) {
        return (record, ack) -> {
            String key = record.topic() + "-" + record.partition() + "-" + record.offset();
            int retryCount = retryCounters.getOrDefault(key, new AtomicInteger(0)).get();

            ConsumeResult result = dispatcher.dispatch(handler, record.value(), retryCount);

            switch (result) {
                case SUCCESS -> {
                    retryCounters.remove(key);
                    ack.acknowledge();
                }
                case RETRY -> {
                    int next = retryCounters
                            .computeIfAbsent(key, k -> new AtomicInteger(0))
                            .incrementAndGet();
                    // nack(duration)：把消费者 seek 回本条的 offset，暂停 duration 后重新投递。
                    // 退避是必要的——下游刚出问题时立刻重试大概率还是失败，
                    // 连续快速重试会把下游彻底打垮，形成雪崩。
                    long backoff = properties.getRetryBackoffMs() * next;
                    log.warn("消息处理失败，{}ms 后重试（第 {} 次）。topic={}, offset={}",
                            backoff, next, record.topic(), record.offset());
                    ack.nack(Duration.ofMillis(backoff));
                }
                case DISCARD -> {
                    retryCounters.remove(key);
                    // 先转发到死信 topic，再 ack 原消息。顺序不能反——
                    // 先 ack 的话，转发失败这条消息就永久没了。
                    //
                    // 【Kafka 的固有局限】这里只能做到「尽力转发」：
                    // Kafka 无法像 RabbitMQ 那样对单条消息 requeue，
                    // 消费位点一旦越过就只能整个分区 seek 回来。
                    // 所以转发失败时除了记 ERROR 日志没有更好的补救手段，
                    // 而 RabbitMQ 侧 basicNack(requeue=false) 是由 Broker 保证
                    // 「要么进死信队列、要么留在原队列」的原子语义。
                    // 这是两者在错误处理上的一个真实差距。
                    try {
                        kafkaTemplate.send(record.topic() + ".DLT", record.key(), record.value());
                    } catch (Exception e) {
                        log.error("死信转发失败，该消息将被跳过。topic={}, partition={}, offset={}",
                                record.topic(), record.partition(), record.offset(), e);
                    }
                    ack.acknowledge();
                    log.error("消息进入死信。topic={}, partition={}, offset={}",
                            record.topic(), record.partition(), record.offset());
                }
            }
        };
    }

    private String groupId(EventHandler<?> handler) {
        return properties.getKafka().getGroupPrefix() + "-" + handler.consumerGroup();
    }

    @Override
    public void stop() {
        containers.forEach(container -> {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("停止 Kafka 监听容器失败", e);
            }
        });
        containers.clear();
        retryCounters.clear();
        running = false;
        log.info("Kafka 消费者已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 在 Web 容器之前停止消费。
     * <p>这样应用关闭时不会出现「业务 Bean 已销毁、消费者还在收消息」的状态。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
