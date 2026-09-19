# 本地开发环境

## 组件与端口

| 组件 | 地址 | 账号 | 用途 |
|---|---|---|---|
| MySQL | `localhost:3306` | `root` / `forum123456` | 主存储，库名 `school_forum` |
| Redis | `localhost:6379` | 无密码 | 缓存、计数、库存、分布式锁 |
| RabbitMQ | `localhost:5672` | `guest` / `guest` | 可靠投递、延迟消息 |
| RabbitMQ 管理台 | http://localhost:15672 | `guest` / `guest` | 查看队列与积压 |
| Kafka | `localhost:9092` | 无认证 | 高吞吐事件流（KRaft 模式） |
| Kafka UI | http://localhost:8090 | — | 查看 Topic / 分区 / 消费组积压（需 `ui` profile，见下） |
| Redis Commander | http://localhost:8081 | — | 查看 Key，调试点赞 Set 与秒杀库存（需 `ui` profile） |

> 两个 UI 在 compose 里归入 `ui` profile，**默认不启动**。原因见
> [镜像拉不下来怎么办](#镜像拉不下来怎么办) —— 它们的镜像在本机拉不下来，
> 若不隔离，`docker compose up -d` 会因为它们整体报错。
>
> 好消息是：MQ 对比实验的主要数据来源是 `t_mq_consume_log` 表（逐条记录了
> provider、耗时、重试次数、消费组），比肉眼看 UI 更可靠，UI 只是辅助。

## 常用命令

```bash
cd docker

# 启动核心组件（mysql / redis / rabbitmq / kafka）
docker compose up -d

# 连同 UI 一起启动（需先备好 UI 镜像，见下）
docker compose --profile ui up -d

# 查看状态
docker compose ps

# 查看某个组件日志
docker compose logs -f mysql

# 停止（保留数据）
docker compose down

# 停止并清空所有数据（下次启动会重新执行建表脚本）
docker compose down -v
```

## 镜像拉不下来怎么办

Kafka 镜像**不是**用 `docker pull` 拉下来的，而是用 `build-kafka-image.sh` 手工组装的。
这不是绕远路，是被逼出来的，过程和结论都值得记下来：

### 症状

```
docker pull apache/kafka:3.7.0
→ failed to copy: httpReadSeeker: failed open: failed to do request:
  Get "https://cloudfront-docker-cf.mrs.1ms.run/.../blobs/sha256/fe/fe7e6a65.../data": EOF
```

### 排查

1. **换源无效**。`docker.1ms.run`、`hub.rat.dev`、`docker.1panel.live` 最终都 302 到
   同一个后端 `cloudfront-docker-cf.mrs.1ms.run`——换的只是门面，后端是同一台。
2. **失败的偏偏不是大文件**。拉下 manifest 逐个 blob 分析后发现，失败的是
   **7.5 KB 的 config blob**，而 200 MB 的镜像层反而下得动。
3. **curl 请求同一个 URL 秒下**，内容完全正确（`http=200 size=7583`，JSON 合法）。

### 结论

不是网络问题，也不是 CDN 缺这个对象，而是 **docker 客户端读取 blob 的实现
（registry 的 `httpReadSeeker`）与这家 CDN 的响应不兼容**。镜像层下载走的是另一条
代码路径，所以大层反而没事。

> 这也解释了为什么"多试几个镜像源"这类常规办法在这里全部失效——
> 得先看清失败的是哪一层，才知道换源解决不了问题。

### 对策

`build-kafka-image.sh` 绕过 docker 的 blob 读取逻辑：用 curl 把各 blob 下全，
按 `docker-archive` 格式打包，再 `docker load` 进本地（tag 为 `forum/kafka:3.7.0`）。
curl 有重试和断点续传，比 docker 客户端健壮得多。

```bash
cd docker
bash build-kafka-image.sh
```

脚本会校验**每个层的 sha256**，与官方 manifest 比对一致后才组装，
所以产出的镜像与官方 `apache/kafka:3.7.0` 二进制一致。

#### 下载速度：单连接被限速，分块并发近线性叠加

实测这家 CDN **对单条连接限速在 ~150 KB/s，但不限制总带宽**：

| 方式 | 聚合速度 |
|---|---|
| 单连接 | ~147 KB/s |
| 4 路并发 | ~712 KB/s |
| 8 路并发（脚本默认） | 实测 200 MB / 约 6 分钟 |

所以脚本用 HTTP Range 请求（206）把每个层切成 4 MB 的块并发下载。
**这不只是"快一点"的问题**：单连接下光那个 122 MB 的层就要十几分钟，
中途断一次还得从头再来；分块之后每块独立重试、独立续传，
断一块只重下这一块。

> ⚠️ **不要同时跑两个实例**。脚本按固定文件名写 `layers/layerN.blob`，
> 两个实例并发写同一批文件会互相覆盖。中断后重跑是安全的——
> 已经下完且大小正确的层会被跳过。

### 需要 UI 时

脚本的目标镜像可用环境变量覆盖，同一个 CDN 问题影响所有镜像：

```bash
IMG=provectuslabs/kafka-ui TAG=latest TARGET=forum/kafka-ui:latest \
WORK=/c/work/school/docker/kafka-ui-image bash build-kafka-image.sh
```

然后把 compose 里 `kafka-ui` 的 `image` 换成 `forum/kafka-ui:latest`。

## 数据库初始化

MySQL 容器**首次启动时会自动执行** `sql/` 下的脚本（按文件名排序）：

1. `sql/01-schema.sql` —— 建库建表，23 张表
2. `sql/02-init-data.sql` —— 种子数据，并做数据自洽性校验

> ⚠️ `01-schema.sql` 开头有 `DROP DATABASE IF EXISTS school_forum`。
> 它只在容器**首次初始化**时执行一次，之后重启不会重跑。
> 若要重新初始化，必须 `docker compose down -v` 清空数据卷后再启动。

### 验证数据是否正常

```bash
docker exec -it forum-mysql mysql -uroot -pforum123456 school_forum --table \
  -e "SELECT '用户' t, COUNT(*) n FROM t_user
      UNION ALL SELECT '帖子', COUNT(*) FROM t_post
      UNION ALL SELECT '评论', COUNT(*) FROM t_comment
      UNION ALL SELECT '点赞', COUNT(*) FROM t_user_like;"
```

预期：用户 5 / 帖子 8 / 评论 16 / 点赞 19。

### 连接数据库

```bash
docker exec -it forum-mysql mysql -uroot -pforum123456 school_forum
```

或从宿主机用任意客户端连接 `localhost:3306`。

> **中文与字符集**：`ngram_token_size=2` 已在 compose 中配置；mysql 服务同时设了
> `LANG=C.UTF-8`，容器内客户端默认即按 utf8mb4 通信。因此上面这些
> `docker exec ... mysql` 命令可以直接查中文，**不需要**再加 `--default-character-set=utf8mb4`
> （加了也无害，只是冗余）。
>
> 若在别的环境里又看到中文变 `??????`、或中文检索搜不到东西，仍是客户端字符集问题而非数据问题：
> 用 `SELECT HEX(列)` 确认字节没坏，再给客户端补上 `--default-character-set=utf8mb4`。
> 同理，**初始化导入也必须让客户端处于 utf8mb4**，否则脚本里的中文会被静默双重编码写坏
> （`论坛板块表` 的 `E8AEBA...` 变成 `C3A8C2AE...`，全程不报错）——这正是 compose 里加 `LANG` 的原因。

## 关于延迟消息

本编排**未安装** `rabbitmq_delayed_message_exchange` 插件。订单超时取消使用 RabbitMQ 内置的
**TTL + 死信队列（DLX）** 实现，无需任何插件：

```
订单创建
  → 投递到 order.delay.queue（设置队列级 TTL = 支付超时时间，不注册消费者）
  → 消息到期成为死信
  → 路由到 order.dlx（死信交换机）
  → 进入 order.cancel.queue
  → 被取消订单消费者处理
```

> ⚠️ **TTL + DLX 最著名的坑**：队列是先进先出的，若同一队列内消息的 TTL 不一致，
> 先入队的长 TTL 消息会**阻塞**后面短 TTL 消息的过期。
> 本项目同一活动的支付超时时间统一（由 `t_seckill_activity.pay_timeout_sec` 决定），
> 因此不触发该问题。若未来需要为不同活动设置不同超时，必须按 TTL 值拆分队列，
> 或改用延迟插件。

### 如需启用延迟插件

若压测对比时需要测试插件的表现，可自行构建镜像：

```dockerfile
# docker/rabbitmq/Dockerfile
FROM rabbitmq:3.13-management
RUN apt-get update && apt-get install -y curl && \
    curl -L -o /opt/rabbitmq/plugins/rabbitmq_delayed_message_exchange-3.13.0.ez \
      https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v3.13.0/rabbitmq_delayed_message_exchange-3.13.0.ez
RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
```

然后在 compose 中把 `rabbitmq` 服务的 `image` 换成 `build: ./rabbitmq`。

下载地址的版本号必须与 RabbitMQ 版本匹配，否则插件加载会失败。

## 资源占用

| 组件 | 内存上限（建议） |
|---|---|
| MySQL | 1 GB |
| Redis | 512 MB（已在启动参数中限制） |
| RabbitMQ | 512 MB |
| Kafka | 1 GB |
| 两个 UI | 各 256 MB |

全量启动约需 4 GB 可用内存。内存紧张时不要启动 `kafka-ui` 与 `redis-ui`。

## 压测对比实验的环境隔离

《05-消息中间件选型对比方案》第 5.1 节要求 **Kafka 与 RabbitMQ 不得同时运行在同一台机器上**，
否则两者互相争抢 IO 会导致数据不可比。

本编排默认同时启动两者是为了开发便利。**正式压测时必须分开跑**：

```bash
# 第一轮：只跑 Kafka
docker compose down
docker compose up -d mysql redis kafka

# 清理环境后再跑第二轮
docker compose down -v
docker compose up -d mysql redis rabbitmq
```

并且每轮之间应重启机器以清理 OS 页缓存。
