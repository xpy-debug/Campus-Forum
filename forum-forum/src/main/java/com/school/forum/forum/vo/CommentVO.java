package com.school.forum.forum.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.school.forum.user.api.UserBrief;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论出参。一级评论与二级评论共用本类型。
 *
 * @param floor     楼层号。二级评论为 0，前端不展示
 * @param replyUser 被回复者。一级评论为 null，二级评论用于渲染「回复 @某某」
 * @param author    是否楼主自评
 * @param replies   二级评论预览。仅一级评论有值，默认只带前 2 条
 */
public record CommentVO(Long id,
                        Long postId,
                        int floor,
                        String content,
                        UserBrief user,
                        UserBrief replyUser,
                        @JsonProperty("isAuthor") boolean author,
                        @JsonProperty("isLiked") boolean liked,
                        long likeCount,
                        long replyCount,
                        LocalDateTime createTime,
                        List<CommentVO> replies) {
}
