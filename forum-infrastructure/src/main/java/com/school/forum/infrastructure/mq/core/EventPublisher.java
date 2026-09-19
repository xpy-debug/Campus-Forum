package com.school.forum.infrastructure.mq.core;

/**
 * 消息发送入口。业务模块只依赖本接口，不依赖任何 MQ 产品客户端。
 *
 * <p><b>接口刻意设计得很窄</b>——只有「发一条」和「延迟发一条」两个能力，
 * 不暴露分区、offset、消息确认模式等产品专属概念。窄接口换来的
 * 是两家实现都能低成本满足，也避免了业务代码里出现「只有 Kafka 才有」的用法
 * 而导致抽象层被击穿。
 *
 * <p><b>关于返回值：</b>所有方法都是「发出去就算成功」，不等待 Broker 确认。
 * 这是有意为之——本系统的异步链路（点赞、通知、秒杀下单）都不需要同步确认，
 * 强等确认会把 MQ 的 RTT 直接加到接口响应时间上。可靠投递由
 * <b>本地消息表 + 定时补偿</b>保证（见 {@code t_mq_message} 表），
 * 而不是靠同步阻塞。
 */
public interface EventPublisher {

    /**
     * 发送事件。消息键由事件内容推导（见各实现的默认策略）。
     * <p>适合不关心顺序的场景，如站内通知。
     */
    void publish(String topic, BaseEvent event);

    /**
     * 发送事件并指定分区键。
     *
     * <p><b>按键保序是唯一能保证顺序的手段：</b>Kafka 保证同一分区内有序，
     * RabbitMQ 保证同一队列内有序（单消费者时）。把业务主键作为 key，
     * 可以保证「同一个帖子被点赞/取消点赞」这类事件按发生顺序被消费。
     * 不指定 key 时，Kafka 会轮询分区、RabbitMQ 会轮询消费者，
     * 同一业务对象的两个事件完全可能被并发处理而乱序。
     *
     * @param routingKey 业务主键，如 {@code "post:123"}。注意不要用 eventId 当 key，
     *                   那样等于放弃保序
     */
    void publish(String topic, String routingKey, BaseEvent event);

    /**
     * 发送延迟事件。
     *
     * <p><b>这是两个产品差异最大的一项能力，也是选型对比的重要维度：</b>
     * <ul>
     *   <li>RabbitMQ：TTL 队列 + 死信交换机，原生支持，消息持久化在 Broker 上</li>
     *   <li>Kafka：无原生支持，需要借助外部调度器或时间轮重新投递</li>
     * </ul>
     *
     * @param delaySeconds 延迟秒数。抽象层只承诺「不早于该时间投递」，
     *                     实际投递时刻可能有秒级误差
     */
    void publishDelay(String topic, String routingKey, BaseEvent event, int delaySeconds);

    /**
     * 发送一条<b>已经序列化好</b>的消息，不做任何转换。
     *
     * <p><b>谁需要它：</b>{@code MqOutboxDispatcher}。本地消息表里存的就是 JSON 文本，
     * 补发时若再「反序列化成对象、又序列化回字符串」，既白白消耗 CPU，
     * 又引入了一个风险：两次序列化之间如果事件类结构发生了变化，
     * 补发出去的内容就和当初写进表里的不一致了。直接透传原文最安全。
     *
     * <p>业务代码不应调用本方法——它绕过了 {@code BaseEvent} 的结构约定，
     * 只适合「消息已经是最终形态」的补发场景。
     */
    void publishRaw(String topic, String routingKey, String payload);

    /**
     * 当前生效的 MQ 产品标识，取值 {@code kafka} 或 {@code rabbit}。
     * <p>供监控埋点与压测结果打标使用——写入 {@code t_mq_consume_log.provider}，
     * 这样两种实现的数据可以落在同一张表里直接对比。
     */
    String provider();
}
