#!/usr/bin/env bash
# 敏感信息扫描：在**入库文件**里找密钥 / 凭据 / 私钥。
#
# 退出码：0 = 干净；1 = 发现可疑（CI 应失败）；2 = 用法或环境错误。
#
# 设计要点（改之前请先读）：
#   * 只扫 `git ls-files` 的**入库文件**——未入库的本地资产（`docs/`、`.env`、`.rivet/`）
#     不进 CI，扫它们只会制造噪声。要扫任意目录，显式传路径参数（自测用）。
#   * **allowlist 与 pattern 分离**：豁免写在 `secret-scan-allow.txt`（逐条给出理由），
#     而不是把正则改宽——改宽会让真正的新密钥也漏报。
#   * 每条命中都打印 file:line 与**脱敏后的**片段：日志里不留明文密钥。
#
# 用法：
#   tools/security/scan_secrets.sh              # 扫入库文件（CI 用）
#   tools/security/scan_secrets.sh <path>...    # 扫指定路径（自测用）

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ALLOW_FILE="$REPO_ROOT/tools/security/secret-scan-allow.txt"

# ── 扫描模式：每条 = 名字 + ERE ────────────────────────────────────────────────
# 顺序即优先级；命中即报，不做去重合并（宁可多报一条，也不漏一条）。
PATTERNS=(
  # Telegram bot token 的唯一格式 <数字ID>:<35 位 base64url>——高置信，几乎不误报
  "telegram-bot-token|[0-9]{8,12}:[A-Za-z0-9_-]{35}"
  # 任何私钥块（PEM）
  "private-key-block|-----BEGIN [A-Z ]*PRIVATE KEY-----"
  # 长 token 赋值给 key/secret/token 类变量。
  # ⚠️ **右侧不要求引号**：YAML / .properties / .env 里最常见的形态就是无引号赋值
  #    （`password: hunter2…`），要求引号会**静默漏掉这一整类**——实测漏报过一次（2026-09-19 审查发现）。
  #    排除占位符不靠引号，靠「${...}` / `<...>` 里的字符不在字符集内，且阈值 20 位。
  "hardcoded-credential|(password|passwd|secret|token|api[_-]?key|private[_-]?key)[\"']?[[:space:]]*[:=][[:space:]]*[\"']?[A-Za-z0-9+/=_-]{20,}"
  # AWS 风格 AK
  "aws-access-key|AKIA[0-9A-Z]{16}"
)

fail=0

collect_files() {
  if [ "$#" -gt 0 ]; then
    find "$@" -type f -not -path '*/.git/*' 2>/dev/null
  else
    (cd "$REPO_ROOT" && git ls-files)
  fi
}

is_allowed() {
  # $1=相对路径 $2=pattern 名；allow 文件格式：<pattern名> <路径子串> [# 理由]
  # ⚠️ 必须要求 NF>=2 且路径列非空：否则漏写第二列时 `index(f,"")` 恒为 1，
  #    会**静默豁免该 pattern 的全部命中**（把门禁关掉却看不出来）。
  [ -f "$ALLOW_FILE" ] || return 1
  grep -vE '^[[:space:]]*(#|$)' "$ALLOW_FILE" 2>/dev/null |
    awk -v p="$2" -v f="$1" 'NF>=2 && $1==p && $2!="" && index(f,$2)>0 {found=1} END{exit !found}'
}

report() {  # $1=路径 $2=行号 $3=pattern名 $4=命中片段（脱敏）
  printf '  [%s] %s:%s\n      %s\n' "$3" "$1" "$2" "$4"
  fail=1
}

mask() {  # 只留前 6 个字符，其余打码
  local s="$1"
  printf '%s…（已脱敏，共 %s 字符）' "$(printf '%s' "$s" | cut -c1-6)" "${#s}"
}

main() {
  local files
  files=$(collect_files "$@")
  [ -z "$files" ] && { echo "scan_secrets: 没有可扫描的文件" >&2; exit 2; }

  echo "scan_secrets: 扫描 $(printf '%s\n' "$files" | wc -l | tr -d ' ') 个文件，模式 ${#PATTERNS[@]} 条"

  local f name re line_no line hit
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    for entry in "${PATTERNS[@]}"; do
      name="${entry%%|*}"; re="${entry#*|}"
      # 二进制文件跳过（图片/class/jar）
      case "$f" in *.png|*.jpg|*.jar|*.class|*.ico|*.woff*|*.lock) continue;; esac
      while IFS=: read -r line_no line; do
        [ -z "${line:-}" ] && continue
        hit=$(printf '%s' "$line" | grep -oE "$re" | head -1)
        [ -z "$hit" ] && continue
        is_allowed "$f" "$name" && continue
        report "$f" "$line_no" "$name" "$(mask "$hit")"
      done < <(grep -nE "$re" "$f" 2>/dev/null)
    done
  done <<< "$files"

  if [ "$fail" -eq 0 ]; then
    echo "scan_secrets: ✓ 未发现可疑凭据"
    exit 0
  fi
  echo "scan_secrets: ✗ 发现可疑凭据（确认是误报请加入 $ALLOW_FILE，不要改宽正则）" >&2
  exit 1
}

main "$@"
