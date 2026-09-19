package com.school.forum.forum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发帖请求。
 *
 * <p>长度上限与数据库列宽一致（标题 128、摘要由正文截取）。上限写在这里而不是
 * 让数据库去报错：一条 {@code Data too long for column 'title'} 的 SQL 异常
 * 对用户毫无意义，而校验注解能直接告诉前端「标题最多 128 字」。
 */
@Data
public class PostCreateRequest {

    @NotNull(message = "请选择板块")
    private Long boardId;

    @NotBlank(message = "标题不能为空")
    @Size(min = 5, max = 128, message = "标题需 5-128 字")
    private String title;

    @NotBlank(message = "正文不能为空")
    @Size(min = 5, max = 20000, message = "正文需 5-20000 字")
    private String content;

    /** 标签 ID 列表。最多 5 个——标签是「交叉标注」，多了就失去区分度 */
    @Size(max = 5, message = "最多选择 5 个标签")
    private List<Long> tags;

    /** 正文图片 URL 列表，最多 9 张 */
    @Size(max = 9, message = "最多上传 9 张图片")
    private List<String> images;

    /** 0草稿 1直接发布。默认为发布——绝大多数用户点「发帖」就是想立刻发出去 */
    private Integer status = 1;

    public boolean isDraft() {
        return status != null && status == 0;
    }
}
