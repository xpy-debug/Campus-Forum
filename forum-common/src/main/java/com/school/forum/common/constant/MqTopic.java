package com.school.forum.common.constant;

/**
 * 消息主题 / 路由键常量。
 *
 * <p><b>为什么放在 forum-common 而不是 infrastructure：</b>
 * 生产者（业务模块）和消费者（业务模块）都要引用这些常量，而业务模块不依赖
 * infrastructure 的 MQ 实现。放在公共模块可以让双方只依赖抽象名，不依赖具体产品。
 *
 * <p><b>命名规范：</b>{@code forum.<域>.<动作>}。这个字符串同时被用作：
 *
 * <ul>
 *   <li>Kafka 的 topic 名</li>
 *   <li>RabbitMQ 的 routing key</li>
 * </ul>
 *
 * <p>RabbitMQ 的 exchange 统一为 {@link #EXCHANGE}（topic 类型），因此 routing key 可以
 * 直接复用 Kafka 的 topic 名，切换 MQ 时无需改动任何业务代码。
 *
 * <p><b>不要在注解里直接写字符串字面量</b>——用 {@code @KafkaListener(topics = MqTopic.LIKE)}
 * 这类引用，否则改名时编译器帮不上忙。
 */
public final class MqTopic {

    private MqTopic() {
    }

    /** RabbitMQ 统一 topic exchange 名称。Kafka 侧无对应概念，忽略即可 */
    public static final String EXCHANGE = "forum.exchange";

    /** 死信交换机。TLL + DLX 延迟队列到期后转入此交换机 */
    public static final String DLX_EXCHANGE = "forum.dlx.exchange";

    // ==================== 互动域 ====================

    /** 点赞事件。payload: {@code LikeEvent} */
    public static final String LIKE = "forum.like";

    /** 取消点赞事件。payload: {@code UnlikeEvent} */
    public static final String UNLIKE = "forum.unlike";

    /** 评论事件（通知用）。评论写库是同步的，本主题只负责异步扇出通知 */
    public static final String COMMENT = "forum.comment";

    // ==================== 通知域 ====================

    /** 站内通知投递。统一收敛到这一个主题，由消费者按类型分派 */
    public static final String NOTIFICATION = "forum.notification";

    // ==================== 营销域（秒杀） ====================

    /**
     * 秒杀下单请求。Redis Lua 预扣减成功后投递，由消费者落库生成订单。
     * <p>这是全系统吞吐要求最高的主题，也是 Kafka vs RabbitMQ 压测的主战场。
     */
    public static final String SECKILL_ORDER = "forum.seckill.order";

    /** 秒杀订单超时未支付，需要回滚库存 */
    public static final String SECKILL_TIMEOUT = "forum.seckill.timeout";

    // ==================== 数据同步 ====================

    /** 计数落库（点赞数、评论数等 Redis 计数批量刷回 MySQL） */
    public static final String COUNT_SYNC = "forum.count.sync";

    // ==================== 自检 ====================

    /**
     * 链路自检主题。
     *
     * <p>用途是<b>验证当前生效的 MQ 通路是否真的打通</b>：
     * 往这个主题发一条消息，消费端收到后把「收到时间 + 端到端耗时」写进 Redis，
     * 再用 {@code GET /api/health/mq} 读回来。
     *
     * <p>它比 {@code actuator/health} 更有价值的地方在于：健康检查只能说明
     * 「TCP 连得上 Broker」，而自检能证明「消息确实从生产者走到了消费者」——
     * 主题没建、路由键写错、消费者组配错、权限不足这些问题，
     * 健康检查全是绿的，但消息一条都到不了。
     *
     * <p>做 MQ 压测时这个主题也是最小的验证通路：
     * 先确认链路通，再上压测工具，能省掉大量「到底是压测脚本的问题还是系统的问题」的排查。
     */
    public static final String SELF_TEST = "forum.selftest";

    /**
     * 延迟消息使用的主题前缀。
     *
     * <p>RabbitMQ 侧的延迟通过「TTL 队列 + DLX」实现：消息先投递到
     * {@code forum.delay.<秒数>} 队列，队列设置 {@code x-message-ttl}，过期后
     * 经死信交换机转发回 {@link #EXCHANGE}。Kafka 侧无原生延迟支持，由
     * {@code KafkaDelayScheduler} 按时间轮重新投递。
     */
    public static final String DELAY_PREFIX = "forum.delay.";

    /** 生成延迟队列名，例如 {@code delayQueue(30)} -> {@code forum.delay.30} */
    public static String delayQueue(int delaySeconds) {
        return DELAY_PREFIX + delaySeconds;
    }
}
