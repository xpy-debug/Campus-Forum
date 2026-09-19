package com.school.forum.forum.consumer;

import com.school.forum.common.constant.MqTopic;
import com.school.forum.forum.event.LikeChangedEvent;
import com.school.forum.infrastructure.mq.core.ConsumeResult;
import com.school.forum.infrastructure.mq.core.EventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 取消点赞事件消费入口。事件类型与 {@link LikeEventHandler} 相同，
 * 区别只在订阅的主题——取消点赞走独立的 {@code forum.unlike}，
 * 这样两类事件的积压与消费速率可以分别观测。
 *
 * <p>两个主题意味着<b>它们之间没有顺序保证</b>，用户快速「点赞→取消」时
 * 取消可能先被消费。这一点的兜底不在本类，而在
 * {@link LikeBatchPersister} 的时间戳守卫里。
 */
@Component
@RequiredArgsConstructor
public class UnlikeEventHandler implements EventHandler<LikeChangedEvent> {

    private final LikeBatchWriter writer;

    @Override
    public String topic() {
        return MqTopic.UNLIKE;
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
