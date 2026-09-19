package com.school.forum.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 板块，对应 {@code t_board}。
 *
 * <p>板块是帖子的「唯一归属」——一个帖子只能属于一个板块。这与标签的
 * 「交叉标注」是两种不同的组织方式，因此不能互相替代：板块回答「去哪找」，
 * 标签回答「关于什么」。
 */
@Data
@TableName("t_board")
public class Board implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_NORMAL = 0;
    /** 列表中不可见，但可直达 */
    public static final int STATUS_HIDDEN = 1;
    /** 禁止发帖 */
    public static final int STATUS_CLOSED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 板块编码，前端路由与权限判定使用 */
    private String code;

    private String description;

    private String icon;

    private String cover;

    /** 排序权重，值越小越靠前 */
    private Integer sortOrder;

    private Integer postCount;

    private Integer todayCount;

    /** 版主用户 ID，0 表示未指派 */
    private Long ownerId;

    /** 发帖是否需先审核 0否 1是 */
    private Integer postAudit;

    /** 是否默认板块（发帖页预选） */
    private Integer isDefault;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
