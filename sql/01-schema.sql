-- =====================================================================================
-- 校园论坛系统 —— 数据库表结构 DDL
-- =====================================================================================
-- 版本      : V1.0.0
-- 数据库    : MySQL 8.0+（依赖 ngram 全文索引、降序索引、JSON 类型、CHECK 约束）
-- 字符集    : utf8mb4 / utf8mb4_0900_ai_ci
-- 存储引擎  : InnoDB
--
-- 【全局设计规范】
-- 1. 主键统一 BIGINT UNSIGNED AUTO_INCREMENT，由应用层 MyBatis-Plus ASSIGN_ID(雪花) 或
--    DB 自增生成；对外暴露的业务号（订单号/券码）单独建列并加唯一索引。
-- 2. 不使用统一的 deleted 逻辑删除列，各表以 status 字段承担状态语义。
--    理由：a) 业务状态本就多样（草稿/待审/驳回/下架），单一 deleted 无法表达；
--          b) 逻辑删除列会污染所有复合索引，导致 status/create_time 等索引选择性下降。
--    删除语义在各表中由约定的 status 值表达，见每表注释。
-- 3. 所有时间列使用 DATETIME（不使用 TIMESTAMP，避免 2038 问题与时区隐式转换）。
-- 4. 所有金额列使用 DECIMAL(10,2)，禁止 FLOAT/DOUBLE。
-- 5. 所有计数字段（点赞数/评论数等）均为冗余字段，属于"最终一致"数据，
--    实时真值以 Redis 为准，DB 值由异步消费者刷写 + 定时对账任务修正。
-- 6. 索引命名：uk_ 唯一索引 / idx_ 普通索引 / ft_ 全文索引。
--
-- 【表清单】
--   用户域 : t_user, t_user_follow
--   论坛域 : t_board, t_post, t_post_content, t_comment, t_user_like,
--            t_user_collect, t_tag, t_post_tag
--   通知域 : t_notification
--   营销域 : t_coupon, t_seckill_activity, t_user_coupon, t_seckill_order
--   积分域 : t_user_points, t_points_record, t_user_signin,
--            t_mall_goods, t_mall_order
--   基础域 : t_mq_message, t_mq_consume_log, t_mq_benchmark_result
-- =====================================================================================

DROP DATABASE IF EXISTS `school_forum`;
CREATE DATABASE `school_forum`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `school_forum`;


-- =====================================================================================
-- 一、用户域
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- t_user 用户表
-- 状态语义：status 0正常 1禁言 2封禁 3注销（注销后 username 仍被占用，防止冒名注册）
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_user`
(
    `id`              BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`        VARCHAR(50)       NOT NULL COMMENT '登录用户名，全局唯一',
    `password`        VARCHAR(100)      NOT NULL COMMENT 'BCrypt 加密后的密码（含盐，固定60字符）',
    `nickname`        VARCHAR(50)       NOT NULL COMMENT '昵称，展示用，可重复',
    `avatar`          VARCHAR(512)      NOT NULL DEFAULT '' COMMENT '头像 URL',
    `email`           VARCHAR(128)               DEFAULT NULL COMMENT '邮箱',
    `phone`           VARCHAR(20)                DEFAULT NULL COMMENT '手机号',
    `student_no`      VARCHAR(32)                DEFAULT NULL COMMENT '学号，校内实名字段',
    `college`         VARCHAR(64)       NOT NULL DEFAULT '' COMMENT '学院',
    `major`           VARCHAR(64)       NOT NULL DEFAULT '' COMMENT '专业',
    `grade`           SMALLINT UNSIGNED          DEFAULT NULL COMMENT '入学年份，如 2023',
    `gender`          TINYINT           NOT NULL DEFAULT 0 COMMENT '性别 0未知 1男 2女',
    `bio`             VARCHAR(255)      NOT NULL DEFAULT '' COMMENT '个性签名',
    `role`            TINYINT           NOT NULL DEFAULT 0 COMMENT '角色 0普通用户 1版主 2管理员',
    `status`          TINYINT           NOT NULL DEFAULT 0 COMMENT '状态 0正常 1禁言 2封禁 3注销',
    `level`           SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '等级，由 exp 换算',
    `exp`             INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '经验值，发帖/评论/被点赞累加',
    `post_count`      INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '发帖数（冗余）',
    `follower_count`  INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '粉丝数（冗余）',
    `following_count` INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '关注数（冗余）',
    `last_login_time` DATETIME                   DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip`   VARCHAR(45)       NOT NULL DEFAULT '' COMMENT '最后登录IP（45位兼容IPv6）',
    `create_time`     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time`     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_student_no` (`student_no`),
    KEY `idx_nickname` (`nickname`),
    KEY `idx_email` (`email`),
    KEY `idx_phone` (`phone`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户表';


-- -------------------------------------------------------------------------------------
-- t_user_follow 用户关注关系表
-- 说明：无 status 列，取关即物理删除（保证唯一索引可用），关注行为不保留历史。
-- 支撑"关注流 Feed"：推拉结合模式下，拉模式按此表查询关注列表。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_user_follow`
(
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '关注者（粉丝）用户ID',
    `follow_user_id` BIGINT UNSIGNED NOT NULL COMMENT '被关注者用户ID',
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_follow` (`user_id`, `follow_user_id`),
    KEY `idx_follow_user` (`follow_user_id`, `create_time` DESC) COMMENT '查某人的粉丝列表'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户关注关系表';


-- =====================================================================================
-- 二、论坛域
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- t_board 板块表
-- 状态语义：status 0正常 1隐藏（列表中不可见但可直达） 2关闭（禁止发帖）
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_board`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '板块ID',
    `name`        VARCHAR(32)     NOT NULL COMMENT '板块名称',
    `code`        VARCHAR(32)     NOT NULL COMMENT '板块编码，用于前端路由与权限判定',
    `description` VARCHAR(255)    NOT NULL DEFAULT '' COMMENT '板块简介',
    `icon`        VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '板块图标 URL',
    `cover`       VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '板块封面 URL',
    `sort_order`  INT             NOT NULL DEFAULT 0 COMMENT '排序权重，值越小越靠前',
    `post_count`  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '帖子总数（冗余，小时级刷新）',
    `today_count` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '今日发帖数（冗余，Redis 实时 + 定时落库）',
    `owner_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '版主用户ID，0 表示暂未指派',
    `post_audit`  TINYINT         NOT NULL DEFAULT 0 COMMENT '发帖是否需审核 0否 1是',
    `is_default`  TINYINT         NOT NULL DEFAULT 0 COMMENT '是否默认板块（发帖页预选）',
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '状态 0正常 1隐藏 2关闭',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_status_sort` (`status`, `sort_order`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='论坛板块表';


-- -------------------------------------------------------------------------------------
-- t_post 帖子主表
-- 【垂直分表】正文大字段拆到 t_post_content，本表只存列表页所需字段，
--   保证列表分页查询可以完全走索引、且单行长度小、页内行数多、Buffer Pool 命中率高。
-- 状态语义：status 0草稿 1已发布 2待审核 3审核驳回 4已删除 5已下架
-- 类型语义：type   0普通 1置顶 2精华 3公告
--
-- 索引设计说明：
--   idx_board_list    板块内列表页（按置顶+发布时间倒序），覆盖最核心的浏览路径
--   idx_board_hot     板块内热度榜（hot_score 由定时任务批量刷新，非实时写入，避免索引频繁重排）
--   idx_user_list     个人主页「我的帖子」
--   idx_last_comment  板块内「最新回复」排序
--   ft_title_summary  标题+摘要中文全文检索（ngram 分词器，需 ngram_token_size 配置）
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_post`
(
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
    `board_id`          BIGINT UNSIGNED NOT NULL COMMENT '所属板块ID',
    `user_id`           BIGINT UNSIGNED NOT NULL COMMENT '作者用户ID',
    `title`             VARCHAR(128)    NOT NULL COMMENT '标题',
    `summary`           VARCHAR(255)    NOT NULL DEFAULT '' COMMENT '摘要，发帖时截取正文前 120 字',
    `cover_image`       VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '封面图 URL（取正文首图）',
    `type`              TINYINT         NOT NULL DEFAULT 0 COMMENT '类型 0普通 1置顶 2精华 3公告',
    `status`            TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0草稿 1已发布 2待审核 3审核驳回 4已删除 5已下架',
    `audit_remark`      VARCHAR(255)    NOT NULL DEFAULT '' COMMENT '审核意见（驳回时填写）',
    `like_count`        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数（冗余，最终一致）',
    `comment_count`     INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数（冗余，最终一致）',
    `collect_count`     INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '收藏数（冗余，最终一致）',
    `view_count`        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '浏览数（冗余，Redis 计数后定时批量刷回）',
    `share_count`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '分享数（冗余）',
    `hot_score`         DECIMAL(12, 4)  NOT NULL DEFAULT 0 COMMENT '热度分=(赞*2+评*3+藏*4)/时间衰减，定时任务批量重算',
    `last_comment_time` DATETIME                 DEFAULT NULL COMMENT '最后评论时间，用于「最新回复」排序',
    `publish_time`      DATETIME                 DEFAULT NULL COMMENT '首次发布时间（草稿转发布时写入，不再变更）',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 【游标分页索引规范】每个用于列表分页的索引末尾都显式追加了 `id` DESC。
    --   原因：游标分页的排序键形如 `ORDER BY publish_time DESC, id DESC`（id 用作
    --   同一时间戳下的决胜列）。InnoDB 二级索引虽隐式包含主键，但方向固定为 ASC，
    --   无法满足 `id DESC` 的排序要求，会导致 Using filesort。
    --   实测：不追加 id 时 `(publish_time,id) < (?,?)` 的游标查询走 filesort；
    --         追加 id DESC 后 filesort 消失（详见《04-数据库设计》1.5 节）。
    --   注意：若排序列全部为 ASC（如 t_comment 的 floor），隐式主键已满足，无需追加。
    KEY `idx_board_list` (`board_id`, `status`, `type`, `publish_time` DESC, `id` DESC)
        COMMENT '板块列表页（游标分页）。type 用等值过滤 type=0，置顶帖单独查',
    KEY `idx_board_hot` (`board_id`, `status`, `type`, `hot_score` DESC, `id` DESC)
        COMMENT '板块热度榜（游标分页）',
    KEY `idx_user_list` (`user_id`, `status`, `publish_time` DESC, `id` DESC)
        COMMENT '个人主页「我的帖子」（游标分页）',
    KEY `idx_last_comment` (`board_id`, `status`, `type`, `last_comment_time` DESC, `id` DESC)
        COMMENT '「最新回复」排序（游标分页）',
    KEY `idx_publish_time` (`status`, `publish_time` DESC, `id` DESC)
        COMMENT '全站最新帖（游标分页）',
    KEY `idx_pinned` (`board_id`, `status`, `type`) COMMENT '置顶/精华/公告帖，结果集极小，缓存即可',
    FULLTEXT KEY `ft_title_summary` (`title`, `summary`) WITH PARSER ngram
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子主表';


-- -------------------------------------------------------------------------------------
-- t_post_content 帖子正文表
-- 与 t_post 是 1:1 关系，以 post_id 作为主键（天然唯一约束 + 聚簇索引，按帖子ID查正文零回表）。
-- 拆表动机：MEDIUMTEXT 平均数 KB，若与主表同存会显著拉长单行长度，
--          导致列表分页时单页跨越更多数据页、Buffer Pool 有效容量下降。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_post_content`
(
    `post_id`      BIGINT UNSIGNED NOT NULL COMMENT '帖子ID，与 t_post.id 一对一',
    `content`      MEDIUMTEXT      NOT NULL COMMENT '正文原文（Markdown 格式）',
    `content_html` MEDIUMTEXT COMMENT '预渲染的 HTML，读多写少场景下避免每次请求重复渲染',
    `images`       JSON                     DEFAULT NULL COMMENT '正文图片 URL 数组，如 ["https://...","https://..."]',
    `video_url`    VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '视频 URL（可选，单帖最多一个）',
    `word_count`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '正文字数，用于展示阅读时长',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`post_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子正文表（垂直分表）';


-- -------------------------------------------------------------------------------------
-- t_comment 评论表（两级评论模型）
-- 【模型说明】仅两级：一级评论（level=1, root_id=0, parent_id=0）+ 二级评论（level=2）。
--   二级评论的 root_id 指向所属一级评论，parent_id 指向被回复的那条评论。
--   「查询某条一级评论下的全部回复」只需 WHERE root_id = ? ORDER BY create_time，
--   无需递归查询，这是优于无限级树形结构的关键点。
-- 状态语义：status 0待审核 1正常 2已折叠 3已删除
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_comment`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `post_id`       BIGINT UNSIGNED NOT NULL COMMENT '所属帖子ID',
    `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '评论者用户ID',
    `root_id`       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '根评论ID：一级评论为0，二级评论为其所属一级评论ID',
    `parent_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '直接父评论ID：一级评论为0',
    `reply_user_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '被回复的用户ID（用于渲染「回复 @某某」），一级评论为0',
    `level`         TINYINT         NOT NULL DEFAULT 1 COMMENT '层级 1一级评论 2二级评论',
    `content`       VARCHAR(1000)   NOT NULL COMMENT '评论内容',
    `images`        JSON                     DEFAULT NULL COMMENT '评论图片 URL 数组',
    `floor`         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '楼层号，仅一级评论有值，由 Redis INCR 生成',
    `like_count`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数（冗余）',
    `reply_count`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '子回复数（冗余，仅一级评论维护）',
    `is_author`     TINYINT         NOT NULL DEFAULT 0 COMMENT '是否楼主 0否 1是（发帖人自评标识）',
    `ip_location`   VARCHAR(64)     NOT NULL DEFAULT '' COMMENT 'IP 归属地，如「广东·深圳」',
    `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0待审核 1正常 2已折叠 3已删除',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_post_l1` (`post_id`, `status`, `level`, `floor`) COMMENT '帖子内一级评论按楼层分页',
    KEY `idx_root_reply` (`root_id`, `status`, `create_time`) COMMENT '一级评论下的二级回复列表',
    KEY `idx_user_list` (`user_id`, `status`, `create_time` DESC) COMMENT '「我的评论」',
    KEY `idx_parent` (`parent_id`) COMMENT '按父评论聚合',
    KEY `idx_create_time` (`create_time`) COMMENT '审核/归档扫描'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='评论表（两级模型）';


-- -------------------------------------------------------------------------------------
-- t_user_like 点赞记录表（统一点赞表）
-- 【设计取舍】帖子点赞与评论点赞共用一张表，用 target_type 区分。
--   选统一表而非 t_post_like / t_comment_like 两张表的原因：
--     a) 点赞逻辑（Redis 判重 → 发 MQ → 异步落库 → 更新计数）可完全复用一套代码；
--     b) 新增可点赞实体（如「回答」「动态」）无需新建表；
--     c) Redisson/Redis 侧的点赞状态结构与落库结构一一对应，压测对比实验只需改一处。
--   代价：单表增长最快，且热点集中。演进方案见《04-数据库设计》。
-- 【幂等保证】uk_user_target 是点赞幂等的最终兜底，即便 MQ 重复投递也不会产生脏数据。
-- 【为什么没有 status 列】取消点赞即物理 DELETE，这样唯一索引才能继续生效，
--   使得「取消后再点赞」可被正确识别为一次新的点赞。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_user_like`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '点赞者用户ID',
    `target_type`     TINYINT         NOT NULL COMMENT '目标类型 1帖子 2评论',
    `target_id`       BIGINT UNSIGNED NOT NULL COMMENT '目标ID',
    `target_owner_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '目标作者ID，冗余以便直接查「我收到的赞」',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_target` (`user_id`, `target_type`, `target_id`) COMMENT '幂等兜底 + 判重',
    KEY `idx_target` (`target_type`, `target_id`, `create_time` DESC) COMMENT '某目标的点赞明细',
    KEY `idx_owner` (`target_owner_id`, `create_time` DESC) COMMENT '「我收到的赞」时间线'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='点赞记录表（帖子/评论统一）';


-- -------------------------------------------------------------------------------------
-- t_user_collect 收藏记录表
-- 说明：不设 folder_id 外键约束，收藏夹为后续迭代功能，此处预留字段避免二次改表。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_user_collect`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `post_id`     BIGINT UNSIGNED NOT NULL COMMENT '帖子ID',
    `folder_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '收藏夹ID，0 表示默认收藏夹',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_post` (`user_id`, `post_id`),
    KEY `idx_post` (`post_id`),
    KEY `idx_user_list` (`user_id`, `folder_id`, `create_time` DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子收藏表';


-- -------------------------------------------------------------------------------------
-- t_tag 标签表
-- 与板块的区别：板块是「唯一归属」（一个帖子只属于一个板块），标签是「交叉标注」（可有多个）。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_tag`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '标签ID',
    `name`        VARCHAR(32)     NOT NULL COMMENT '标签名',
    `use_count`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '被引用次数（冗余，定时重算）',
    `is_official` TINYINT         NOT NULL DEFAULT 0 COMMENT '是否官方标签（官方标签不可被普通用户删除）',
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '状态 0正常 1禁用（禁用后不再展示，但历史引用保留）',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_status_use` (`status`, `use_count` DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='标签表';


-- -------------------------------------------------------------------------------------
-- t_post_tag 帖子-标签关联表
-- 删除标签关联采用物理删除（同 t_user_like），保证唯一索引有效。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_post_tag`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `post_id`     BIGINT UNSIGNED NOT NULL COMMENT '帖子ID',
    `tag_id`      BIGINT UNSIGNED NOT NULL COMMENT '标签ID',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_tag` (`post_id`, `tag_id`),
    KEY `idx_tag_post` (`tag_id`, `post_id` DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子标签关联表';


-- =====================================================================================
-- 三、通知域
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- t_notification 消息通知表
-- 【聚合策略】点赞类通知不逐条落库：消费端先在 Redis 中以
--   notif:agg:{receiverId}:{type}:{bizId} 聚合，窗口（默认 5 分钟）结束后
--   合并为一条「张三等 5 人赞了你的帖子」写库，避免大 V 用户通知表被点赞刷爆。
-- 类型语义：type 1点赞 2评论 3回复 4关注 5系统公告 6审核结果 7秒杀提醒
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_notification`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    `receiver_id` BIGINT UNSIGNED NOT NULL COMMENT '接收者用户ID',
    `sender_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '触发者用户ID，0 表示系统通知',
    `type`        TINYINT         NOT NULL COMMENT '类型 1点赞 2评论 3回复 4关注 5系统公告 6审核结果 7秒杀提醒',
    `biz_type`    TINYINT         NOT NULL DEFAULT 0 COMMENT '关联业务对象类型 0无 1帖子 2评论 3用户',
    `biz_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '关联业务对象ID',
    `title`       VARCHAR(128)    NOT NULL DEFAULT '' COMMENT '通知标题',
    `content`     VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '通知正文摘要',
    `extra`       JSON                     DEFAULT NULL COMMENT '扩展数据，如聚合人数、跳转参数',
    `is_read`     TINYINT         NOT NULL DEFAULT 0 COMMENT '是否已读 0未读 1已读',
    `read_time`   DATETIME                 DEFAULT NULL COMMENT '阅读时间',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_receiver_unread` (`receiver_id`, `is_read`, `create_time` DESC) COMMENT '未读列表 + 未读数统计',
    KEY `idx_receiver_type` (`receiver_id`, `type`, `create_time` DESC) COMMENT '按类型筛选'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='消息通知表';


-- =====================================================================================
-- 四、营销域（优惠券秒杀）
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- t_coupon 优惠券模板表
-- 【设计说明】模板与活动分离：一张券模板可被多个秒杀场次复用（如「满30减10」既在
--   午间场又在晚间场发放）。库存不在模板上，而在 t_seckill_activity 上，
--   因为库存属于「某一场次」而非「券本身」。
-- 类型语义：type 1满减 2折扣 3无门槛
-- 有效期语义：valid_type 1固定区间（用 valid_start_time/valid_end_time）
--                        2领取后N天（用 valid_days，领取时按 receive_time + N 天写死到用户券）
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_coupon`
(
    `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '优惠券模板ID',
    `name`                VARCHAR(64)     NOT NULL COMMENT '优惠券名称',
    `type`                TINYINT         NOT NULL COMMENT '类型 1满减 2折扣 3无门槛',
    `discount_amount`     DECIMAL(10, 2)  NOT NULL DEFAULT 0 COMMENT '减免金额（type=1/3 时生效）',
    `discount_rate`       DECIMAL(4, 2)   NOT NULL DEFAULT 10.00 COMMENT '折扣率（type=2 时生效），如 8.50 表示 8.5 折',
    `max_discount_amount` DECIMAL(10, 2)  NOT NULL DEFAULT 0 COMMENT '折扣券最高减免金额，0 表示不限',
    `threshold_amount`    DECIMAL(10, 2)  NOT NULL DEFAULT 0 COMMENT '使用门槛金额，0 表示无门槛',
    `scope`               TINYINT         NOT NULL DEFAULT 0 COMMENT '适用范围 0全场 1指定范围',
    `scope_ids`           JSON                     DEFAULT NULL COMMENT '适用对象ID数组（scope=1 时生效）',
    `valid_type`          TINYINT         NOT NULL DEFAULT 1 COMMENT '有效期类型 1固定区间 2领取后N天',
    `valid_start_time`    DATETIME                 DEFAULT NULL COMMENT '固定有效期开始时间',
    `valid_end_time`      DATETIME                 DEFAULT NULL COMMENT '固定有效期结束时间',
    `valid_days`          INT             NOT NULL DEFAULT 0 COMMENT '领取后有效天数（valid_type=2 时生效）',
    `description`         VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '券面描述（展示给用户）',
    `rule_desc`           VARCHAR(1024)   NOT NULL DEFAULT '' COMMENT '使用规则说明',
    `status`              TINYINT         NOT NULL DEFAULT 0 COMMENT '状态 0草稿 1已上架 2已下架',
    `create_time`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_time` (`status`, `valid_end_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='优惠券模板表';


-- -------------------------------------------------------------------------------------
-- t_seckill_activity 秒杀活动表
-- 【库存三层模型 —— 本项目最核心的设计之一】
--   第一层 Redis  ：forum:seckill:stock:{id} —— 抗洪峰的唯一入口，Lua 脚本原子预扣
--   第二层 available_stock（本表）：DB 最终真相，由下单消费者扣减，定时任务与 Redis 对账
--   第三层 locked_stock：已下单未支付，超时未支付时回补到 available_stock
--   恒等式：total_stock = available_stock + locked_stock + sold_count
--   对账口径：Redis 剩余库存 == available_stock（locked 的库存已被 Redis 扣过，不计入）
--   version 列作乐观锁，用于后台人工调库存等低频写场景，不参与秒杀主链路。
-- 【本项目的秒杀形态是积分秒杀】卖的是积分商城的商品（goods_id），支付用积分（points_cost）。
--   商品资料在创建活动时快照进来，热路径不跨模块查库；商品改名不该改写历史活动。
-- 状态语义：status 0未开始 1进行中 2已结束 3已下线
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_seckill_activity`
(
    `id`              BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '活动ID',
    `coupon_id`       BIGINT UNSIGNED            DEFAULT NULL COMMENT '关联优惠券模板ID。积分秒杀卖商品不发券，故可空',
    `goods_id`        BIGINT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '关联商城商品ID（t_mall_goods）',
    `goods_name`      VARCHAR(64)       NOT NULL DEFAULT '' COMMENT '商品名快照。抢购是热路径，不在请求里跨模块查库',
    `goods_cover`     VARCHAR(512)      NOT NULL DEFAULT '' COMMENT '商品封面快照',
    `goods_type`      TINYINT           NOT NULL DEFAULT 1 COMMENT '商品类型快照 1优惠券 2实物 3虚拟物品',
    `points_cost`     INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '秒杀价（积分）。与商品的 points_price 分开：打折多少由活动决定，不改商品本身',
    `name`            VARCHAR(64)       NOT NULL COMMENT '活动名称，如「午间秒杀·星巴克券」',
    `total_stock`     INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '活动总库存（不变量，恒等式基准）',
    `available_stock` INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '可售库存',
    `locked_stock`    INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '锁定库存（已下单未支付）',
    `sold_count`      INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '已售数量（已支付）',
    `per_user_limit`  TINYINT UNSIGNED  NOT NULL DEFAULT 1 COMMENT '每人限购数量。注意：>1 时需调整订单表唯一索引，见《04-数据库设计》',
    `start_time`      DATETIME          NOT NULL COMMENT '活动开始时间',
    `end_time`        DATETIME          NOT NULL COMMENT '活动结束时间',
    `pay_timeout_sec` INT UNSIGNED      NOT NULL DEFAULT 900 COMMENT '支付超时秒数，默认 900 秒（15 分钟）',
    `warmup_minutes`  INT UNSIGNED      NOT NULL DEFAULT 10 COMMENT '开始前预热分钟数，提前将库存加载进 Redis',
    `status`          TINYINT           NOT NULL DEFAULT 0 COMMENT '状态 0未开始 1进行中 2已结束 3已下线',
    `version`         INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（后台低频写场景）',
    `create_time`     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_start` (`status`, `start_time`),
    KEY `idx_coupon` (`coupon_id`),
    KEY `idx_time_range` (`start_time`, `end_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='秒杀活动表';


-- -------------------------------------------------------------------------------------
-- t_user_coupon 用户优惠券表（券实例）
-- 【券码】coupon_code 是对外可核销的业务号，与自增 id 解耦，避免暴露发券量。
-- 【过期处理】不做定时任务批量 UPDATE（大表全表扫描），而是：
--   查询时惰性判定（WHERE status=0 AND expire_time > NOW()） + 定时任务分批扫
--   idx_status_expire 索引慢速修正状态，两者结合保证既不误用也不积压。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_user_coupon`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `coupon_code` VARCHAR(32)     NOT NULL COMMENT '券码，对外核销用，全局唯一',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '持有用户ID',
    `coupon_id`   BIGINT UNSIGNED NOT NULL COMMENT '优惠券模板ID',
    `activity_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '来源秒杀活动ID，0 表示非秒杀渠道发放',
    `order_id`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '关联秒杀订单ID',
    `source`      TINYINT         NOT NULL DEFAULT 0 COMMENT '来源 0系统发放 1秒杀 2活动兑换 3新人礼包',
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '状态 0未使用 1已使用 2已过期 3已作废',
    `receive_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    `expire_time` DATETIME        NOT NULL COMMENT '过期时间（领取时根据模板规则算好写死，避免查询时联表计算）',
    `use_time`    DATETIME                 DEFAULT NULL COMMENT '使用时间',
    `use_biz_id`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '使用时的业务单号',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coupon_code` (`coupon_code`),
    KEY `idx_user_status_expire` (`user_id`, `status`, `expire_time`) COMMENT '「我的优惠券」按状态筛选',
    KEY `idx_activity_user` (`activity_id`, `user_id`) COMMENT '限购校验与对账',
    KEY `idx_status_expire` (`status`, `expire_time`) COMMENT '过期券定时扫描'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户优惠券表';


-- -------------------------------------------------------------------------------------
-- t_seckill_order 秒杀订单表
-- 【防超卖的最后一道防线】uk_user_activity 唯一索引。
--   即使 Redis 预扣、MQ 消费全部出现异常重复，DB 层也绝不会产生同一用户的重复订单，
--   消费端捕获 DuplicateKeyException 后按「已下单」处理即可，天然幂等。
-- 【重要约束】该唯一索引成立的前提是 per_user_limit = 1。
--   若业务需要允许一人多单，必须改为普通索引 KEY idx_user_activity，
--   并把防重职责上移到 Redis（现在 Lua 已是 HINCRBY 计数，故只需改索引）。
--   本项目设定校园场景每人限领 1 件，故保留唯一索引。
-- 【取消不删 Redis 标记】每人限购 1 件是「活动期内的一次性资格」，不是「同时最多持有 1 件」。
--   取消订单只回补库存，不退还资格；本表的唯一索引保证取消后也无法再抢第二单。
-- 【订单号】order_no = S + yyyyMMdd + 6 位日内序号，在抢购的 Lua 脚本里 INCR 生成，
--   与自增主键解耦，不对外暴露抢购总量。
-- 状态语义：status 0待支付 1已支付(待发放) 2已取消 3超时关闭 4已退款 5已完成
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_seckill_order`
(
    `id`             BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no`       VARCHAR(32)       NOT NULL COMMENT '业务订单号 S+yyyyMMdd+6位序号，抢购时由 Lua 生成',
    `user_id`        BIGINT UNSIGNED   NOT NULL COMMENT '下单用户ID',
    `activity_id`    BIGINT UNSIGNED   NOT NULL COMMENT '秒杀活动ID',
    `goods_id`       BIGINT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '商品ID',
    `goods_name`     VARCHAR(64)       NOT NULL DEFAULT '' COMMENT '商品名快照。订单是历史凭证，商品改名后仍显示当时的样子',
    `goods_type`     TINYINT           NOT NULL DEFAULT 1 COMMENT '商品类型快照 1优惠券 2实物 3虚拟物品，决定发放方式',
    `points_cost`    INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '本单消耗积分（积分秒杀用积分支付，不走 pay_amount）',
    `coupon_id`      BIGINT UNSIGNED            DEFAULT NULL COMMENT '优惠券模板ID（冗余，避免查订单时联表）。积分秒杀为 NULL',
    `user_coupon_id` BIGINT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '支付成功后生成的用户券ID',
    `quantity`       SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '购买数量',
    `order_amount`   DECIMAL(10, 2)    NOT NULL DEFAULT 0 COMMENT '订单金额',
    `pay_amount`     DECIMAL(10, 2)    NOT NULL DEFAULT 0 COMMENT '实付金额',
    `status`         TINYINT           NOT NULL DEFAULT 0 COMMENT '状态 0待支付 1已支付(待发放) 2已取消 3超时关闭 4已退款 5已完成',
    `pay_type`       TINYINT           NOT NULL DEFAULT 0 COMMENT '支付方式 0未支付 1积分（积分秒杀用积分支付）',
    `expire_time`    DATETIME          NOT NULL COMMENT '支付截止时间 = create_time + activity.pay_timeout_sec',
    `pay_time`       DATETIME                   DEFAULT NULL COMMENT '支付时间',
    `finish_time`    DATETIME                   DEFAULT NULL COMMENT '发放完成时间（管理员标记已发放时写入）',
    `close_time`     DATETIME                   DEFAULT NULL COMMENT '关闭时间（取消或超时）',
    `client_ip`      VARCHAR(45)       NOT NULL DEFAULT '' COMMENT '下单来源IP（风控用）',
    `remark`         VARCHAR(255)      NOT NULL DEFAULT '' COMMENT '备注',
    `create_time`    DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `update_time`    DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_user_activity` (`user_id`, `activity_id`) COMMENT '防重复下单的最终兜底，前提 per_user_limit=1',
    KEY `idx_status_expire` (`status`, `expire_time`) COMMENT '超时订单扫描 / 延迟消息补偿',
    KEY `idx_user_create` (`user_id`, `create_time` DESC) COMMENT '「我的订单」',
    KEY `idx_activity_status` (`activity_id`, `status`) COMMENT '活动维度统计与对账'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='秒杀订单表';


-- =====================================================================================
-- 五、积分与商城域
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- t_user_points 积分账户表
-- 【为什么独立成表而不给 t_user 加一列】
--   1) t_user 是热点表（每次登录都改 last_login_time），积分列放进去会让签到/兑换
--      与登录抢同一行的行锁；
--   2) 更关键的是事务边界：扣积分 + 写订单 + 记流水必须在同一事务里，三者都属于积分域。
--      若余额在 t_user，这个事务就得跨模块写 forum-user 的表，模块边界当场失效。
-- 【不变式】balance = total_earned - total_spent，由每日对账任务校验。
-- 【为什么允许 balance 这样的冗余列】高频读（每次进积分页）、低频写（每次变动伴随流水），
--   用不变式约束它，比每次读的时候去 SUM 流水划算得多。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_user_points`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `balance`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '可用积分余额',
    `total_earned` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '累计获得积分（只增）',
    `total_spent`  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '累计消耗积分（只增）',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`) COMMENT '一个用户一条账户记录'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='积分账户表';


-- -------------------------------------------------------------------------------------
-- t_points_record 积分流水表（账户余额的唯一解释来源）
-- 【uk_user_biz 是本表存在的核心理由】它是「同一笔业务只能记一次账」的最后防线：
--   签到接口被重放、月度奖励任务重复执行、取消订单接口并发调用都可能发生，
--   而 Redis 去重会失效、条件 UPDATE 在特定顺序下也可能重复执行，唯一索引不会失效。
-- 【biz_id 为什么是业务标识而不是外键 ID】月度奖励没有对应的业务行（不是某条记录触发的），
--   用字符串语义标识（如 2026-08）可以覆盖所有类型，不必为「无对应行」的场景造一张空表。
-- 【balance_after 为什么值得存】排查「用户说积分不对」时一眼看出从哪一笔开始偏离，
--   没有它只能把全部流水重算一遍。它是快照，不是真相。
-- 【游标分页用 id 而不是 create_time】流水是纯追加的，id 与时间同序，
--   且 id 是主键、无精度问题、也不用担心同秒多条。
-- 业务类型语义：biz_type 1签到 2月度全勤奖励 3商城兑换 4兑换取消退回 5管理员调整
--                              6秒杀消耗 7秒杀退回
--   注意 6/7 与 3/4 是分开的：秒杀与商城是两条独立链路（见《02-架构设计》ADR-010），
--   它们的 biz_id 都是订单号，但订单号来自不同的表（t_seckill_order / t_mall_order）。
--   共用 biz_type 会让「每笔消耗都能查到对应订单」这条校验无法区分去查哪张表。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_points_record`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `change_amount` INT             NOT NULL COMMENT '积分变动，正为获得、负为消耗',
    `balance_after` INT UNSIGNED    NOT NULL COMMENT '变动后余额快照（便于对账与排查）',
    `biz_type`      TINYINT         NOT NULL COMMENT '业务类型 1签到 2月度全勤奖励 3商城兑换 4兑换取消退回 5管理员调整 6秒杀消耗 7秒杀退回',
    `biz_id`        VARCHAR(64)     NOT NULL COMMENT '业务标识：签到 yyyy-MM-dd / 奖励 yyyy-MM / 订单 order_no',
    `remark`        VARCHAR(128)    NOT NULL DEFAULT '' COMMENT '备注',
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_biz` (`user_id`, `biz_type`, `biz_id`) COMMENT '同一笔业务只能记一次账',
    KEY `idx_user_id` (`user_id`, `id` DESC) COMMENT '「我的积分明细」游标分页'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='积分流水表';


-- -------------------------------------------------------------------------------------
-- t_user_signin 签到记录表（签到行为的最终真相）
-- 【与 Redis Bitmap 的关系】位图（forum:points:signin:{userId}:{yyyyMM}）是缓存与加速器，
--   本表才是真相。位图可能因 Redis 重启、误删或「置位后事务回滚」而与本表不一致，
--   由每日 04:00 的修复任务按本表重建位图（详见《02-架构设计》ADR-007）。
-- 【uk_user_date 的意义】保证「同一天不可能有两行」，
--   因此任何重复请求最多让位图多一次 SETBIT，绝不会多发积分。
-- 【year_month 是刻意的冗余列】月度全勤结算要按 WHERE year_month = ? 过滤，
--   若写成 DATE_FORMAT(signin_date,'%Y-%m') = ?，函数会让索引失效，
--   这个每天都要跑的查询就退化成全表扫描。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_user_signin`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `signin_date` DATE            NOT NULL COMMENT '签到日期（服务器时区 Asia/Shanghai 的自然日）',
    `year_month`  CHAR(7)         NOT NULL COMMENT '所属年月 yyyy-MM（冗余列，供月度结算走索引）',
    `points`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '当次获得积分',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `signin_date`) COMMENT '同一天只能签到一次',
    KEY `idx_month_user` (`year_month`, `user_id`) COMMENT '月度全勤结算：按月份分组统计签到天数'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='签到记录表';


-- -------------------------------------------------------------------------------------
-- t_mall_goods 积分商城商品表
-- 【库存恒等式】stock + sold_count = 初值。
--   与秒杀的三层模型不同，这里只有两层 —— 商城是低频场景，没有「已下单未支付」的中间态
--   （兑换即时扣减、即时完成），多出来的 locked_stock 只会增加对账口径的复杂度。
-- 【列表索引为什么带 sort_order DESC, id DESC】与 t_post 的列表索引是同一个教训：
--   游标分页 ORDER BY sort_order DESC, id DESC 要求索引里这两个列方向一致，
--   否则必然 filesort（见《04-数据库设计》1.5 节的实测结论）。
-- 【商品为什么是软下架而非物理删除】历史订单要能显示「当时兑换的是什么」。
-- 状态语义：status 0草稿 1上架 2下架
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_mall_goods`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `name`         VARCHAR(64)     NOT NULL COMMENT '商品名称',
    `cover_image`  VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '封面图',
    `description`  VARCHAR(1024)   NOT NULL DEFAULT '' COMMENT '商品描述',
    `type`         TINYINT         NOT NULL DEFAULT 1 COMMENT '类型 1优惠券 2实物 3虚拟物品',
    `points_price` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '兑换所需积分',
    `stock`        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '剩余库存',
    `sold_count`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '已兑换数量',
    `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '状态 0草稿 1上架 2下架',
    `sort_order`   INT             NOT NULL DEFAULT 0 COMMENT '排序权重，越大越靠前',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_sort` (`status`, `sort_order` DESC, `id` DESC) COMMENT '商城列表游标分页'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='积分商城商品表';


-- -------------------------------------------------------------------------------------
-- t_mall_order 积分商城兑换订单表
-- 【为什么冗余 goods_name / goods_type】订单是历史凭证。商品改名、调价、下架之后，
--   订单页仍要显示「当时兑换的是什么」。这与 t_seckill_order 冗余 coupon_id 是同一个理由，
--   但这里冗余的是文本快照 —— 因为商品名是会被改的，ID 指向的当前值已不是当初的样子。
-- 【为什么没有 uk_user_goods 唯一索引】商城允许同一用户重复兑换同一商品
--   （库存与积分是唯一的约束），这与秒杀「每人限购 1 件」的 uk_user_activity 恰好相反
--   —— 限购与否是业务规则，不是技术选择。
-- 【订单号】M + yyyyMMdd + 6 位日内序号（Redis INCR 生成），位数固定故按字典序即按时间序。
-- 【取消退回】status 0→2 时在同一事务内退积分、退库存、写 biz_type=4 的正数流水。
-- 状态语义：status 0待发放 1已完成 2已取消
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_mall_order`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no`    VARCHAR(32)     NOT NULL COMMENT '订单号 M+yyyyMMdd+6位日内序号',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '下单用户ID',
    `goods_id`    BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    `goods_name`  VARCHAR(64)     NOT NULL COMMENT '商品名称（下单时快照，商品改名后仍显示当时的值）',
    `goods_type`  TINYINT         NOT NULL DEFAULT 1 COMMENT '商品类型（下单时快照）',
    `points_cost` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '消耗积分',
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '状态 0待发放 1已完成 2已取消',
    `remark`      VARCHAR(255)    NOT NULL DEFAULT '' COMMENT '备注',
    `finish_time` DATETIME                 DEFAULT NULL COMMENT '发放完成时间',
    `cancel_time` DATETIME                 DEFAULT NULL COMMENT '取消时间',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`, `id` DESC) COMMENT '「我的兑换记录」游标分页',
    KEY `idx_status_id` (`status`, `id` DESC) COMMENT '后台按状态筛选订单'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='积分商城兑换订单表';


-- =====================================================================================
-- 六、基础设施域（消息可靠性与压测对比支撑）
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- t_mq_message 本地消息表（可靠投递）
-- 【用途】解决「DB 操作成功但消息发送失败」的双写不一致问题：
--   业务事务内先写本表（status=0 待发送），事务提交后由投递线程扫描并真正发到 MQ，
--   发送成功改 status=1。发送失败按 next_retry_time 指数退避重试。
-- 【为什么同时有 provider 列】因为本项目需要在 Kafka / RabbitMQ 之间切换并对比，
--   记录每条消息实际走的实现，便于按 provider 维度统计发送耗时与失败率。
-- 状态语义：status 0待发送 1已发送 2发送失败(重试中) 3已消费 4消费失败(需人工介入)
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_mq_message`
(
    `id`              BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `message_id`      VARCHAR(64)       NOT NULL COMMENT '全局唯一消息ID（雪花或UUID），消费端幂等键',
    `provider`        VARCHAR(16)       NOT NULL COMMENT '消息中间件实现 kafka / rabbitmq',
    `topic`           VARCHAR(128)      NOT NULL COMMENT 'Kafka Topic 或 RabbitMQ Exchange 名',
    `routing_key`     VARCHAR(128)      NOT NULL DEFAULT '' COMMENT 'RabbitMQ routingKey / Kafka partition key',
    `biz_type`        VARCHAR(64)       NOT NULL COMMENT '业务类型 LIKE_CHANGED / COMMENT_CREATED / SECKILL_ORDER 等',
    `biz_key`         VARCHAR(128)      NOT NULL COMMENT '业务幂等键，如 like:{userId}:{targetType}:{targetId}',
    `payload`         JSON              NOT NULL COMMENT '消息体',
    `headers`         JSON                       DEFAULT NULL COMMENT '附加消息头',
    `status`          TINYINT           NOT NULL DEFAULT 0 COMMENT '状态 0待发送 1已发送 2发送失败 3已消费 4消费失败',
    `retry_count`     SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retry`       SMALLINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '最大重试次数',
    `next_retry_time` DATETIME                   DEFAULT NULL COMMENT '下次重试时间（指数退避：2^n 秒）',
    `error_msg`       VARCHAR(1024)     NOT NULL DEFAULT '' COMMENT '最近一次错误信息',
    `create_time`     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    KEY `idx_status_retry` (`status`, `next_retry_time`) COMMENT '投递线程扫描待发送/待重试',
    KEY `idx_biz` (`biz_type`, `biz_key`) COMMENT '按业务键排查',
    KEY `idx_provider_status` (`provider`, `status`) COMMENT '按 MQ 实现维度统计成功率',
    KEY `idx_create_time` (`create_time`) COMMENT '归档清理'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='本地消息表（保证消息可靠投递）';


-- -------------------------------------------------------------------------------------
-- t_mq_consume_log 消费幂等日志表
-- 【用途】消费端幂等去重：uk_msg_consumer 保证同一条消息在同一消费组内只被处理一次。
-- 【关键列 cost_ms】记录单条消息的处理耗时，由消费切面自动埋点。
--   这是本项目「Kafka vs RabbitMQ 性能对比」的核心数据来源之一 ——
--   无需额外压测工具即可从本表直接聚合出端到端消费延迟分布：
--     SELECT provider, topic, COUNT(*), AVG(cost_ms), MAX(cost_ms)
--     FROM t_mq_consume_log WHERE create_time > ? GROUP BY provider, topic;
-- 【清理策略】本表增长极快，按 create_time 保留 7 天，由定时任务分批删除。
-- 状态语义：status 0处理中 1成功 2失败
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_mq_consume_log`
(
    `id`             BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `message_id`     VARCHAR(64)       NOT NULL COMMENT '消息全局ID',
    `consumer_group` VARCHAR(128)      NOT NULL COMMENT '消费者组（Kafka）或队列名（RabbitMQ）',
    `provider`       VARCHAR(16)       NOT NULL COMMENT '消息中间件实现 kafka / rabbitmq',
    `topic`          VARCHAR(128)      NOT NULL COMMENT 'Topic 或 Exchange',
    `biz_type`       VARCHAR(64)       NOT NULL DEFAULT '' COMMENT '业务类型',
    `status`         TINYINT           NOT NULL DEFAULT 0 COMMENT '状态 0处理中 1成功 2失败',
    `retry_count`    SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务重试次数',
    `cost_ms`        INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '消费处理耗时（毫秒），压测统计用',
    `error_msg`      VARCHAR(1024)     NOT NULL DEFAULT '' COMMENT '失败原因',
    `create_time`    DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_consumer` (`message_id`, `consumer_group`) COMMENT '幂等去重的核心约束',
    KEY `idx_provider_topic_time` (`provider`, `topic`, `create_time`) COMMENT '压测对比聚合查询',
    KEY `idx_create_time` (`create_time`) COMMENT '按时间清理'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='消息消费幂等日志表（兼压测埋点）';


-- -------------------------------------------------------------------------------------
-- t_mq_benchmark_result 压测结果记录表
-- 【用途】把 Kafka / RabbitMQ 的对比实验数据结构化沉淀下来，
--   使「换实现 → 重跑压测 → 查表对比」成为可重复的流程，而不是一次性手写报告。
--   每次压测以一个 batch_no 标识，同一批次下两种 provider 使用完全相同的参数行，
--   保证可比性（同 scenario、同 concurrency、同 partition_count、同 batch_size）。
-- -------------------------------------------------------------------------------------
CREATE TABLE `t_mq_benchmark_result`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `batch_no`        VARCHAR(64)     NOT NULL COMMENT '压测批次号，同批次的两个 provider 结果互为对照',
    `provider`        VARCHAR(16)     NOT NULL COMMENT '消息中间件实现 kafka / rabbitmq',
    `scenario`        VARCHAR(64)     NOT NULL COMMENT '压测场景 LIKE_EVENT / COMMENT_EVENT / SECKILL_ORDER',
    `concurrency`     INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '生产端并发线程数',
    `partition_count` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT 'Kafka 分区数 / RabbitMQ 队列数',
    `consumer_count`  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '消费端并发消费者数',
    `batch_size`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '批量大小（Kafka batch.size / RMQ prefetch）',
    `ack_mode`        VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '确认模式，如 acks=1 / publisher-confirm',
    `total_messages`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '发送消息总数',
    `produce_qps`     DECIMAL(12, 2)  NOT NULL DEFAULT 0 COMMENT '生产端 QPS',
    `consume_qps`     DECIMAL(12, 2)  NOT NULL DEFAULT 0 COMMENT '消费端 QPS',
    `p50_ms`          DECIMAL(10, 2)  NOT NULL DEFAULT 0 COMMENT '端到端延迟 P50',
    `p95_ms`          DECIMAL(10, 2)  NOT NULL DEFAULT 0 COMMENT '端到端延迟 P95',
    `p99_ms`          DECIMAL(10, 2)  NOT NULL DEFAULT 0 COMMENT '端到端延迟 P99',
    `max_ms`          DECIMAL(10, 2)  NOT NULL DEFAULT 0 COMMENT '端到端延迟最大值',
    `error_count`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '失败/重复投递次数',
    `cpu_peak_pct`    DECIMAL(5, 2)   NOT NULL DEFAULT 0 COMMENT '压测期间峰值 CPU 使用率（%）',
    `mem_peak_mb`     INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '压测期间峰值内存（MB）',
    `disk_peak_mb`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT 'Broker 磁盘占用峰值（MB）',
    `remark`          VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '备注，如异常现象、调参记录',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (`id`),
    KEY `idx_batch` (`batch_no`),
    KEY `idx_provider_scenario` (`provider`, `scenario`, `create_time` DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='消息中间件压测结果记录表';
