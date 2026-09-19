package com.school.forum.forum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 帖子主表，对应 {@code t_post}。
 *
 * <p><b>本表不含正文。</b>正文在 {@link PostContent}，两者是 1:1 的垂直分表。
 * 拆分的收益在列表页：一页 20 条帖子若连正文一起读出来，平均要搬运上百 KB 的数据，
 * 而列表页只需要标题和摘要。单行变短之后，同样的 Buffer Pool 能装下更多行，
 * 分页查询的物理读显著减少。
 *
 * <p>因此<b>列表查询绝不能 JOIN t_post_content</b>——那等于把拆表的意义又还回去了。
 * 需要正文的只有详情页，按主键查一次即可（聚簇索引，零回表）。
 */
@Data
@TableName("t_post")
public class Post implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 状态 ====================

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_AUDITING = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_DELETED = 4;
    public static final int STATUS_OFFLINE = 5;

    // ==================== 类型 ====================

    public static final int TYPE_NORMAL = 0;
    /** 置顶。与公告一起归入「非普通帖」，列表页单独查询后拼接在顶部 */
    public static final int TYPE_PINNED = 1;
    public static final int TYPE_ESSENCE = 2;
    public static final int TYPE_ANNOUNCEMENT = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long boardId;

    private Long userId;

    private String title;

    /** 摘要，发帖时由正文截取生成。查列表只读这一列，不解析 Markdown */
    private String summary;

    /** 封面图 URL，取正文首图 */
    private String coverImage;

    /** 0普通 1置顶 2精华 3公告 */
    private Integer type;

    /** 0草稿 1已发布 2待审核 3审核驳回 4已删除 5已下架 */
    private Integer status;

    private String auditRemark;

    /** 点赞数。Redis 是实时真相，本列由 MQ 消费者异步刷写，是最终真相 */
    private Integer likeCount;

    private Integer commentCount;

    private Integer collectCount;

    /** 浏览数。Redis 计数后由定时任务批量刷回 */
    private Integer viewCount;

    private Integer shareCount;

    /** 热度分 =（赞×2 + 评×3 + 藏×4）/ 时间衰减，定时任务批量重算 */
    private BigDecimal hotScore;

    private LocalDateTime lastCommentTime;

    /** 首次发布时间。草稿转发布时写入，之后不再变更——它是列表的排序键，不能中途改 */
    private LocalDateTime publishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
