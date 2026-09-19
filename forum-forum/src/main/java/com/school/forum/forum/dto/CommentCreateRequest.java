package com.school.forum.forum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发表评论请求。一级评论与回复共用同一个接口，靠 {@code parentId} 区分。
 */
@Data
public class CommentCreateRequest {

    @NotNull(message = "帖子 ID 不能为空")
    private Long postId;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论最多 1000 字")
    private String content;

    /**
     * 0 表示发表一级评论；非 0 表示回复某条评论。
     * <p>用 0 而不是 null 表示「没有父级」，与 {@code t_comment} 的字段约定一致，
     * 少一处判空就少一处空指针。
     */
    private Long parentId = 0L;

    @Size(max = 3, message = "评论最多上传 3 张图片")
    private List<String> images;
}
