package com.school.forum.infrastructure.mq.core;

/**
 * 业务侧唯一的消息消费入口。
 *
 * <p><b>这是整个 MQ 抽象的关键设计：</b>业务模块只实现本接口并注册为 Spring Bean，
 * 完全不出现 {@code @KafkaListener}、{@code @RabbitListener} 或任何产品专属注解。
 * 抽象层在启动时扫描所有 {@code EventHandler} Bean，按 {@link #topic()} 把它们
 * 绑定到当前激活的 MQ 实现上。
 *
 * <p>这样做的收益是：切换 {@code forum.mq.provider} 只需要改一行配置，
 * 业务代码零改动——这正是「用同一套业务逻辑对比 Kafka 与 RabbitMQ 性能」的前提。
 * 若业务代码直接写死 {@code @KafkaListener}，那么两次压测跑的就不是同一份代码，
 * 结论也就不可比。
 *
 * <p><b>实现类必须自己保证幂等。</b>抽象层只承诺 at-least-once，同一条消息
 * （相同 {@code eventId}）可能被投递多次。抽象层提供的 {@code IdempotentGuard}
 * 可以挡住绝大部分重复，但它基于 Redis 且有 TTL，不能作为唯一防线：
 * 真正的幂等应该落在数据库唯一索引上（如 {@code t_user_like} 的
 * {@code (user_id, target_type, target_id)} 唯一键）。
 *
 * @param <T> 事件类型
 */
public interface EventHandler<T extends BaseEvent> {

    /**
     * 订阅的主题 / 路由键。取值来自 {@code MqTopic} 常量。
     * <p>同一主题允许多个 Handler，但语义上是「竞争消费」还是「广播」需自行确认：
     * 本抽象默认按<b>竞争消费</b>实现（同一消费者组内一条消息只被一个实例处理）。
     */
    String topic();

    /**
     * 事件的具体类型，供反序列化使用。
     * <p>之所以要显式声明而不是靠泛型擦除后反射获取，是因为 Kafka 的
     * {@code JsonDeserializer} 需要 {@code Class} 对象才能工作，
     * 而 RabbitMQ 侧虽然能从 {@code __TypeId__} 头拿到类名，但显式声明
     * 更可靠（也避免了信任外部消息头带来的反序列化安全风险）。
     */
    Class<T> eventType();

    /**
     * 消费逻辑。
     * <p><b>实现约束：</b>
     * <ul>
     *   <li>必须是幂等的——重复执行同样的入参不能产生副作用叠加</li>
     *   <li>不要在方法内 swallow 异常后返回 SUCCESS，那会让消息永久丢失</li>
     *   <li>耗时操作要留意消费线程池大小，阻塞过久会触发 rebalance</li>
     * </ul>
     */
    ConsumeResult handle(T event);

    /**
     * 消费者组名。默认取实现类全限定名。
     * <p>Kafka 用它做消费组隔离；RabbitMQ 用它拼队列名，实现「同组竞争、异组广播」。
     */
    default String consumerGroup() {
        return getClass().getSimpleName();
    }
}
