package com.school.forum.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论，对应 {@code t_comment}。
 *
 * <p><b>只有两级，但用 {@code root_id} 而不是树。</b>「查询某条一级评论下的全部回复」
 * 只需 {@code WHERE root_id = ?}，一次索引扫描；无限级树形结构则要递归查询或
 * 维护路径枚举。<b>查询永远只用 {@code root_id}</b>，{@code parent_id} 只用来渲染
 * 「回复 @某某」——这个分工是理解本表的关键。
 */
@Data
@TableName("t_comment")
public class Comment implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int LEVEL_ROOT = 1;
    public static final int LEVEL_REPLY = 2;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_FOLDED = 2;
    public static final int STATUS_DELETED = 3;

    /** 一级评论的两个自引用字段固定为 0，语义是「没有父级」，不用 NULL 是为了免去判空 */
    public static final long NO_PARENT = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long userId;

    /** 一级评论为 0，二级评论为其所属一级评论 ID */
    private Long rootId;

    /** 直接父评论 ID，一级评论为 0。仅用于渲染「回复 @某某」 */
    private Long parentId;

    /** 被回复的用户 ID，一级评论为 0 */
    private Long replyUserId;

    /** 1一级 2二级 */
    private Integer level;

    private String content;

    /** 图片 URL 数组（JSON） */
    private String images;

    /** 楼层号，仅一级评论有值，由 Redis INCR 生成 */
    private Integer floor;

    private Integer likeCount;

    /** 子回复数，仅一级评论维护 */
    private Integer replyCount;

    /** 是否楼主自评 0否 1是 */
    private Integer isAuthor;

    private String ipLocation;

    /** 0待审核 1正常 2已折叠 3已删除 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
