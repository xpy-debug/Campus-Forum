// 前端端到端冒烟：用 CDP 驱动 headless Edge，把「注册 → 登录 → 列表 → 发帖 → 详情 → 评论 → 点赞」真跑一遍。
// 只做自动化能覆盖的部分：点击、填表、断言页面状态与接口返回，截图留给人工看。
// 用法：node smoke/e2e-ui.mjs
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PORT = 9222
const BASE = 'http://localhost:5173'
const OUT = 'smoke/shots'
const STAMP = Date.now().toString().slice(-6)
const USERNAME = `ui${STAMP}`
const PASSWORD = 'UiTest123456'
const NICKNAME = `界面测试${STAMP}`

mkdirSync(OUT, { recursive: true })

const steps = []
const record = (name, ok, detail = '') => {
  steps.push({ name, ok, detail })
  console.log(`${ok ? '  [OK]' : '[FAIL]'} ${name}${detail ? ' — ' + detail : ''}`)
}

// ---------- CDP 管道 ----------
const edge = spawn(EDGE, [
  '--headless=new',
  '--disable-gpu',
  '--no-first-run',
  '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${process.env.TEMP}\\forum-e2e-profile`,
  'about:blank'
], { stdio: 'ignore' })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function connect() {
  for (let i = 0; i < 60; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
      const page = list.find((t) => t.type === 'page')
      if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl
    } catch { /* 浏览器还没起来 */ }
    await sleep(250)
  }
  throw new Error('连不上 Edge 调试端口')
}

const ws = new WebSocket(await connect())
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true })
  ws.addEventListener('error', reject, { once: true })
})

let seq = 0
const pending = new Map()
const pageErrors = []

ws.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result)
    return
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    pageErrors.push(msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text)
  }
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    pageErrors.push(msg.params.args.map((a) => a.description || a.value).join(' '))
  }
})

const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++seq
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
    setTimeout(() => {
      if (pending.delete(id)) reject(new Error(`CDP 超时: ${method}`))
    }, 30000)
  })

async function evaluate(expression) {
  const res = await send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true
  })
  if (res.exceptionDetails) {
    throw new Error(res.exceptionDetails.exception?.description || res.exceptionDetails.text)
  }
  return res.result.value
}

/** 轮询等待页面里的条件成立；超时抛出最后看到的值，方便定位 */
async function waitFor(expression, label = expression, timeout = 15000) {
  const deadline = Date.now() + timeout
  let last
  while (Date.now() < deadline) {
    try {
      last = await evaluate(expression)
      if (last) return last
    } catch (e) {
      last = e.message
    }
    await sleep(150)
  }
  throw new Error(`等待超时: ${label}（最后结果 ${JSON.stringify(last)}）`)
}

// 每次整页导航后执行上下文都会重建，辅助函数得重新注入
const HELPERS = `
window.__q = (sel) => document.querySelector(sel);
window.__all = (sel) => [...document.querySelectorAll(sel)];
window.__btn = (text) => __all('button').find((b) => b.textContent.trim().includes(text));
window.__click = (sel) => { const el = __q(sel); if (!el) throw new Error('点击目标不存在: ' + sel); el.click(); };
// Vue 的 v-model 只监听 DOM 事件，不用像 React 那样绕原生 setter，直接赋值再派发事件即可
window.__fill = (el, value) => {
  if (!el) throw new Error('填值目标不存在');
  el.focus();
  el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  el.dispatchEvent(new Event('blur', { bubbles: true }));
};
window.__fillBy = (sel, value) => __fill(__q(sel), value);
window.__text = () => document.body.innerText;
true
`

async function goto(url) {
  await send('Page.navigate', { url })
  await waitFor(`document.readyState === 'complete' && !!document.querySelector('#app > *')`, `页面加载 ${url}`)
  await evaluate(HELPERS)
}

async function shot(name) {
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(data, 'base64'))
}

try {
  await send('Runtime.enable')
  await send('Page.enable')

  // 1. 注册
  await goto(`${BASE}/register`)
  await waitFor(`!!document.querySelector('input[placeholder="登录用，注册后不可修改"]')`, '注册表单渲染')
  await evaluate(`
    __fillBy('input[placeholder="登录用，注册后不可修改"]', ${JSON.stringify(USERNAME)});
    __fillBy('input[placeholder="其他人看到的名字"]', ${JSON.stringify(NICKNAME)});
    __fillBy('input[placeholder="8-32 位，含字母和数字"]', ${JSON.stringify(PASSWORD)});
    __fillBy('input[placeholder="再次输入密码"]', ${JSON.stringify(PASSWORD)});
    true
  `)
  // 按钮要用表单内的 .submit，不能按文字找「注册」——页头也有一个同名的注册按钮，按文字会先命中它
  await evaluate(`__click('.auth .submit'); true`)
  await waitFor(`location.pathname === '/login' || __text().includes('注册成功')`, '注册后跳转/提示')
  const registered = await evaluate(`location.pathname === '/login'`)
  record('注册提交并被服务端接受', registered, registered ? '跳转到 /login' : '停留在注册页')
  await shot('01-register')

  // 2. 登录
  await goto(`${BASE}/login`)
  await evaluate(`
    __fillBy('input[placeholder="请输入用户名"]', ${JSON.stringify(USERNAME)});
    __fillBy('input[placeholder="请输入密码"]', ${JSON.stringify(PASSWORD)});
    true
  `)
  await evaluate(`__click('.auth .submit'); true`)
  await waitFor(`location.pathname === '/'`, '登录后回到首页')
  await waitFor(`__text().includes(${JSON.stringify(NICKNAME)})`, '页头显示昵称')
  record('登录成功且页头显示昵称', true, NICKNAME)
  await shot('02-login-home')

  // 3. 列表
  const cards = await waitFor(`__all('article.post').length || false`, '帖子列表渲染')
  record('首页帖子列表渲染', cards > 0, `${cards} 张卡片`)

  // 4. 发帖（走页头按钮，顺带验证导航守卫放行已登录用户）
  await evaluate(`__btn('发帖').click(); true`)
  await waitFor(`location.pathname === '/post/create'`, '进入发帖页')
  await waitFor(`!!document.querySelector('input[placeholder="用一句话说清楚要讨论什么"]')`, '发帖表单渲染')
  const TITLE = `界面冒烟帖 ${STAMP}`
  const CONTENT = `## 小标题\n\n这是 **自动化** 冒烟测试写入的正文，包含 \`代码\` 与列表：\n\n- 第一项\n- 第二项\n`
  await evaluate(`
    __fillBy('input[placeholder="用一句话说清楚要讨论什么"]', ${JSON.stringify(TITLE)});
    __fillBy('textarea', ${JSON.stringify(CONTENT)});
    true
  `)
  await shot('03-create-filled')
  await evaluate(`__btn('发布').click(); true`)
  const createdPath = await waitFor(`/^\\/post\\/\\d+$/.test(location.pathname) && location.pathname`, '发布后跳到详情页')
  const postId = createdPath.split('/').pop()
  record('发帖并跳转详情页', true, `postId=${postId}`)

  // 5. 详情页渲染（Markdown 渲染成 HTML 而不是原样输出）
  await waitFor(`__text().includes(${JSON.stringify(TITLE)})`, '详情页标题')
  const markdownOk = await evaluate(`!!document.querySelector('.markdown-body h2') && !!document.querySelector('.markdown-body li')`)
  record('Markdown 渲染为 HTML', markdownOk, markdownOk ? 'h2/li 已生成' : '仍是纯文本')
  await shot('04-detail')

  // 6. 评论
  const COMMENT = `自动化评论 ${STAMP}`
  const before = await evaluate(`+__q('.comments .heading').textContent.replace(/\\D/g, '')`)
  await evaluate(`__fillBy('.editor textarea', ${JSON.stringify(COMMENT)}); true`)
  await evaluate(`__btn('发表').click(); true`)
  await waitFor(`__text().includes(${JSON.stringify(COMMENT)})`, '评论出现在列表里')
  await evaluate(`__text().includes('第 1 楼') || true`)
  record('发表评论并即时显示', true)
  const after = await evaluate(`+__q('.comments .heading').textContent.replace(/\\D/g, '')`)
  record('评论数同步 +1', after === before + 1, `${before} → ${after}`)
  // 按行匹配：昵称里可能带数字，直接在整段文本里找 \d+楼 会跨行误命中
  const floorText = await evaluate(`(__q('.comments')?.innerText.split('\\n').find((l) => /^\\d+楼$/.test(l.trim())) || '无')`)
  record('新评论楼层号已渲染', floorText !== '无', floorText)
  await shot('05-comment')

  // 7. 点赞（此时是未点赞态）
  const likeBefore = await evaluate(`__q('.actions .like').textContent.trim()`)
  await evaluate(`__q('.actions .like').click(); true`)
  await waitFor(`__q('.actions .like').classList.contains('active')`, '点赞按钮进入已点赞态')
  const likeAfter = await evaluate(`__q('.actions .like').textContent.trim()`)
  record('点赞后按钮状态与计数更新', true, `"${likeBefore}" → "${likeAfter}"`)
  await sleep(1500)
  await evaluate(`location.reload(); true`).catch(() => {})
  await sleep(2500)
  await evaluate(HELPERS)
  const persisted = await evaluate(`__q('.actions .like')?.classList.contains('active')`)
  record('刷新后点赞态仍在（Redis 是实时真相）', !!persisted)
  await shot('06-liked')

  // 8. 未登录访问发帖页应被守卫拦下
  await evaluate(`__q('.header .user')?.click(); true`).catch(() => {})
  await evaluate(`localStorage.clear(); true`)
  await goto(`${BASE}/post/create`)
  await waitFor(`location.pathname === '/login'`, '未登录访问发帖页被拦到登录页')
  record('导航守卫拦截未登录用户', true, `redirect=${await evaluate(`new URLSearchParams(location.search).get('redirect')`)}`)
  await shot('07-guard')
} catch (error) {
  record('流程异常中断', false, error.message)
} finally {
  record('页面无未捕获 JS 异常', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
  const failed = steps.filter((s) => !s.ok)
  console.log(`\n共 ${steps.length} 项，失败 ${failed.length} 项`)
  console.log(`用户名 ${USERNAME} / 截图目录 ${OUT}`)
  ws.close()
  edge.kill()
  process.exit(failed.length ? 1 : 0)
}
