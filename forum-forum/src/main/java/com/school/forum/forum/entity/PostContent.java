package com.school.forum.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子正文，对应 {@code t_post_content}。
 *
 * <p><b>主键就是 {@code post_id}</b>，不是自增 ID：1:1 关系下再引入一个自增主键
 * 只会多维护一个索引，而用 post_id 作主键可以让「按帖子查正文」直接命中聚簇索引，
 * 不需要回表。
 *
 * <p>{@code images} 是 MySQL 的 JSON 列，实体里用 String 承接，写入时需保证是合法 JSON
 * （本项目存的是形如 {@code ["https://..."]} 的数组字面量）。不引入自定义 TypeHandler：
 * 当前只有「整体读写」的需求，没有必要为它多一层序列化抽象。
 */
@Data
@TableName("t_post_content")
public class PostContent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 与 t_post.id 一对一，插入时必须显式赋值，不能交给数据库自增 */
    @TableId(type = IdType.INPUT)
    private Long postId;

    /** Markdown 原文 */
    private String content;

    /** 预渲染的 HTML。读多写少，渲染一次存下来，避免每次请求重复解析 */
    private String contentHtml;

    /** 正文图片 URL 数组（JSON） */
    private String images;

    private String videoUrl;

    private Integer wordCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
