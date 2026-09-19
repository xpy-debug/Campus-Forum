package com.school.forum.forum.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.school.forum.user.api.UserBrief;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情出参。
 *
 * <p>与 {@link PostVO} 分开而不是「详情 = 列表项 + 正文」继承：两者字段差异不小
 * （详情有板块名、正文、字数，列表项没有），继承会让列表接口的返回体里出现一堆
 * 只在详情页有意义的字段，前端反而更难判断哪些字段一定存在。
 */
public record PostDetailVO(Long id,
                           Long boardId,
                           String boardName,
                           String title,
                           String content,
                           String contentHtml,
                           UserBrief author,
                           long likeCount,
                           long commentCount,
                           long viewCount,
                           int wordCount,
                           @JsonProperty("isLiked") boolean liked,
                           List<TagVO> tags,
                           LocalDateTime publishTime) {
}
