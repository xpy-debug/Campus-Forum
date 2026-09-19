package com.school.forum.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 标签，对应 {@code t_tag}。
 */
@Data
@TableName("t_tag")
public class Tag implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_DISABLED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 被引用次数，定时重算 */
    private Integer useCount;

    /** 官方标签不可被普通用户删除 */
    private Integer isOfficial;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
