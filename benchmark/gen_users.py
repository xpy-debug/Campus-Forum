"""生成压测用的用户夹具 SQL。

默认产出 10000 个可登录用户，写完即可用 mysql 客户端导入。

【为什么直接生成 SQL，而不是调注册接口】
    注册接口每个用户都要跑一次 BCrypt（strength=10，约 100ms），1 万个用户
    串行要十几分钟，而且会被 Redisson 注册锁 + uk_username 反复挡。
    但 BCrypt 的密文对同一明文是可复用的——直接复用种子里 123456 的密文，
    密码依然能通过 PasswordEncoder.matches() 校验，批量 INSERT 秒级完成。

【为什么每个用户都要有唯一 student_no】
    uk_student_no 是唯一索引（允许 NULL）。留 NULL 虽然合法，但夹具用户就
    没有可辨识的业务标识了；给每个夹具用户一个唯一学号，出问题时能一眼看出
    它是压测数据而不是真实用户。

用法：
    python benchmark/gen_users.py                     # 10000 个，前缀 load
    python benchmark/gen_users.py --count 50000
    python benchmark/gen_users.py --prefix bench --start 1

导入（容器内 mysql 客户端因 LANG=C.UTF-8 默认即 utf8mb4，无需额外参数）：
    docker exec -i forum-mysql mysql -uroot -pforum123456 < benchmark/generated/load-users.sql

清理：
    DELETE FROM `t_user` WHERE username LIKE 'load%';
"""

import argparse
import os

# 种子数据里 123456 的 BCrypt 密文（strength=10）。复用它意味着夹具用户
# 的密码同样是 123456，与 README 里公布的测试账号一致。
PASSWORD_HASH = "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi"

DB = "school_forum"

# 每一条 INSERT 语句塞多少行。太大会顶到 max_allowed_packet，
# 太小则语句数量多、解析开销大。1000 行约 200KB，足够安全也足够快。
CHUNK = 1000


def sql_str(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def build(count: int, prefix: str, start: int, width: int) -> str:
    lines = [
        "-- 由 benchmark/gen_users.py 生成，请勿手工编辑",
        "-- 密码统一为 123456（复用种子的 BCrypt 密文）",
        f"USE `{DB}`;",
        "SET NAMES utf8mb4;",
        "",
    ]

    end = start + count - 1
    cols = "(`username`, `password`, `nickname`, `student_no`)"

    for chunk_start in range(start, end + 1, CHUNK):
        chunk_end = min(chunk_start + CHUNK - 1, end)
        rows = []
        for i in range(chunk_start, chunk_end + 1):
            username = f"{prefix}{i:0{width}d}"
            nickname = f"压测用户{i:0{width}d}"
            student_no = f"LOAD{i:08d}"
            rows.append(
                "(" + ", ".join([
                    sql_str(username),
                    sql_str(PASSWORD_HASH),
                    sql_str(nickname),
                    sql_str(student_no),
                ]) + ")"
            )
        lines.append(f"INSERT INTO `t_user` {cols} VALUES")
        lines.append(",\n".join(rows) + ";")
        lines.append("")

    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=10000, help="生成多少个用户")
    parser.add_argument("--prefix", default="load", help="用户名前缀")
    parser.add_argument("--start", type=int, default=1, help="起始序号")
    parser.add_argument("--out", default=os.path.join("benchmark", "generated", "load-users.sql"))
    args = parser.parse_args()

    if args.count < 1:
        raise SystemExit("--count 必须 >= 1")

    # 序号宽度按最大序号自动取，至少 6 位，保证用户名可读且排序稳定
    width = max(6, len(str(args.start + args.count - 1)))

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(build(args.count, args.prefix, args.start, width))

    size_kb = os.path.getsize(args.out) / 1024
    print(f"已生成 {args.count} 个用户 -> {args.out} ({size_kb:.0f} KB)")
    print(f"用户名范围：{args.prefix}{args.start:0{width}d} ~ "
          f"{args.prefix}{args.start + args.count - 1:0{width}d}")
    print(f"密码：123456")


if __name__ == "__main__":
    main()
