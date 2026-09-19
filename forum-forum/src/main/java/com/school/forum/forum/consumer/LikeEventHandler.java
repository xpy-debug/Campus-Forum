package com.school.forum.forum.consumer;

import com.school.forum.common.constant.MqTopic;
import com.school.forum.forum.event.LikeChangedEvent;
import com.school.forum.infrastructure.mq.core.ConsumeResult;
import com.school.forum.infrastructure.mq.core.EventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 点赞事件消费入口。
 *
 * <p><b>它只做一件事：把事件交给攒批器。</b>真正的落库在
 * {@link LikeBatchWriter} 与 {@link LikeBatchPersister} 里。
 * 之所以在这里立刻返回成功（而不是等落库完成再返回），理由见攒批器的类注释——
 * 这是用「极端情况下最多丢 200 条记录」换「写入次数降到 1/200」的取舍。
 *
 * <p><b>本类不需要自己实现幂等。</b>{@code MessageDispatcher} 已经用
 * {@code IdempotentGuard} 挡掉了重复的 eventId，而落到数据库这一层还有
 * {@code uk_user_target} 唯一索引与 {@code INSERT IGNORE} 兜底。
 * 在消费者里再写一遍幂等判断，只会多一次 Redis 往返。
 *
 * <p>与 {@link UnlikeEventHandler} 分别订阅两个主题（点赞 / 取消点赞），
 * 而不是在一个处理器里按字段分派：主题是消费端的路由依据，
 * 两个主题也让「点赞」与「取消点赞」的消费速率、堆积情况可以分别监控。
 */
@Component
@RequiredArgsConstructor
public class LikeEventHandler implements EventHandler<LikeChangedEvent> {

    private final LikeBatchWriter writer;

    @Override
    public String topic() {
        return MqTopic.LIKE;
    }

    @Override
    public Class<LikeChangedEvent> eventType() {
        return LikeChangedEvent.class;
    }

    @Override
    public ConsumeResult handle(LikeChangedEvent event) {
        writer.add(event);
        return ConsumeResult.SUCCESS;
    }
}
