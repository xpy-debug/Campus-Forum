"""积分秒杀端到端冒烟测试。

覆盖「Redis 预扣 + outbox → Kafka → 消费者落库 + 待支付 + 超时回补 + 取消」整条链路。

【先决条件】
    1. 后端在 8081 上跑，且**以 Kafka profile 启动**（秒杀下单必须真的走到 MQ）：
         java -jar forum-boot/target/school-forum.jar \\
              --spring.profiles.active=mq-kafka --server.port=8081
    2. MySQL / Redis / Kafka 都在；数据库已导入 sql/01-schema.sql + sql/02-init-data.sql。
    3. 秒杀活动由本脚本自己通过管理端接口创建，因此不依赖种子里的时间窗，
       可以重复运行（不像 e2e-points.py 需要重新导入夹具）。

【为什么用 8081】与 e2e-points.py 一致：8080 常被 IDE 里正在调的实例占着。

【两个与实现强绑定的约定，改实现时记得同步这里】
  - 抢购限流是「单用户 10 秒 5 次」（forum.seckill.grab-rate-limit-per-ten-seconds），
    因此并发段里一部分请求会被 10006 挡掉，第 9 节前要等过这个窗口。
  - 「未预热」这一节必须在预热任务两次执行之间完成，否则活动会被后台预热、
    断言不再成立（预热任务每分钟第 0 秒跑）。脚本会先等到刚过整分钟再继续。
"""
import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta

sys.stdout.reconfigure(encoding="utf-8")

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://localhost:8081/api", help="后端地址")
BASE = parser.parse_args().base

PASS = 0
FAIL = 0

SECKILL_SOLD_OUT = 15001
SECKILL_ALREADY_JOINED = 15002
SECKILL_NOT_STARTED = 15003
SECKILL_NOT_WARMED_UP = 15005
ORDER_STATUS_ILLEGAL = 15013
POINTS_NOT_ENOUGH = 16002
FORBIDDEN = 10003
TOO_MANY_REQUESTS = 10006

# 秒杀活动的库存。用 2 是为了让「两个人抢完即售罄」这条断言有意义：
# 库存设成 5 却指望抢两次就抢光，是脚本自己的算术错误
STOCK = 2


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


def section(title):
    print(f"\n=== {title} ===")


def login(username):
    _, r = call("POST", "/auth/login", body={"username": username, "password": "123456"})
    assert r["code"] == 0, f"登录失败 {username}: {r}"
    return r["data"]["accessToken"]


def text(minutes_from_now, seconds=0):
    """管理端接口接受的格式是 yyyy-MM-dd HH:mm:ss（见 SeckillActivityForm 的 @JsonFormat）"""
    t = datetime.now() + timedelta(minutes=minutes_from_now, seconds=seconds)
    return t.strftime("%Y-%m-%d %H:%M:%S")


def create_activity(token, name, stock, points_cost, start, end,
                    pay_timeout=600, warmup=0, goods_id=1):
    """建一场活动并上线，返回活动 ID。默认「未预热」——预热要显式调 warmup"""
    _, r = call("POST", "/admin/seckill/activities", token, {
        "goodsId": goods_id,
        "name": name,
        "pointsCost": points_cost,
        "totalStock": stock,
        "perUserLimit": 1,
        "startTime": start,
        "endTime": end,
        "payTimeoutSec": pay_timeout,
        "warmupMinutes": warmup,
        "status": 0
    })
    assert r["code"] == 0, f"创建活动失败: {r}"
    activity_id = r["data"]
    _, r = call("POST", f"/admin/seckill/activities/{activity_id}/status?status=1", token)
    assert r["code"] == 0, f"上线失败: {r}"
    return activity_id


def admin_activity(token, activity_id):
    _, r = call("GET", f"/admin/seckill/activities/{activity_id}", token)
    assert r["code"] == 0, r
    return r["data"]


def account(token):
    _, r = call("GET", "/points/account", token)
    assert r["code"] == 0, r
    return r["data"]


def grab_and_wait(token, activity_id, timeout_seconds=20):
    """抢购并轮询到落库。返回 (抢购响应, 结果响应)"""
    _, grab = call("POST", f"/seckill/{activity_id}/grab", token)
    if grab["code"] != 0:
        return grab, None
    deadline = time.time() + timeout_seconds
    delay = 0.2
    while time.time() < deadline:
        time.sleep(delay)
        _, result = call("GET", f"/seckill/{activity_id}/result", token)
        if result["code"] == 0 and result["data"]["status"] != "PENDING":
            return grab, result
        delay = min(delay + 0.3, 2.0)
    return grab, None


def my_orders(token):
    _, r = call("GET", "/seckill/orders?size=50", token)
    assert r["code"] == 0, r
    return r["data"]["list"]


def align_to_minute_start():
    """等到刚过整分钟的那几秒。

    「未预热」这一节的断言只在预热任务两次执行之间成立，而该任务每分钟
    第 0 秒跑一次。等它刚跑完再继续，断言才是确定的而不是碰运气。
    """
    waited = 0
    while int(time.time() % 60) > 5 and waited < 65:
        time.sleep(1)
        waited += 1


admin = login("admin")
zhangsan = login("zhangsan")
lisi = login("lisi")
wangwu = login("wangwu")
zhaoliu = login("zhaoliu")

# ---------------------------------------------------------------- 校验顺序
section("1. 资格校验：未预热 / 未开始")
align_to_minute_start()

# 1.1 未预热：活动已在时间窗内，但库存还没加载到 Redis。
#     期望 15005（活动太火爆）而不是 15001（已抢光）——「系统没准备好」
#     与「你来晚了」是两件事，对用户来说是完全不同的信息
unwarmed_id = create_activity(admin, "冒烟·未预热", STOCK, 60, text(-1), text(30))
_, r = call("POST", f"/seckill/{unwarmed_id}/grab", wangwu)
check("未预热 -> 15005", r["code"] == SECKILL_NOT_WARMED_UP, f"code={r['code']} {r['message']}")

# 1.2 未开始：时间窗还没到。此时也不会预热（预热窗口从「开始前 N 分钟」起算），
#     因此这条用例同时验证了「快照缺失时仍能区分出未开始」
future_id = create_activity(admin, "冒烟·未开始", 5, 60, text(30), text(60))
_, r = call("POST", f"/seckill/{future_id}/grab", wangwu)
check("未开始 -> 15003（而不是 15005）", r["code"] == SECKILL_NOT_STARTED,
      f"code={r['code']} {r['message']}")

# ---------------------------------------------------------------- 主链路
section("2. 抢购 -> 排队中 -> Kafka 落库 -> 抢购成功")

_, r = call("POST", f"/admin/seckill/activities/{unwarmed_id}/warmup", admin)
check("手动预热成功", r["code"] == 0 and r["data"] is True, r)

before_activity = admin_activity(admin, unwarmed_id)
check(f"预热不改动 DB 库存（{STOCK}）", before_activity["availableStock"] == STOCK, before_activity)

grab, result = grab_and_wait(wangwu, unwarmed_id)
check("抢购返回排队中", grab["code"] == 0 and grab["data"]["status"] == "PENDING", grab)
order_no = grab["data"]["orderNo"] if grab["code"] == 0 else None
check("返回订单号（S + 日期 + 序号）", bool(order_no) and order_no.startswith("S2"), order_no)

check("轮询到 SUCCESS（Kafka 已落库）", result is not None and result["data"]["status"] == "SUCCESS",
      result)
check("结果里带回订单", result is not None and result["data"]["order"] is not None,
      result["data"] if result else None)

after_activity = admin_activity(admin, unwarmed_id)
check(f"DB 库存：可售 {STOCK} -> {STOCK - 1}", after_activity["availableStock"] == STOCK - 1,
      f"available={after_activity['availableStock']}")
check("DB 库存：锁定 0 -> 1", after_activity["lockedStock"] == 1,
      f"locked={after_activity['lockedStock']}")
check("库存恒等式成立", after_activity["totalStock"]
      == after_activity["availableStock"] + after_activity["lockedStock"] + after_activity["soldCount"],
      after_activity)

orders = my_orders(wangwu)
mine = [o for o in orders if o["orderNo"] == order_no]
check("订单出现在「我的秒杀」", len(mine) == 1, mine)
check("订单状态为待支付", mine and mine[0]["statusName"] == "待支付", mine[0] if mine else None)
check("支付截止时间已下发", mine and mine[0]["expireTime"] is not None, mine[0] if mine else None)

# ---------------------------------------------------------------- 幂等重放
section("3. 重复点击：幂等重放，返回同一张单而不是第二张")
_, again = call("POST", f"/seckill/{unwarmed_id}/grab", wangwu)
check("重复抢购不报错", again["code"] == 0, again)
check("拿回同一个订单号", again.get("data", {}).get("orderNo") == order_no,
      f"{again.get('data', {}).get('orderNo')} vs {order_no}")
after_activity = admin_activity(admin, unwarmed_id)
check(f"重复点击不再扣减库存（可售仍为 {STOCK - 1}）",
      after_activity["availableStock"] == STOCK - 1,
      f"available={after_activity['availableStock']}")

# ---------------------------------------------------------------- 抢光
section("4. 抢光 -> 15001")
grab2, result2 = grab_and_wait(zhangsan, unwarmed_id)
check("第二人抢到（库存刚好用尽）", grab2["code"] == 0, grab2)
check("轮询到 SUCCESS", result2 is not None and result2["data"]["status"] == "SUCCESS", result2)

_, r = call("POST", f"/seckill/{unwarmed_id}/grab", lisi)
check("库存耗尽 -> 15001", r["code"] == SECKILL_SOLD_OUT, f"code={r['code']} {r['message']}")

# ---------------------------------------------------------------- 支付
section("5. 支付：用积分换，locked -> sold")
balance_before = account(wangwu)["balance"]
_, r = call("POST", f"/seckill/orders/{order_no}/pay", wangwu)
check("支付成功", r["code"] == 0, r)
check("订单状态变为待发放", r["code"] == 0 and r["data"]["statusName"] == "待发放",
      r["data"] if r["code"] == 0 else r)

balance_after = account(wangwu)["balance"]
check("扣了 60 积分", balance_before - balance_after == 60,
      f"{balance_before} -> {balance_after}")

paid_activity = admin_activity(admin, unwarmed_id)
check("DB 库存：锁定 2 -> 1", paid_activity["lockedStock"] == 1,
      f"locked={paid_activity['lockedStock']}")
check("DB 库存：已售 0 -> 1", paid_activity["soldCount"] == 1,
      f"sold={paid_activity['soldCount']}")
check("支付后库存恒等式仍成立", paid_activity["totalStock"]
      == paid_activity["availableStock"] + paid_activity["lockedStock"] + paid_activity["soldCount"],
      paid_activity)

_, r = call("POST", f"/seckill/orders/{order_no}/pay", wangwu)
check("重复支付被状态机拦下（15013）", r["code"] == ORDER_STATUS_ILLEGAL,
      f"code={r['code']} {r['message']}")

# ---------------------------------------------------------------- 积分不足
section("6. 积分不足：支付失败，订单留在待支付，库存不回补")
poor_id = create_activity(admin, "冒烟·积分不足", 1, 99999, text(-1), text(30))
call("POST", f"/admin/seckill/activities/{poor_id}/warmup", admin)
grab3, result3 = grab_and_wait(lisi, poor_id)
check("抢购成功（抢购阶段不校验积分）", grab3["code"] == 0, grab3)
poor_order_no = grab3["data"]["orderNo"]

_, r = call("POST", f"/seckill/orders/{poor_order_no}/pay", lisi)
check("积分不足 -> 16002", r["code"] == POINTS_NOT_ENOUGH, f"code={r['code']} {r['message']}")

poor_activity = admin_activity(admin, poor_id)
check("订单仍是待支付（锁定库存未回补）", poor_activity["lockedStock"] == 1,
      f"locked={poor_activity['lockedStock']}")

# ---------------------------------------------------------------- 取消
section("7. 取消：回补库存，且取消后不能重抢")
_, r = call("POST", f"/seckill/orders/{poor_order_no}/cancel", lisi)
check("取消成功", r["code"] == 0 and r["data"]["statusName"] == "已取消", r)

poor_activity = admin_activity(admin, poor_id)
check("库存回到可售", poor_activity["availableStock"] == 1 and poor_activity["lockedStock"] == 0,
      f"available={poor_activity['availableStock']} locked={poor_activity['lockedStock']}")

_, r = call("POST", f"/seckill/{poor_id}/grab", lisi)
check("取消后再次抢购 -> 15002（资格不退还，而不是重放那张已取消的单）",
      r["code"] == SECKILL_ALREADY_JOINED, f"code={r['code']} {r['message']}")

_, r = call("POST", f"/seckill/orders/{poor_order_no}/cancel", lisi)
check("重复取消 -> 15013", r["code"] == ORDER_STATUS_ILLEGAL, f"code={r['code']} {r['message']}")

# ---------------------------------------------------------------- 并发一人一单
section("8. 并发一人一单：同一用户 10 个并发请求只产生一张单")
conc_id = create_activity(admin, "冒烟·并发", 10, 60, text(-1), text(30))
call("POST", f"/admin/seckill/activities/{conc_id}/warmup", admin)

results = []
lock = threading.Lock()


def racer():
    _, resp = call("POST", f"/seckill/{conc_id}/grab", zhaoliu)
    with lock:
        results.append(resp)


threads = [threading.Thread(target=racer) for _ in range(10)]
for t in threads:
    t.start()
for t in threads:
    t.join()

ok = [r for r in results if r["code"] == 0]
rejected = [r for r in results if r["code"] != 0]
unique = {r["data"]["orderNo"] for r in ok}
check("成功的请求拿到同一个单号（幂等重放）", len(unique) == 1, f"成功 {len(ok)} 个，订单号 {unique}")
check("被拒的请求都是限流（10006），不是别的错",
      all(r["code"] == TOO_MANY_REQUESTS for r in rejected),
      [r["code"] for r in rejected])

# 并发段不能再固定 sleep 2 秒：消息要经 outbox → Kafka → 消费者，
# 落库时刻不由抢购接口决定。轮询到出现为止才是稳的
db_orders = []
deadline = time.time() + 20
while time.time() < deadline:
    _, r = call("GET", "/admin/seckill/orders?page=1&size=50", admin)
    # 只按活动过滤。并发段的活动是本用例专有的，「这个活动下有且仅有一单」
    # 正是要断言的东西；附加 userId 条件反而容易写错——注意 zhaoliu 是**令牌**，
    # 不是用户 ID，拿它去比 userId 永远为假
    db_orders = [o for o in r["data"]["list"] if o["activityId"] == conc_id]
    if db_orders:
        break
    time.sleep(1)
check("数据库里只有 1 张订单", len(db_orders) == 1, db_orders)

conc_activity = admin_activity(admin, conc_id)
check("只扣了 1 件库存", conc_activity["availableStock"] == 9,
      f"available={conc_activity['availableStock']}")

# ---------------------------------------------------------------- 超时
section("9. 超时未支付：自动关闭并回补库存（含延迟消息）")

# 抢购限流是「单用户 10 秒 5 次」，上面刚打完 10 次，先等这个窗口过去，
# 否则这一节会拿到 10006 而不是抢购结果
print("  （等待抢购限流窗口过去，约 11 秒）")
time.sleep(11)

timeout_id = create_activity(admin, "冒烟·超时", 1, 60, text(-1), text(30), pay_timeout=20)
call("POST", f"/admin/seckill/activities/{timeout_id}/warmup", admin)
grab4, _ = grab_and_wait(zhaoliu, timeout_id)
check("抢购成功", grab4["code"] == 0, grab4)
timeout_order_no = grab4["data"]["orderNo"]

deadline = time.time() + 90
status_name = None
while time.time() < deadline:
    time.sleep(5)
    orders = my_orders(zhaoliu)
    hit = [o for o in orders if o["orderNo"] == timeout_order_no]
    if hit and hit[0]["statusName"] == "超时关闭":
        status_name = hit[0]["statusName"]
        break
check("订单被自动关闭为「超时关闭」", status_name == "超时关闭", f"status={status_name}")

timeout_activity = admin_activity(admin, timeout_id)
check("库存已回补到可售", timeout_activity["availableStock"] == 1
      and timeout_activity["lockedStock"] == 0,
      f"available={timeout_activity['availableStock']} locked={timeout_activity['lockedStock']}")

_, r = call("POST", f"/seckill/{timeout_id}/grab", zhaoliu)
check("超时后也不能重抢 -> 15002", r["code"] == SECKILL_ALREADY_JOINED,
      f"code={r['code']} {r['message']}")

# ---------------------------------------------------------------- 权限
section("10. 权限边界：普通用户碰管理接口")
for method, path, body in [
    ("GET", "/admin/seckill/activities?page=1&size=20", None),
    ("POST", "/admin/seckill/activities", {"goodsId": 1, "name": "越权", "pointsCost": 1,
                                           "totalStock": 1, "startTime": text(1),
                                           "endTime": text(2)}),
    ("POST", f"/admin/seckill/activities/{unwarmed_id}/warmup", None),
    ("GET", "/admin/seckill/orders?page=1&size=20", None),
]:
    _, r = call(method, path, lisi, body)
    check(f"普通用户 {method} {path.split('?')[0]} -> 10003", r["code"] == FORBIDDEN,
          f"code={r['code']} {r['message']}")

_, r = call("GET", "/seckill/activities")
check("未登录访问用户侧接口 -> 10002", r["code"] == 10002, f"code={r['code']} {r['message']}")

# ---------------------------------------------------------------- 用户侧列表
section("11. 用户侧活动列表")
_, r = call("GET", "/seckill/activities", wangwu)
ids = [a["id"] for a in r["data"]]
check("新建的活动出现在列表里", unwarmed_id in ids, ids)

print(f"\n{'=' * 46}\nPASS={PASS}  FAIL={FAIL}\n{'=' * 46}")
sys.exit(1 if FAIL else 0)
