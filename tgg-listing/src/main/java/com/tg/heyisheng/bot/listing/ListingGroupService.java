package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.listing.verification.GroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.VerificationRecord;
import com.tg.heyisheng.bot.listing.verification.VerificationRecordRepository;
import com.tg.heyisheng.bot.listing.verification.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 模块五 · 收录库服务：承载<b>验证状态机</b>（设计文档 §3.2）。
 *
 * <pre>
 * OK    → lastVerifiedAt 刷新，failCount 归零
 * FAIL  → failCount++；达阈值（默认 3）→ status=SUSPENDED（软删）+ suspendedAt 落库
 * ERROR → 只写一条验证记录；failCount / status / lastVerifiedAt 一律不动
 * </pre>
 *
 * <p><b>本类存在的意义就是把「探测失败」与「群失效」分开</b>：{@link #verify} 兜住探针的一切异常
 * 并折成 {@link VerificationResult#ERROR}，{@link #recordOutcome} 对 ERROR 分支<b>不触碰实体</b>。
 * 任何人想把 ERROR 当成 FAIL 处理，都必须先改掉这两个方法。
 *
 * <p><b>软删不物理删</b>：判失效只改 {@code status} / {@code suspendedAt}，行保留——
 * 对外不可见（查询按 status 过滤），对内可审计、可申诉。
 *
 * <p>时钟经构造注入（{@link Clock}）：测试注入 {@code Clock.fixed} 即可断言
 * {@code suspendedAt} 的确切时刻，且不必真等到凌晨。
 */
public class ListingGroupService {

    private static final Logger log = LoggerFactory.getLogger(ListingGroupService.class);

    private final ListingGroupRepository groups;
    private final VerificationRecordRepository records;
    private final GroupLinkVerifier verifier;
    private final ListingProperties properties;
    private final Clock clock;

    public ListingGroupService(ListingGroupRepository groups,
                               VerificationRecordRepository records,
                               GroupLinkVerifier verifier,
                               ListingProperties properties,
                               Clock clock) {
        this.groups = groups;
        this.records = records;
        this.verifier = verifier;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 提交一条收录（幂等）。
     *
     * <p>重复提交同一个 {@code chatId} 由数据库唯一键 + {@code INSERT IGNORE} 静默吸收，
     * <b>不</b>抛唯一约束异常、也<b>不</b>覆盖既有条目——不用「先查再存 + catch 异常」，
     * 因为 Hibernate 在 flush 失败后 session 即不可用（沿用坑 21 纪律）。
     *
     * @return {@code true} = 本次真的新建；{@code false} = 该群已在库中（重复提交，被忽略）
     */
    @Transactional
    public boolean submit(long chatId, String inviteLink, String title, Long submitterUserId) {
        boolean created = groups.insertIfAbsent(chatId, inviteLink, title, submitterUserId,
                ListingGroup.Status.ACTIVE.name(), clock.instant()) > 0;
        if (!created) {
            log.info("群 {} 已在收录库中，重复提交被忽略。", chatId);
        }
        return created;
    }

    /** 待验证条目：{@code status=ACTIVE}，按 id 升序（分批限速的取数入口）。 */
    public List<ListingGroup> activeGroups() {
        return groups.findByStatusOrderByIdAsc(ListingGroup.Status.ACTIVE.name());
    }

    /**
     * 从给定条目中挑出「长期未成功验证」的那些（模块五 §3.2 的补充告警，**只读、不改任何状态**）。
     *
     * <p><b>它抓的是什么</b>：{@code ERROR} 既不累加 {@code failCount}、也不改变业务状态
     * （见 {@link #recordOutcome} 的空分支），所以「探测层持续不可用」（缺 {@code TGG_BOT_TOKEN} /
     * 网络不通 / 被限流）的条目会**永远安静地**停在 ACTIVE；而一直 {@code FAIL} 的条目会累积到
     * {@code SUSPENDED}、由既有流程处理。因此「ACTIVE 且长期没有验证成功」正好等价于
     * 「连续多轮 ERROR」——这正是既有流程**不会**发现的失败形态。
     *
     * <p><b>基准的选取</b>：取 {@code lastVerifiedAt}；从未成功验证过的回落到 {@code createdAt}——
     * 否则「提交后一直没验成功」这类最该被发现的条目反而漏掉。
     *
     * @param entries   待筛选条目（通常是 {@link #activeGroups()} 的结果）
     * @param staleDays 阈值天数；最后一次成功（或创建）**严格早于** {@code now - staleDays} 才算 stale
     * @return 保序的 stale 条目（调用方可据此打出稳定的日志）
     */
    public List<ListingGroup> staleAmong(List<ListingGroup> entries, int staleDays) {
        Instant cutoff = clock.instant().minus(Duration.ofDays(staleDays));
        return entries.stream()
                .filter(entry -> {
                    Instant baseline = entry.getLastVerifiedAt() != null
                            ? entry.getLastVerifiedAt()
                            : entry.getCreatedAt();
                    return baseline != null && baseline.isBefore(cutoff);
                })
                .toList();
    }

    /**
     * 调探针取一次结果。
     *
     * <p><b>兜底契约</b>：探针抛出的任何 {@link RuntimeException} 都被折成 {@link VerificationResult#ERROR}
     * ——「探测本身炸了」永远不等于「群失效」。
     */
    public VerificationResult verify(ListingGroup entry) {
        try {
            VerificationResult result = verifier.verify(entry);
            return result == null ? VerificationResult.ERROR : result;
        } catch (RuntimeException ex) {
            log.warn("条目 #{} 探针抛出 {}，按 ERROR 处理（不计入失败次数）：{}",
                    entry.getId(), ex.getClass().getSimpleName(), ex.getMessage());
            return VerificationResult.ERROR;
        }
    }

    /**
     * 把一次验证结果落进状态机，并追加一条验证记录。
     *
     * <p>{@code ERROR} 分支<b>不调用 save</b>：不动 failCount / status / lastVerifiedAt，
     * 只留审计记录——这是「探测失败不误伤群」在代码层的落点。
     *
     * @return {@code true} = 本次判定为失效（已转 SUSPENDED，供调用方发通知）
     */
    @Transactional
    public boolean recordOutcome(ListingGroup entry, VerificationResult result) {
        Instant now = clock.instant();
        boolean suspended = false;

        switch (result) {
            case OK -> entry.markVerifiedOk(now);
            case FAIL -> suspended = entry.registerFailure(now, properties.getFailThreshold());
            case ERROR -> {
                // 刻意留空：探测失败不改动任何业务状态（仅下方落一条记录）
            }
        }

        if (result != VerificationResult.ERROR) {
            groups.save(entry);
        }
        records.save(new VerificationRecord(entry.getId(), now, result, describe(result)));

        if (suspended) {
            log.warn("条目 #{} 连续 {} 次验证失败，已置 SUSPENDED（软删：记录保留，可供审计与申诉）。",
                    entry.getId(), entry.getFailCount());
        }
        return suspended;
    }

    /** 记录里的状态描述（不含任何群消息内容）。 */
    private static String describe(VerificationResult result) {
        return switch (result) {
            case OK -> "链接可达";
            case FAIL -> "链接验证失败";
            case ERROR -> "探测异常，不计入失败次数";
        };
    }
}
