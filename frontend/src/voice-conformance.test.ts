import { describe, expect, it } from 'vitest'

/**
 * 前端文案守门——把 `VOICE.md` 第七节的**可机器判定三条**覆盖到 `frontend/src`。
 *
 * <p>对应关系：后端 `VoiceConformanceTest` 守 Java 侧的 `*Messages`，本测试守 Vue/TS 侧。
 * 判据（与后端同源，只判「客观可判」的三条，语气仍交评审）：
 * <ol>
 *   <li><b>无模板符号</b>（`<` `>` `|`）——会被运营者整串照抄，或渲染成看不懂的东西；</li>
 *   <li><b>无未填占位符</b>（`{0}`–`{9}`、`XXX`、`TODO`、`FIXME`）；</li>
 *   <li><b>无 Markdown 标记</b>（`**`）——本控制台走**纯文本渲染**（全仓无 `v-html`、
 *       不设 parseMode），`**` 不会被解析成加粗，只会原样显示成两个星号。</li>
 * </ol>
 *
 * <p><b>只扫「用户可见」的中文字面量</b>：注释行（`//`、`*`、`/*`）整体跳过——
 * 本仓注释大量用中文，不排除会立刻误报；再用「中文字面量」把 CSS 类名、枚举值
 * 这类非文案字符串滤掉。
 *
 * <p><b>为什么用 `import.meta.glob` 而不是 `node:fs`</b>：本仓前端 tsconfig 的
 * `types` 只含 `vite/client`（不含 node），用 `node:fs` 会让 `vue-tsc --noEmit` 直接报
 * 「Cannot find module 'node:fs'」——即 `pnpm build` 变红。Vite 的 glob（`eager` + `?raw`）
 * 在构建期就把源文件文本注入，既拿到内容又不引 node 类型。
 *
 * <p><b>取舍与边界（如实记录）</b>：与后端守门同款——靠正则匹配「引号里的字面量」，
 * 覆盖不到运行时拼接出的中文（如 `'未找到' + id`）；那类靠评审。
 */
const SOURCES = import.meta.glob(['./**/*.{ts,vue}', '!./**/*.test.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const CJK = /[\u4e00-\u9fff]/
const TEMPLATE_MARK = /[<>|]/
const PLACEHOLDER = /\{\d\}|XXX|TODO|FIXME/
/** Markdown 加粗/标题：本控制台纯文本渲染，这些不会被解析。 */
const MARKDOWN = /\*\*|^\s*#{1,6}\s/

/** 注释行里的中文不是文案（本仓注释大量用中文，不排除必误报）。 */
function isComment(line: string): boolean {
  const t = line.trim()
  return t.startsWith('*') || t.startsWith('//') || t.startsWith('/*')
}

/** 提取一行里的字符串字面量内容（`'…'` / `"…"` / `` `…` ``）。 */
function literals(line: string): string[] {
  const out: string[] = []
  const re = /'([^'\\]*(?:\\.[^'\\]*)*)'|"([^"\\]*(?:\\.[^"\\]*)*)"|`([^`\\]*(?:\\.[^`\\]*)*)`/g
  let m: RegExpExecArray | null
  while ((m = re.exec(line)) !== null) {
    out.push(m[1] ?? m[2] ?? m[3] ?? '')
  }
  return out
}

describe('前端文案守门（VOICE.md 第七节 · 可机器判定）', () => {
  it('用户可见中文字面量不含模板符号 / 未填占位符 / Markdown 标记', () => {
    const offenders: string[] = []
    for (const [file, content] of Object.entries(SOURCES)) {
      content.split('\n').forEach((line: string, i: number) => {
        if (isComment(line)) {
          return
        }
        for (const text of literals(line)) {
          if (!CJK.test(text)) {
            continue
          }
          if (TEMPLATE_MARK.test(text) || PLACEHOLDER.test(text) || MARKDOWN.test(text)) {
            offenders.push(`${file}:${i + 1} → ${text}`)
          }
        }
      })
    }
    expect(offenders, offenders.join('\n')).toEqual([])
  })
})
