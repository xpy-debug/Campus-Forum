#!/bin/bash
# ============================================================================
# 手工组装镜像（国内网络环境兜底方案），默认目标为 apache/kafka
# ============================================================================
# 为什么需要这个脚本：
#
# docker pull apache/kafka:3.7.0 在国内镜像源上稳定失败：
#   failed to copy: httpReadSeeker: failed open: failed to do request:
#   Get "https://cloudfront-docker-cf.mrs.1ms.run/.../blobs/sha256/fe/fe7e6a65.../data" : EOF
#
# 排查过程：
#   1. 换源无效 —— docker.1ms.run / hub.rat.dev / docker.1panel.live 最终都 302 到
#      同一个后端 cloudfront-docker-cf.mrs.1ms.run，换的只是门面。
#   2. 拉 manifest 分析分层后发现，失败的 blob 不是 200MB 的大层，
#      而是 7.5KB 的 config 文件（镜像元数据 JSON）。
#   3. 用 curl 请求同一个 URL —— 秒下，内容完全正确。
#
# 结论：不是网络问题，也不是 CDN 缺这个对象，而是 docker 客户端读取 blob 的实现
# （registry 的 httpReadSeeker）与这家 CDN 的响应不兼容。镜像层的下载走的是另一条
# 代码路径，所以 200MB 的层反而能正常下载。
#
# 对策：绕过 docker 的 blob 读取逻辑 —— 用 curl 把各 blob 下全，按 docker-archive
# 格式打包，再 docker load 进本地。curl 有重试和断点续传，比 docker 客户端健壮得多。
# ============================================================================
set -u

# 目标镜像可用环境变量覆盖，默认是 Kafka。
# 同一个 CDN 问题会影响所有镜像，所以这个脚本也用来构建别的拉不下来的镜像，例如：
#   IMG=provectuslabs/kafka-ui TAG=latest TARGET=forum/kafka-ui:latest \
#   WORK=/c/work/school/docker/kafka-ui-image bash build-kafka-image.sh
IMG="${IMG:-apache/kafka}"
TAG="${TAG:-3.7.0}"
MIRROR="${MIRROR:-https://docker.1ms.run}"
TARGET="${TARGET:-forum/kafka:3.7.0}"
WORK="${WORK:-/c/work/school/docker/kafka-image}"

mkdir -p "$WORK/layers"
cd "$WORK" || exit 1

get_token() {
    curl -s -m 20 "$MIRROR/openapi/v1/auth/token?service=docker.1ms.run&scope=repository:$IMG:pull" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

TOKEN=$(get_token)
if [ ${#TOKEN} -lt 100 ]; then
    echo "!! 获取 token 失败"
    exit 1
fi
echo "token 获取成功 (${#TOKEN} 字符)"

# ---------- 1. 取 amd64 子 manifest ----------
echo ""
echo "=== [1/5] 拉取 amd64 manifest ==="
# 先取 index，找到 amd64 的 digest
curl -sL -m 30 -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json" \
    "$MIRROR/v2/$IMG/manifests/$TAG" -o index.json -w 'index http=%{http_code}\n'

AMD64_DIGEST=$(python -c "
import json
d = json.load(open('index.json'))
for m in d.get('manifests', []):
    p = m.get('platform', {})
    if p.get('os') == 'linux' and p.get('architecture') == 'amd64':
        print(m['digest']); break
")
if [ -z "$AMD64_DIGEST" ]; then echo "!! 找不到 amd64 manifest"; exit 1; fi
echo "amd64 manifest: $AMD64_DIGEST"

curl -sL -m 30 -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.oci.image.manifest.v1+json" \
    "$MIRROR/v2/$IMG/manifests/$AMD64_DIGEST" -o manifest-amd64.json -w 'manifest http=%{http_code}\n'

# ---------- 2. 下载 config ----------
echo ""
echo "=== [2/5] 下载 config blob ==="
CONFIG_DIGEST=$(python -c "import json;print(json.load(open('manifest-amd64.json'))['config']['digest'])")
CONFIG_SIZE=$(python -c "import json;print(json.load(open('manifest-amd64.json'))['config']['size'])")
echo "config: $CONFIG_DIGEST ($CONFIG_SIZE 字节)"

curl -sL -m 60 --retry 10 --retry-all-errors --retry-delay 2 \
    -H "Authorization: Bearer $TOKEN" \
    "$MIRROR/v2/$IMG/blobs/$CONFIG_DIGEST" -o config.json \
    -w 'config http=%{http_code} size=%{size_download}\n'

# ---------- 3. 下载所有层 ----------
echo ""
echo "=== [3/5] 下载镜像层（分块并发） ==="
python -c "
import json
m = json.load(open('manifest-amd64.json'))
# newline='\n' 是必须的：Windows 上的 Python 默认写 CRLF，而行尾的 \r 会被
# bash 的 read 吃进最后一个变量里，导致后续算术运算报
# 'invalid arithmetic operator' 并中断整个循环。
with open('layer-digests.txt', 'w', newline='\n') as f:
    for l in m['layers']:
        f.write(l['digest'] + ' ' + str(l['size']) + '\n')
print('layers:', len(m['layers']))
print('total: %.1f MB' % (sum(l['size'] for l in m['layers'])/1048576))
"

# ============================================================================
# 为什么要分块并发下载，而不是一条 curl 拉完整个层：
#
# 实测这个 CDN 对单条连接限速在 ~150 KB/s，但不对总带宽设限——
# 4 条并发连接聚合能到 ~710 KB/s，接近线性叠加。
# 最快的层有 122 MB，单连接要跑十几分钟，并发后只要一两分钟。
#
# 用 Range 请求（HTTP 206）分块，每个块独立重试、独立续传：
# 某一块断了只重下这一块，不会把已经下了 100 MB 的进度一起丢掉。
# ============================================================================
CONCURRENCY=8                              # 并发连接数
CHUNK=$(( 4 * 1024 * 1024 ))               # 每块 4 MB

refresh_token() {
    TOKEN=$(get_token)
    echo "    （token 已刷新）"
}

# 下载 [start, end] 区间到 $out。块内支持续传，失败自动重试。
fetch_chunk() {
    local digest="$1" start="$2" end="$3" out="$4"
    local want=$(( end - start + 1 ))
    local attempt=0
    while [ "$attempt" -lt 8 ]; do
        attempt=$(( attempt + 1 ))
        local have=0
        [ -f "$out" ] && have=$(stat -c %s "$out" 2>/dev/null || echo 0)
        [ "$have" -ge "$want" ] && return 0
        # 续传：从「起点 + 已有字节数」接着下
        local from=$(( start + have ))
        if [ "$have" -eq 0 ]; then
            curl -sL -m 600 --retry 5 --retry-all-errors --retry-delay 2 \
                -r "${from}-${end}" -H "Authorization: Bearer $TOKEN" \
                "$MIRROR/v2/$IMG/blobs/$digest" -o "$out" 2>/dev/null </dev/null
        else
            curl -sL -m 600 --retry 5 --retry-all-errors --retry-delay 2 \
                -r "${from}-${end}" -H "Authorization: Bearer $TOKEN" \
                "$MIRROR/v2/$IMG/blobs/$digest" >> "$out" 2>/dev/null </dev/null
        fi
        # 重试到第 4 次时换个新 token：镜像源的 token 有效期通常只有几分钟，
        # 大层下载中途过期会表现为「一直 401」，光重试是没用的。
        [ "$attempt" -eq 4 ] && refresh_token
        sleep 1
    done
    return 1
}

mkdir -p chunks
i=0
while read -r digest size; do
    size="${size//[$'\r\n']/}"   # 双保险：无论文件怎么写的都清掉行尾空白
    out="layers/layer${i}.blob"
    if [ -f "$out" ] && [ "$(stat -c %s "$out")" = "$size" ]; then
        echo "  layer[$i] 已存在且大小正确，跳过"
        i=$((i+1)); continue
    fi
    rm -f "$out"

    n=$(( (size + CHUNK - 1) / CHUNK ))
    echo "  layer[$i] ($(( size / 1048576 )) MB, 分 $n 块, 并发 $CONCURRENCY) ..."
    rm -rf "chunks/$i"; mkdir -p "chunks/$i"

    for (( c=0; c<n; c++ )); do
        s=$(( c * CHUNK ))
        e=$(( s + CHUNK - 1 ))
        [ "$e" -ge "$size" ] && e=$(( size - 1 ))
        fetch_chunk "$digest" "$s" "$e" "chunks/$i/part$(printf '%04d' "$c")" &
        # 控制并发数：满了就等，直到有槽位空出来
        while [ "$(jobs -rp | wc -l)" -ge "$CONCURRENCY" ]; do sleep 0.3; done
    done
    wait
    # part 文件名零填充过，字典序 == 数字序，拼出来的顺序才是对的
    cat "chunks/$i"/part* > "$out"
    rm -rf "chunks/$i"
    i=$((i+1))
done < layer-digests.txt
rmdir chunks 2>/dev/null || true

# ---------- 4. 校验 ----------
echo ""
echo "=== [4/5] 校验 sha256 ==="
FAIL=0
check() {
    local file="$1" expect="$2" label="$3"
    local actual
    actual="sha256:$(sha256sum "$file" | cut -d' ' -f1)"
    if [ "$actual" = "$expect" ]; then
        echo "  OK   $label"
    else
        echo "  失败 $label"
        echo "       期望 $expect"
        echo "       实际 $actual"
        FAIL=1
    fi
}
check config.json "$CONFIG_DIGEST" "config"

i=0
while read -r digest size; do
    size="${size//[$'\r\n']/}"
    check "layers/layer${i}.blob" "$digest" "layer[$i]"
    i=$((i+1))
done < layer-digests.txt

if [ "$FAIL" != "0" ]; then
    echo ""
    echo "!! 校验失败，中止。删掉 kafka-image/layers 下的文件后重跑本脚本。"
    exit 1
fi

# ---------- 5. 组装 docker-archive 并 load ----------
echo ""
echo "=== [5/5] 组装镜像并导入 ==="
rm -rf build && mkdir -p build
cp config.json build/config.json

# 解压层：docker-archive 的传统格式要求层是未压缩的 tar。
# 虽然新版 docker 能自动识别 gzip，但显式解压能让 diff_id 校验路径最确定。
i=0
LAYER_JSON=""
while read -r digest size; do
    size="${size//[$'\r\n']/}"
    echo "  解压 layer[$i] ..."
    gzip -dc "layers/layer${i}.blob" > "build/layer${i}.tar" || {
        echo "!! layer[$i] 解压失败"; exit 1;
    }
    if [ -n "$LAYER_JSON" ]; then LAYER_JSON="$LAYER_JSON, "; fi
    LAYER_JSON="${LAYER_JSON}\"layer${i}.tar\""
    i=$((i+1))
done < layer-digests.txt

cat > build/manifest.json <<EOF
[{
  "Config": "config.json",
  "RepoTags": ["$TARGET"],
  "Layers": [$LAYER_JSON]
}]
EOF

echo "  打包 tar ..."
(cd build && tar -cf ../kafka-image.tar manifest.json config.json layer*.tar)

echo "  导入中（约 500MB，需要一两分钟）..."
if docker load -i kafka-image.tar; then
    echo ""
    echo "=== 成功 ==="
    docker images "$TARGET"
    echo ""
    echo "可以删除中间文件释放空间： rm -rf $WORK"
else
    echo ""
    echo "!! docker load 失败"
    exit 1
fi
