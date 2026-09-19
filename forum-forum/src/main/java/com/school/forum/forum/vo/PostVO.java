package com.school.forum.forum.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.school.forum.user.api.UserBrief;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子列表项出参。
 *
 * <p><b>不含正文</b>——列表页只展示摘要。正文可达两万字，一页 20 条就是几十万字符，
 * 而它们在列表页一个都用不上。
 *
 * <p>{@code isLiked} 用 {@link JsonProperty} 显式命名：record 的属性名推导规则
 * 与 JavaBean 不同（会照搬组件名），显式标注可以让接口字段名不受
 * 序列化框架版本变化的影响，前端也不必跟着改。
 *
 * @param liked 当前登录用户是否已点赞。未登录时恒为 false
 */
public record PostVO(Long id,
                     Long boardId,
                     String title,
                     String summary,
                     String coverImage,
                     int type,
                     UserBrief author,
                     long likeCount,
                     long commentCount,
                     long viewCount,
                     @JsonProperty("isLiked") boolean liked,
                     List<TagVO> tags,
                     LocalDateTime publishTime) {
}
