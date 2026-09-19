"""积分 + 积分商城 端到端冒烟测试。

直接用 urllib 打 HTTP，不做任何 mock：要验证的正是「拦截器 + 事务 + 条件更新」
这一整条真实链路，绕开任何一环都不算数。

【先决条件】后端在 8081 上跑，且数据库处于刚导入种子的状态，
另外先造好 2026-08 的签到夹具（第 9 节要用，见 docs/06 的「验证」一节）：

    docker exec -i forum-mysql mysql -uroot -pforum123456 --default-character-set=utf8mb4 \\
      -D school_forum <<'SQL'
    INSERT INTO t_user_signin (user_id, signin_date, `year_month`, points, create_time)
    WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 26)
    SELECT 3, DATE_ADD('2026-08-01', INTERVAL n - 1 DAY), '2026-08', 5, NOW() FROM seq;
    SQL

第 1 节要求 lisi 当天还没签到，第 9 节要求 2026-08 尚未结算过，因此这个脚本
不能在同一个数据库状态上连跑两次——重跑前先重新导入 sql/01 + sql/02 + 夹具。

【为什么用 8081 而不是 8080】8080 常常被 IDE 里那个启动中的实例占着，
用一个独立端口能让冒烟测试与「手边正在调的那份服务」互不干扰。
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://localhost:8081/api", help="后端地址")
BASE = parser.parse_args().base
PASS = 0
FAIL = 0


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    try:
        with urllib.request.urlopen(req, data) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def check(label, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [PASS] {label}" + (f"  ({detail})" if detail else ""))
    else:
        FAIL += 1
        print(f"  [FAIL] {label}  -> {detail}")


def login(username):
    _, r = call("POST", "/auth/login", body={"username": username, "password": "123456"})
    assert r["code"] == 0, f"登录失败 {username}: {r}"
    return r["data"]["accessToken"]


def section(title):
    print(f"\n=== {title} ===")


admin = login("admin")
zhangsan = login("zhangsan")
lisi = login("lisi")
wangwu = login("wangwu")
zhaoliu = login("zhaoliu")

# ---------------------------------------------------------------- 签到
section("1. 签到（lisi 今天还没签）")
_, r = call("GET", "/points/signin/status", lisi)
before = r["data"]
check("签到前 signedToday=false", before["signedToday"] is False, before)
check("签到前天数 = 12（种子数据）", before["signedDays"] == 12, f"signedDays={before['signedDays']}")

_, r = call("POST", "/points/signin", lisi)
d = r.get("data") or {}
check("签到成功 success=true", r["code"] == 0 and d.get("success") is True, r)
check("本次积分 = 5", d.get("points") == 5, f"points={d.get('points')}")
check("签到后天数 = 13", d.get("signedDays") == 13, f"signedDays={d.get('signedDays')}")

_, r = call("POST", "/points/signin", lisi)
d2 = r.get("data") or {}
check("重复签到被幂等拦下（不报 5xx）", r["code"] == 0 and d2.get("success") is False,
      f"code={r['code']} message={r['message']} data={d2}")

_, r = call("GET", "/points/signin/status", lisi)
after = r["data"]
check("签到后 signedToday=true", after["signedToday"] is True)
check("签到后天数仍为 13（没被重复签到加两次）", after["signedDays"] == 13, f"signedDays={after['signedDays']}")

section("2. 签到幂等 —— 已签过的 zhangsan 再签")
_, r = call("POST", "/points/signin", zhangsan)
d = r.get("data") or {}
check("zhangsan 重复签到 success=false", r["code"] == 0 and d.get("success") is False,
      f"code={r['code']} data={d}")

section("3. 积分账户")
_, r = call("GET", "/points/account", lisi)
acc = r["data"]
check("lisi 余额 = 65（60 + 5）", acc["balance"] == 65, acc)

_, r = call("GET", "/points/records", lisi)
rec = r["data"]
first = rec["list"][0]
check("最新一条是签到 +5", first["changeAmount"] == 5 and "签到" in first["bizTypeName"],
      f"{first['bizTypeName']} {first['changeAmount']} balanceAfter={first['balanceAfter']}")
check("流水的 balanceAfter 与账户余额一致", first["balanceAfter"] == acc["balance"],
      f"{first['balanceAfter']} vs {acc['balance']}")

# ---------------------------------------------------------------- 商城：下单 / 取消
section("4. 商城下单（wangwu 余额 420，兑换 100 分的星巴克券）")
def detail(goods_id, token):
    """走详情接口读库存。

    按 ID 查单行，用来断言库存、已兑数这类精确数字。
    用户侧列表 /mall/goods 同样不走缓存，这里只是让断言更直接。
    """
    _, resp = call("GET", f"/mall/goods/{goods_id}", token)
    return resp["data"]


def account(token):
    _, resp = call("GET", "/points/account", token)
    return resp["data"]


_, r = call("GET", "/mall/goods", wangwu)
goods = {g["id"]: g for g in r["data"]}
check("能兑换：canExchange=true", goods[1]["canExchange"] is True, goods[1])
check("券库存 = 50", detail(1, wangwu)["stock"] == 50, detail(1, wangwu))

_, r = call("POST", "/mall/orders", wangwu, {"goodsId": 1, "remark": "冒烟测试"})
order = r["data"]
order_id = order["id"]
check("下单成功", r["code"] == 0, r)
check("订单快照了商品名与价格", order["goodsName"] == "星巴克中杯券" and order["pointsCost"] == 100, order)
check("订单号形如 M+日期+序号", order["orderNo"].startswith("M2026"), order["orderNo"])
check("状态为待发放", order["statusName"] == "待发放", order["statusName"])

acc = account(wangwu)
check("扣款后余额 = 320", acc["balance"] == 320, acc)
check("totalSpent 累加到 200", acc["totalSpent"] == 200, acc)

d1 = detail(1, wangwu)
check("库存扣减 50 -> 49", d1["stock"] == 49, f"stock={d1['stock']}")
check("已兑数 1 -> 2", d1["soldCount"] == 2, f"soldCount={d1['soldCount']}")

section("5. 积分不足（lisi 余额 65 < 100）")
_, r = call("POST", "/mall/orders", lisi, {"goodsId": 1})
check("返回 16002 积分不足", r["code"] == 16002, f"code={r['code']} message={r['message']}")

section("6. 取消订单，积分与库存都要回滚")
_, r = call("POST", f"/mall/orders/{order_id}/cancel", wangwu)
check("取消成功", r["code"] == 0, r)
check("状态变为已取消", r["data"]["statusName"] == "已取消", r["data"]["statusName"])

# 退回是一笔**正数流水**，因此计进 totalEarned 而不是冲减 totalSpent。
# 这保住了 balance = totalEarned - totalSpent 与
# balance = SUM(change_amount) 两条不变式，也保住了「历史消耗过 200 分」这个事实
acc = account(wangwu)
check("退款后余额回到 420", acc["balance"] == 420, acc)
check("退款计为收入：totalEarned 520 -> 620", acc["totalEarned"] == 620, acc)
check("totalSpent 保持 200（历史不被退款改写）", acc["totalSpent"] == 200, acc)
check("不变式 balance = totalEarned - totalSpent", acc["balance"] == acc["totalEarned"] - acc["totalSpent"], acc)

d1 = detail(1, wangwu)
check("库存回到 50", d1["stock"] == 50, f"stock={d1['stock']}")
check("已兑数回到 1", d1["soldCount"] == 1, f"soldCount={d1['soldCount']}")

_, r = call("POST", f"/mall/orders/{order_id}/cancel", wangwu)
check("重复取消被状态机拦下（16007）", r["code"] == 16007, f"code={r['code']} message={r['message']}")

_, r = call("POST", "/mall/orders", wangwu, {"goodsId": 5})
check("下架商品不能兑换（16004）", r["code"] == 16004, f"code={r['code']} message={r['message']}")

_, r = call("POST", "/mall/orders", lisi, {"goodsId": 4})
check("无库存不能兑换（16005）——4 号商品 stock=0", r["code"] == 16005,
      f"code={r['code']} message={r['message']}")

# ---------------------------------------------------------------- 权限边界
section("7. 权限边界：普通用户碰管理接口")
for method, path, body in [
    ("POST", "/admin/mall/goods", {"name": "越权商品", "type": 1, "pointsPrice": 1, "stock": 1}),
    ("GET", "/admin/mall/goods?page=1&size=20", None),
    ("GET", "/admin/mall/orders?page=1&size=20", None),
    ("POST", "/admin/points/settle-signin-bonus?month=2026-08", None),
]:
    _, r = call(method, path, lisi, body)
    check(f"普通用户 {method} {path.split('?')[0]} -> 10003", r["code"] == 10003,
          f"code={r['code']} message={r['message']}")

_, r = call("GET", "/points/account")
check("未登录访问 /points/account -> 10002", r["code"] == 10002, f"code={r['code']} message={r['message']}")

_, r = call("GET", "/mall/goods", lisi)
ids = sorted(g["id"] for g in r["data"])
check("用户侧商品列表只有上架商品", ids == [1, 2, 3, 4], ids)

# ---------------------------------------------------------------- 管理端商品
section("8. 管理端商品增改与用户侧可见性")
_, r = call("POST", "/admin/mall/goods", admin,
            {"name": "冒烟测试商品", "type": 3, "pointsPrice": 30, "stock": 10,
             "status": 0, "sortOrder": 1, "description": "冒烟用"})
check("管理员创建成功", r["code"] == 0, r)
new_id = r["data"]
check("返回新商品 ID", isinstance(new_id, int), new_id)

_, r = call("GET", f"/admin/mall/goods/{new_id}", admin)
check("新建为草稿", r["data"]["status"] == 0, r["data"])

_, r = call("GET", "/mall/goods", lisi)
ids = [g["id"] for g in r["data"]]
check("草稿不出现在用户侧列表", new_id not in ids, ids)

_, r = call("POST", f"/admin/mall/goods/{new_id}/status?status=1", admin)
check("上架成功", r["code"] == 0, r)
_, r = call("GET", "/mall/goods", lisi)
ids = [g["id"] for g in r["data"]]
check("上架后立刻出现在用户侧（列表不走缓存）", new_id in ids, ids)

_, r = call("POST", f"/admin/mall/goods/{new_id}/status?status=9", admin)
check("非法状态值被拒（10001）", r["code"] == 10001, f"code={r['code']} message={r['message']}")

# ---------------------------------------------------------------- 全勤奖励
section("9. 全勤奖励结算（2026-08）")
# 夹具（见 sql 之外的 smoke 准备语句）：
#   lisi(3)   26 天 -> 超过 25，应发
#   zhangsan(2) 25 天 -> 恰好 25，不该发（边界）
#   zhaoliu(5) 20 天 -> 差得远，不该发
_, r = call("POST", "/admin/points/settle-signin-bonus?month=2026-09", admin)
check("当月不结算，返回 0", r["code"] == 0 and r["data"] == 0, r)
_, r = call("POST", "/admin/points/settle-signin-bonus?month=2026-13", admin)
check("非法月份被拒（10001）", r["code"] == 10001, f"code={r['code']} message={r['message']}")

lisi_before = account(lisi)
zhangsan_before = account(zhangsan)
zhaoliu_before = account(zhaoliu)

_, r = call("POST", "/admin/points/settle-signin-bonus?month=2026-08", admin)
check("结算成功且只发了 1 人（25 天与 20 天都不算全勤）", r["code"] == 0 and r["data"] == 1, r)

lisi_after = account(lisi)
check("lisi 余额 +100", lisi_after["balance"] == lisi_before["balance"] + 100,
      f"{lisi_before['balance']} -> {lisi_after['balance']}")
check("zhangsan 恰好 25 天：不发", account(zhangsan)["balance"] == zhangsan_before["balance"],
      f"{zhangsan_before['balance']} -> {account(zhangsan)['balance']}")
check("zhaoliu 20 天：不发", account(zhaoliu)["balance"] == zhaoliu_before["balance"],
      f"{zhaoliu_before['balance']} -> {account(zhaoliu)['balance']}")

_, r = call("GET", "/points/records", lisi)
bonus = r["data"]["list"][0]
check("最新流水是「2026-08 全勤奖励 +100」",
      bonus["changeAmount"] == 100 and "全勤" in bonus["remark"], f"{bonus['bizTypeName']} {bonus['changeAmount']} {bonus['remark']}")

_, r = call("POST", "/admin/points/settle-signin-bonus?month=2026-08", admin)
check("再结算一次返回 0（幂等，不重复发）", r["code"] == 0 and r["data"] == 0, r)
check("重复结算后余额不变", account(lisi)["balance"] == lisi_after["balance"],
      f"{lisi_after['balance']} -> {account(lisi)['balance']}")

print(f"\n{'=' * 46}\nPASS={PASS}  FAIL={FAIL}\n{'=' * 46}")
sys.exit(1 if FAIL else 0)
