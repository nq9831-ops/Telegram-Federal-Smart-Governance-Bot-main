package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.core.moderation.ModerationRule;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 按群教学规则服务（V5.0 §10.3 的 {@code /teach} 落点）。
 *
 * <p><b>它解决什么</b>：模块九的规则此前**硬编码在内存**（{@code BuiltInRules}），
 * 于是「管理员发现新骗局 → 让它生效」这条路根本不存在——只能改代码重新发版。
 * 本服务把「本群可增删的规则」落进库（V8 表），并给出<b>热路径可用的读接口</b>。
 *
 * <p><b>热路径与缓存的取舍</b>：{@code rulesFor} 在每条消息上被调用，因此**必须**缓存
 * ——本项目已有「热路径每条消息查一次库」的既有缺陷（{@code KNOWN-ISSUES} P2#1 的违禁词检测），
 * 这里不再重犯。缓存为进程内 TTL（{@link #CACHE_TTL}），写操作后**立即失效**
 * （所以「教完立刻生效」而不是等 TTL；TTL 只是兜底，防止无写操作时的陈旧）。
 *
 * <p><b>为什么不用 Redis</b>：V5.0 §10.3 提到刷新 Redis，但本项目未引 Redis（限流走内存实现）。
 * 为单一缓存引入一个基础设施依赖不划算；多实例部署时的一致性属部署侧课题（记入 KNOWN-ISSUES）。
 *
 * <p><b>安全：正则来自管理员，但热路径要吃它</b>——因此入库前必须过三道闸：
 * ① 能编译；② 长度受限（{@link #MAX_REGEX_LENGTH}）；③ 拒绝灾难性回溯形态（{@link #containsNestedQuantifier(String)}）。
 * 少了第三道，一条 {@code (a+)+$} 就能让机器人卡死在一条消息上——那是 DoS，不只是"规则写错了"。
 */
public class TaughtRuleService {

    private static final Logger log = LoggerFactory.getLogger(TaughtRuleService.class);

    /** 正则长度上限（与 {@code moderation_rules.regex} 列宽一致）。 */
    public static final int MAX_REGEX_LENGTH = 512;
    /** 规则 id 长度上限（与列宽一致）。 */
    public static final int MAX_RULE_ID_LENGTH = 64;
    /** 规则描述长度上限（与列宽一致）。 */
    public static final int MAX_NAME_LENGTH = 128;

    /** 缓存 TTL：V5.0 §10.3 要求「本群 5-10 秒内生效」，故取 10 秒为兜底上限（写后立即失效，通常无感）。 */
    public static final Duration CACHE_TTL = Duration.ofSeconds(10);

    /**
     * 灾难性回溯（ReDoS）闸门：检出「组被量词修饰 <b>且</b> 组内含被量词修饰元素」的嵌套形态。
     *
     * <p><b>为什么不用单条正则</b>：旧实现 {@code \([^()]*[+*][^()]*\)…} 的「组内不含括号」前提，
     * 使<b>两层及以上</b>嵌套（{@code ((a+))+}）漏判——一条即可让热路径灾难性回溯（DoS）；
     * 它还会把字符类内的 {@code +}（{@code ([+*])+}）与转义括号（{@code \(a\+\)+}）误判为嵌套。
     * 故改为括号栈逐字符解析：跳过转义与字符类，关闭子组时把「组内含被量词修饰元素」
     * 向上传播给父组，父组若同时被量词修饰即为嵌套。
     *
     * <p><b>保守而非完备</b>：不覆盖 {@code (a|aa)+} 这类「歧义分支」型 ReDoS，
     * 且刻意放行安全形态（{@code (ab)+}、{@code (a|b)+}、{@code (a+)(b+)}、{@code ([+*])+}、
     * {@code (\d{2,4})-\d+}、{@code (a+)?}、{@code \(a\+\)+}）。闸门是防 DoS 的最小拦截，
     * 不是正则审查——完备方案需引擎超时（Java {@code Pattern} 不支持），记入 KNOWN-ISSUES。
     *
     * @return true 表示含嵌套量词，应拒绝入库
     */
    static boolean containsNestedQuantifier(String regex) {
        Deque<Boolean> stack = new ArrayDeque<>();
        stack.push(Boolean.FALSE); // 最外层（非组）是否含「被量词修饰元素」
        int i = 0;
        int n = regex.length();
        while (i < n) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i += (i + 1 < n) ? 2 : 1; // 转义：连同被转义字符一起跳过
                continue;
            }
            if (c == '[') {
                i = skipCharClass(regex, i); // 字符类整体是一个 atom，内部量词字符不算
                continue;
            }
            if (c == '(') {
                stack.push(Boolean.FALSE);
                i++;
                continue;
            }
            if (c == ')') {
                boolean innerQuantified = stack.pop();
                boolean groupQuantified = isQuantifierAt(regex, i + 1);
                if (groupQuantified && innerQuantified) {
                    return true; // 组被量词修饰，且组内含被量词修饰元素 → 嵌套
                }
                if (innerQuantified || groupQuantified) {
                    // 向上传播：父组此刻「含一个内含量词的组 / 被量词修饰的组」
                    stack.push(stack.pop() | Boolean.TRUE);
                }
                i++;
                continue;
            }
            if (isQuantifierAt(regex, i)) {
                // 量词修饰其左侧 atom（普通字符 / 字符类 / 已闭合组）
                stack.push(stack.pop() | Boolean.TRUE);
                i = skipQuantifier(regex, i);
                continue;
            }
            i++;
        }
        return false;
    }

    /** {@code pos} 处是否为重复量词 {@code +}/{@code *}/{@code {n}}/{@code {n,}}/{@code {n,m}}（{@code ?} 除外）。 */
    private static boolean isQuantifierAt(String regex, int pos) {
        if (pos < 0 || pos >= regex.length()) {
            return false;
        }
        char c = regex.charAt(pos);
        if (c == '+' || c == '*') {
            return true;
        }
        if (c == '{') {
            int close = regex.indexOf('}', pos);
            if (close < 0) {
                return false;
            }
            return regex.substring(pos + 1, close).matches("\\d+(,\\d*)?");
        }
        return false;
    }

    /** 跳过 {@code pos} 处的量词（含其后的懒惰修饰符 {@code ?}）。调用前须已确认 {@code isQuantifierAt}。 */
    private static int skipQuantifier(String regex, int pos) {
        int next;
        if (regex.charAt(pos) == '{') {
            int close = regex.indexOf('}', pos);
            next = (close < 0) ? pos + 1 : close + 1;
        } else {
            next = pos + 1;
        }
        if (next < regex.length() && regex.charAt(next) == '?') {
            next++; // 懒惰量词 a+? / a*?
        }
        return next;
    }

    /** 跳过从 {@code start}（指向 {@code [}）开始的字符类，返回 {@code ]} 之后的位置。 */
    private static int skipCharClass(String regex, int start) {
        int i = start + 1;
        int n = regex.length();
        if (i < n && regex.charAt(i) == '^') {
            i++; // 取反
        }
        if (i < n && regex.charAt(i) == ']') {
            i++; // 首字符位置的 ']' 是字面量
        }
        while (i < n && regex.charAt(i) != ']') {
            if (regex.charAt(i) == '\\') {
                i++; // 跳过转义
            }
            i++;
        }
        return i + 1; // 跳过 ']'
    }

    private final TaughtRuleRepository repository;
    private final Clock clock;

    /** 进程内 TTL 缓存：chatId → 已编译规则 + 过期时刻。 */
    private final Map<Long, CachedRules> cache = new ConcurrentHashMap<>();

    private record CachedRules(List<ModerationRule> rules, Instant expiresAt) {
    }

    public TaughtRuleService(TaughtRuleRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 教一条规则（同群同 ruleId 再教 = 覆盖内容）。
     *
     * <p><b>这就是 V5.0 §10.3 的「确认」步骤</b>：管理员执行命令即确认，回复里回显规则预览
     * （而不是做多轮对话状态机——那需要会话状态存储，本阶段不引）。
     *
     * @throws TggException 任一校验不通过（正则非法/过长/灾难性回溯、id 或描述为空/过长）
     */
    @Transactional
    public TaughtRule teach(long chatId, String ruleId, String name, String regex,
                            RiskLevel riskLevel, boolean hardLine, Long operator) {
        String cleanRuleId = requireToken(ruleId, MAX_RULE_ID_LENGTH, "规则 id");
        String cleanName = requireText(name, MAX_NAME_LENGTH, "规则描述");
        String cleanRegex = validateRegex(regex);
        if (riskLevel == null) {
            throw new TggException("风险等级不得为空（LOW / MEDIUM / HIGH）");
        }

        Instant now = clock.instant();
        Optional<TaughtRule> existing = repository.findByChatIdAndRuleId(chatId, cleanRuleId);
        TaughtRule saved = existing
                .map(rule -> {
                    rule.redefine(cleanName, cleanRegex, riskLevel, hardLine, now);
                    return repository.save(rule);
                })
                .orElseGet(() -> repository.save(
                        new TaughtRule(chatId, cleanRuleId, cleanName, cleanRegex, riskLevel, hardLine,
                                operator, now)));

        // 写后立即失效缓存 → 「教完立刻生效」，不必等 TTL
        cache.remove(chatId);
        log.info("教学规则已生效：chatHash={} ruleId={} level={} hardLine={}",
                ruleId, riskLevel, hardLine);
        return saved;
    }

    /** 停用一条规则。@return false = 该群没有这条规则 */
    @Transactional
    public boolean disable(long chatId, String ruleId) {
        Optional<TaughtRule> found = repository.findByChatIdAndRuleId(chatId, ruleId);
        if (found.isEmpty()) {
            return false;
        }
        TaughtRule rule = found.get();
        rule.disable(clock.instant());
        repository.save(rule);
        cache.remove(chatId);
        return true;
    }

    /**
     * 热路径入口：该群当前生效的教学规则（已编译）。
     *
     * <p>库里若有编译不过的正则（手改库/降级写入），**跳过该条并记日志**——
     * 一条坏规则不该让整群的审核失效，但也不能静默：必须留下可追查的痕迹。
     */
    public List<ModerationRule> rulesFor(long chatId) {
        Instant now = clock.instant();
        CachedRules cached = cache.get(chatId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.rules();
        }
        List<ModerationRule> rules = repository.findByChatIdAndEnabledTrueOrderByIdAsc(chatId).stream()
                .map(this::compileSafely)
                .flatMap(Optional::stream)
                .toList();
        cache.put(chatId, new CachedRules(rules, now.plus(CACHE_TTL)));
        return rules;
    }

    /** 该群全部规则（含停用），供管理命令展示。 */
    public List<TaughtRule> listTaught(long chatId) {
        return repository.findByChatIdOrderByIdAsc(chatId);
    }

    private Optional<ModerationRule> compileSafely(TaughtRule rule) {
        try {
            return Optional.of(rule.toRule());
        } catch (IllegalArgumentException ex) {
            // 覆盖 PatternSyntaxException（它是 IllegalArgumentException 的子类）与
            // RiskLevel.valueOf 的非法值——两者都是「库里的规则不可用」，处置相同
            log.warn("教学规则不可用（已跳过，不影响其他规则）：ruleId={} 原因={}",
                    rule.getRuleId(), ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 三条正则闸门：能编译、长度受限、非灾难性回溯形态。 */
    static String validateRegex(String regex) {
        if (regex == null || regex.isBlank()) {
            throw new TggException("正则不得为空");
        }
        String clean = regex.trim();
        if (clean.length() > MAX_REGEX_LENGTH) {
            throw new TggException("正则过长（上限 " + MAX_REGEX_LENGTH + " 字符，实为 " + clean.length() + "）");
        }
        try {
            Pattern.compile(clean);
        } catch (PatternSyntaxException ex) {
            throw new TggException("正则无法编译：" + ex.getDescription());
        }
        if (containsNestedQuantifier(clean)) {
            throw new TggException("正则含嵌套量词（如 (a+)+ ），在长文本上会灾难性回溯、拖垮机器人；"
                    + "请改写为等价但不嵌套的形式");
        }
        return clean;
    }

    /**
     * 校验「可含空白的自由文本」（规则描述）。
     *
     * <p><b>与 {@link #requireToken} 的分工</b>：tokens（规则 id）是命令参数，含空白会破坏参数切分，
     * 必须拒；而描述是命令尾部的<b>整体剩余文本</b>（{@code /teach id regex 一句带空格的话}），
     * 放行内部空白才符合命令层的契约。曾因两者混用导致「命令层说可含空格、服务层必拒」
     * 的矛盾（提交后审查抓出，此前无测试覆盖），故拆成两个方法并在各自 javadoc 写明分工。
     */
    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new TggException(field + "不得为空");
        }
        String clean = value.trim();
        if (clean.length() > maxLength) {
            throw new TggException(field + "过长（上限 " + maxLength + " 字符）");
        }
        return clean;
    }

    private static String requireToken(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new TggException(field + "不得为空");
        }
        String clean = value.trim();
        if (clean.length() > maxLength) {
            throw new TggException(field + "过长（上限 " + maxLength + " 字符）");
        }
        if (clean.chars().anyMatch(Character::isWhitespace)) {
            throw new TggException(field + "不得含空白（命令参数以空白分隔）");
        }
        return clean;
    }
}
