import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 论坛的正文允许 Markdown 语法，也允许内嵌 HTML。这两件事组合起来就是
// 一个存储型 XSS 的入口：有人发一篇带 <img onerror=...> 的帖子，
// 所有读到它的人都会中招。DOMPurify 在这里不是可选项。
marked.setOptions({ breaks: true, gfm: true })

/** 把 Markdown 渲染成可以直接 v-html 的 HTML */
export function renderMarkdown(source) {
  if (!source) {
    return ''
  }
  return DOMPurify.sanitize(marked.parse(source))
}
