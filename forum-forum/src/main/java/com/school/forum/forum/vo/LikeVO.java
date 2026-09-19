package com.school.forum.forum.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 点赞结果出参。
 *
 * <p><b>返回值里带 {@code likeCount}，而不是让前端自己 +1：</b>
 * 点赞走的是「Redis 计数即时生效、DB 异步落库」，服务端返回的计数才是权威值。
 * 让前端自行加减，在网络重试或重复点击时会与实际值越差越远。
 *
 * @param liked     操作后的状态，true 已点赞 / false 未点赞
 * @param likeCount 操作后的点赞总数（读自 Redis 实时计数）
 */
public record LikeVO(int targetType,
                     Long targetId,
                     @JsonProperty("isLiked") boolean liked,
                     long likeCount) {
}
