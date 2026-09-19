package com.school.forum.forum.convert;

import com.school.forum.forum.entity.Comment;
import com.school.forum.forum.vo.CommentVO;
import com.school.forum.user.api.UserBrief;

import java.util.List;

/**
 * 评论实体到出参的转换。
 * <p>一级评论与二级评论共用 {@link CommentVO}：两者的差异只在
 * {@code floor}/{@code replies}/{@code replyUser} 三个字段有没有值，
 * 拆成两个出参类型反而会让前端的渲染代码分叉。
 */
public final class CommentConverter {

    private CommentConverter() {
    }

    public static CommentVO toVO(Comment comment,
                                 UserBrief user,
                                 UserBrief replyUser,
                                 boolean liked,
                                 long likeCount,
                                 List<CommentVO> replies) {
        return new CommentVO(
                comment.getId(),
                comment.getPostId(),
                value(comment.getFloor()),
                comment.getContent(),
                user,
                replyUser,
                comment.getIsAuthor() != null && comment.getIsAuthor() == 1,
                liked,
                likeCount,
                value(comment.getReplyCount()),
                comment.getCreateTime(),
                replies);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
