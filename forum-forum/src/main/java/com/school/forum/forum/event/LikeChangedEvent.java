package com.school.forum.forum.event;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.infrastructure.mq.core.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.ZoneId;

/**
 * 点赞状态变更事件。点赞与取消点赞共用本事件，靠 {@link #liked} 区分。
 *
 * <p><b>事件描述的是「状态」而不是「增量」。</b>这一点是整条异步链路能收敛的关键：
 * 取消点赞对应的是 {@code DELETE}、点赞对应的是 {@code INSERT IGNORE}，
 * 两者都是幂等的状态操作，重复消费不会叠加副作用。
 * 若事件改成「+1 / -1」这样的增量语义，重复投递就会把计数改错，
 * 而对账也无从判断到底该是几。
 *
 * <p>它还有一个副产品：因为落库用的是「真实影响行数」（INSERT IGNORE 究竟插进去几行、
 * DELETE 究竟删掉几行），即使乱序导致某次操作被跳过，计数增量也始终等于
 * 数据库行的实际变化量，不会与实际数据脱节。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LikeChangedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 点赞者 */
    private Long userId;

    /** 1帖子 2评论 */
    private int targetType;

    private Long targetId;

    /** 目标作者，冗余写入 {@code t_user_like.target_owner_id} */
    private Long targetOwnerId;

    /** true 点赞 / false 取消点赞 */
    private boolean liked;

    /**
     * 分区键取「目标」而不是「用户」。
     *
     * <p>同一个目标的多次点赞变更落到同一个分区、按发生顺序被消费；
     * 若按用户分区，一个用户对同一帖子的「点赞→取消」仍是有序的，但
     * <b>消费端批量聚合是按目标聚合的</b>，按目标分区能让同一批消息更集中，
     * 批量 UPDATE 的合并率更高。
     *
     * <p>反过来若按 eventId 分区，同一目标的事件会散到所有分区，
     * 保序和批量聚合同时失效。
     */
    @Override
    public String routingKey() {
        return RedisKey.targetTypeName(targetType) + ":" + targetId;
    }

    /**
     * 事件发生时刻（毫秒时间戳），消费端用它做乱序守卫。
     *
     * <p>直接由父类的 {@code occurredAt} 换算而来，不再单独存一个字段——
     * 两个表示同一时刻的字段迟早会不一致。
     *
     * <p><b>已知局限：</b>它取自生产者的本地时钟，多实例部署时若各机器时钟有偏差，
     * 「谁更新」的判断就可能出错。当前部署形态是单实例，不存在这个问题；
     * 真要上多实例，应改为在 Redis 里用 {@code INCR} 取一个全局单调序号作为事件时间。
     */
    public long occurredAtMillis() {
        return getOccurredAt() == null
                ? 0L
                : getOccurredAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
