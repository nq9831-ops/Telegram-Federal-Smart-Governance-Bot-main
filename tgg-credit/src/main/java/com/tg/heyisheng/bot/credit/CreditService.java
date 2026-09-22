package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 信用记账服务（模块七）：把一条信用事件落到账本，并判定应触发的处罚档位。
 *
 * <p><b>不是 {@code @Service}</b>：本类受 {@code tgg.credit.enabled} 门控，由
 * {@link CreditConfiguration} 以 {@code @Bean} 装配——若直接标 {@code @Service}，
 * 组件扫描会在开关关闭时也实例化它，而其依赖（规则引擎）未装配即导致整个应用上下文启动失败
 * （本项目实测踩过：连累既有的 {@code TggApplicationContextTest}）。
 *
 * <p><b>记账流程</b>：取增量 → 确保账本行存在（首次建行）→ <b>写流水（幂等判定）</b> →
 * 数据库侧原子应用增量 → 回读新分 → 判阈值。
 *
 * <p><b>幂等（§3.3）</b>：流水表的 {@code idempotency_key} 唯一约束是去重的唯一依据。
 * 同键已存在 = 这条业务事实已经记过账（典型来源：Telegram 重投同一条 update）→
 * 直接返回、<b>不再扣分、不再产出处罚</b>。没有它，一次 webhook 重投就会二次扣分、
 * 甚至二次广播联邦封禁。
 *
 * <p><b>失败策略</b>：本方法在事务内执行；调用方（信用事件适配器）负责吞掉异常，
 * 使得"记账失败"不会中断消息处理主链路——信用分是增强，不是消息处理的必要环节。
 *
 * <p>日志中的主体标识<b>经 {@link IdHasher} 哈希</b>（Telegram userId 空间小，明文进日志属个人数据处理）；
 * 幂等键含明文 chatId/messageId，同样不打日志。
 */
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    /** 初始分值（三套分统一）。设计文档默认值，待校准。 */
    public static final int INITIAL_SCORE = 100;
    /** 分值下界：不允许负分（触底即 0）。 */
    public static final int MIN_SCORE = 0;
    /** 分值上界。本阶段只扣分，上界为后续加分预留。 */
    public static final int MAX_SCORE = 150;

    private final CreditRuleEngine ruleEngine;
    private final CreditScoreRepository repository;
    private final CreditEventRecordRepository eventRecordRepository;
    private final IdHasher idHasher;
    private final Clock clock;

    /** 生产构造器：时钟取系统 UTC（与其他组件一致：Clock 可注入，便于用可推进时钟测时间相关逻辑）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public CreditService(CreditRuleEngine ruleEngine,
                         CreditScoreRepository repository,
                         CreditEventRecordRepository eventRecordRepository,
                         IdHasher idHasher) {
        this(ruleEngine, repository, eventRecordRepository, idHasher, Clock.systemUTC());
    }

    /** 可注入时钟的构造器（测试用固定/可推进时钟）。 */
    public CreditService(CreditRuleEngine ruleEngine,
                         CreditScoreRepository repository,
                         CreditEventRecordRepository eventRecordRepository,
                         IdHasher idHasher,
                         Clock clock) {
        this.ruleEngine = ruleEngine;
        this.repository = repository;
        this.eventRecordRepository = eventRecordRepository;
        this.idHasher = idHasher;
        this.clock = clock;
    }

    /**
     * 应用一条信用事件：记账（含流水）并返回结果。
     *
     * @param event 信用事件；为 null 时返回 null
     * @return 记账结果（含最新分值与应触发的处罚档位）；event 为 null 时为 null
     */
    @Transactional
    public CreditOutcome apply(CreditEvent event) {
        if (event == null) {
            return null;
        }
        if (event.eventType() == CreditEventType.MODERATION_REVERSAL) {
            // 补偿事件**不得**走扣分路径：规则引擎会按 severity 重算出一个**负**增量（等于再扣一次），
            // 且重算量与实际扣减量在夹取场景下不同。它只能由 reverseOf 处理。
            // 显式拒绝而非静默跳过——静默会把补偿流水写成 delta=0 并占掉幂等键，
            // 使之后**正确的**退分被去重挡掉，形成"看起来退过、实际没退"。
            throw new IllegalArgumentException(
                    "MODERATION_REVERSAL 是补偿事件，必须经 CreditService.reverseOf 处理，不可 apply");
        }

        int delta = ruleEngine.deltaFor(event);
        Instant now = clock.instant();
        String subjectType = event.subjectType().name();

        // 建行（幂等）**并直接取排他锁**：用 ON DUPLICATE KEY UPDATE 而非 INSERT IGNORE —— 后者命中已存在
        // 行时只取共享锁，随后与下面的锁读构成 S→X 升级环，同主体并发会死锁（实测 Deadlock found）。
        repository.ensureRowLocked(subjectType, event.subjectId(), INITIAL_SCORE, now);

        // 行锁读前值：保证「流水 score_before」与「账本当前分」一致。
        // 若用普通读，同一主体的并发事件会读到同一个前值，流水出现重复 before ⇒ 审计轨迹断裂
        // （实测：修复前曾观测到两条流水 before 均为 100；锁读后 6 并发产出完整链 100→85→…→10）。
        // 守门测试 CreditLedgerConcurrencyIT —— 其 RED 复现是**概率性**的，见该测试 javadoc 的诚实说明。
        int scoreBefore = repository.findForUpdate(event.subjectType(), event.subjectId())
                .map(CreditScore::getScore)
                .orElse(INITIAL_SCORE);

        // 流水先行——它是幂等的判定依据。同键已存在 = 这条业务事实已记过账
        // （典型来源：Telegram 重投同一条 update）→ 直接返回，不扣分、不产出处罚。
        // ⚠️ 用 INSERT IGNORE 而非 catch 唯一约束异常：flush 失败会让 Hibernate session 不可用。
        // ⚠️ 单测里若把本仓库 mock 掉，int 方法默认返回 0，会被读成「重复」——须显式 stub 成 1。
        int inserted = eventRecordRepository.insertIfAbsent(
                subjectType,
                event.subjectId(),
                event.eventType().name(),
                event.severity() == null ? null : event.severity().name(),
                event.hardLine(),
                scoreBefore,
                delta,
                nextScoreAfter(scoreBefore, delta),
                event.source(),
                event.idempotencyKey(),
                event.occurredAt(),
                now);
        if (inserted == 0) {
            // 幂等键含明文 chatId/messageId，不打日志；只打哈希化后的主体标识。
            log.info("重复信用事件已忽略（同幂等键，不重复扣分）：subjectType={} subjectHash={}",
                    event.subjectType(), idHasher.hash(event.subjectId()));
            return new CreditOutcome(event.subjectType(), event.subjectId(), scoreBefore, PenaltyType.NONE);
        }

        if (delta != 0) {
            repository.applyDelta(subjectType, event.subjectId(), delta, MIN_SCORE, MAX_SCORE, now);
        }

        int newScore = repository.findBySubjectTypeAndSubjectId(event.subjectType(), event.subjectId())
                .map(CreditScore::getScore)
                .orElse(INITIAL_SCORE);

        // 回填真实分值（第二道防线）：预测值已与写库语义逐字对齐（见 {@link #nextScoreAfter}），
        // 正常流程预测 == 实际；此处只兜「库侧行为偏离预期」的漂移（如未来改了 applyDelta 口径）。
        // ⚠️ 无幂等键的流水行**不可回填**：updateScoreAfter 按 WHERE idempotency_key = :key 寻址，
        //    key 为 null 时 SQL 恒不匹配（且 insertIfAbsent 契约是 null 键恒插入、行里就是 NULL）
        //    ⇒ 无键事件的流水正确性**完全**依赖预测精确，由 CreditServiceScoreAfterAuditTest 钉死。
        if (event.idempotencyKey() != null && newScore != nextScoreAfter(scoreBefore, delta)) {
            eventRecordRepository.updateScoreAfter(event.idempotencyKey(), newScore);
        }

        // 显式联邦上报优先于分数阈值：生产侧声明「这就是要上报联邦」时，不因分数还没到线而静默不报
        // （模块九 §10.5 的「三次联邦标记」即此——100−5−15−30=50 永远到不了 ≤0 的触发线）。
        PenaltyType penalty = event.federationReport()
                ? PenaltyType.REPORT_TO_FEDERATION
                : CreditThresholds.penaltyFor(newScore);
        if (penalty != PenaltyType.NONE) {
            // 主体标识哈希化——明文 userId/chatId 不得进日志
            log.info("信用分跨阈值：subjectType={} subjectHash={} score={} penalty={}",
                    event.subjectType(), idHasher.hash(event.subjectId()), newScore, penalty);
        }
        return new CreditOutcome(event.subjectType(), event.subjectId(), newScore, penalty);
    }

    /**
     * 显式初始化某主体的账本分值（幂等）。
     *
     * <p><b>为什么需要它</b>：{@link #apply} 首次记账时用的是本类硬编码的 {@link #INITIAL_SCORE}，
     * 而模块六（商家收录）要求商家初值为 V5.0 规格的 500 —— 一个共用常量在此处不够用。
     * 本方法把「初值」交还给调用方：商家入驻成功时以 {@code tgg.merchant.initial-score} 建行，
     * <b>不改</b> {@link #INITIAL_SCORE}（那是三套分共用的既有契约，改它会波及个人/群组分）。
     *
     * <p><b>幂等</b>：账本行已存在时静默跳过（{@code INSERT IGNORE}），<b>不覆盖</b>既有分值
     * ——「初始化」只能在无行时发生，绝不把一个已有分值的商家打回初值。
     *
     * <p><b>不做上下界夹取</b>：夹取只作用于增量路径 {@code CreditScoreRepository#applyDelta}；
     * 本方法的 {@code initialScore} 原样写入。商家初值 500 高于 {@link #MAX_SCORE}（150）这一
     * 模型落差属模块七的已知缺口，不在本方法内「顺手修正」。
     *
     * @return {@code true} = 本次真的新建了账本行；{@code false} = 已有行（分值保持不变）
     */
    @Transactional
    public boolean ensureInitialized(CreditSubjectType subjectType, long subjectId, int initialScore) {
        if (subjectType == null) {
            return false;
        }
        boolean created = repository.insertIfAbsent(subjectType.name(), subjectId, initialScore,
                clock.instant()) > 0;
        if (created) {
            log.info("初始化信用账本：subjectType={} subjectHash={} initialScore={}",
                    subjectType, idHasher.hash(subjectId), initialScore);
        }
        return created;
    }

    /**
     * 读取某主体当前分值（不存在时返回初始分）。
     *
     * <p>只读路径，供查询/展示与测试使用。
     */
    @Transactional(readOnly = true)
    public int scoreOf(CreditSubjectType subjectType, long subjectId) {
        return repository.findBySubjectTypeAndSubjectId(subjectType, subjectId)
                .map(CreditScore::getScore)
                .orElse(INITIAL_SCORE);
    }

    /**
     * 反向补偿：按<b>原事件的幂等键</b>退回它<b>实际</b>扣掉的分（模块十一 · 推翻案件）。
     *
     * <p><b>为什么退分量取自流水而非规则重算</b>：扣分经数据库侧夹取（{@code GREATEST/LEAST}），
     * 分数 10 时硬红线 −100 的<b>实际</b>扣减只有 10。按规则重算会退 100——凭空多给 90 分。
     * 故取原流水的 {@code score_after - score_before}（实际变化）的相反数。
     *
     * <p><b>只追加</b>：原流水<b>不改不删</b>（那是"发生过什么"的记录），补偿是<b>新的一行</b>，
     * 类型为 {@link CreditEventType#MODERATION_REVERSAL}。
     *
     * <p><b>幂等</b>：补偿自身也走同一套 {@code INSERT IGNORE} 去重（键为
     * {@code reversalIdempotencyKey}），重复调用不会把分数越推越高。
     *
     * @param originalIdempotencyKey 原扣分事件的幂等键（{@code moderation:<chatId>:<messageId>}）
     * @param reversalIdempotencyKey 补偿自身的幂等键（须与原键不同，否则互相顶掉）
     * @param reason                 说明（进日志；不含正文）
     * @return {@code true} = 本次真的退了分；{@code false} = 未退（无原事件 / 原事件未改分 / 已退过 / 键缺失）
     */
    @Transactional
    public boolean reverseOf(String originalIdempotencyKey, String reversalIdempotencyKey, String reason) {
        if (originalIdempotencyKey == null || reversalIdempotencyKey == null) {
            return false;
        }
        Optional<CreditEventRecord> found = eventRecordRepository.findByIdempotencyKey(originalIdempotencyKey);
        if (found.isEmpty()) {
            log.info("无可补偿的信用流水（原事件不存在）：reason={}", reason);
            return false;
        }
        CreditEventRecord original = found.get();

        int actualDelta = original.getScoreAfter() - original.getScoreBefore();
        if (actualDelta == 0) {
            // 原事件未改变分数（例如已是 0 分时的扣分）——无分可退
            log.info("原信用事件未改变分值，无需补偿：reason={}", reason);
            return false;
        }
        int refund = -actualDelta;

        Instant now = clock.instant();
        String subjectType = original.getSubjectType().name();
        // 与 apply 同理：建行并直接取排他锁（避免 INSERT IGNORE 的 S→X 升级死锁）。
        repository.ensureRowLocked(subjectType, original.getSubjectId(), INITIAL_SCORE, now);
        // 与 apply 同理：补偿流水的前值也必须锁读，否则并发补偿/扣分会让流水 before 失真。
        int scoreBefore = repository.findForUpdate(original.getSubjectType(), original.getSubjectId())
                .map(CreditScore::getScore)
                .orElse(INITIAL_SCORE);

        int inserted = eventRecordRepository.insertIfAbsent(
                subjectType,
                original.getSubjectId(),
                CreditEventType.MODERATION_REVERSAL.name(),
                original.getSeverity() == null ? null : original.getSeverity().name(),
                original.isHardLine(),
                scoreBefore,
                refund,
                nextScoreAfter(scoreBefore, refund),
                "moderation-reversal",
                reversalIdempotencyKey,
                now,
                now);
        if (inserted == 0) {
            log.info("重复的补偿请求已忽略（同幂等键）：reason={}", reason);
            return false;
        }

        repository.applyDelta(subjectType, original.getSubjectId(), refund, MIN_SCORE, MAX_SCORE, now);
        int newScore = repository.findBySubjectTypeAndSubjectId(original.getSubjectType(), original.getSubjectId())
                .map(CreditScore::getScore)
                .orElse(INITIAL_SCORE);
        // 与 apply 同款回填（第二道防线）：预测值与写库语义逐字对齐，正常流程不会漂移。
        if (newScore != nextScoreAfter(scoreBefore, refund)) {
            eventRecordRepository.updateScoreAfter(reversalIdempotencyKey, newScore);
        }

        log.info("信用分已反向补偿：subjectType={} subjectHash={} refund={} score={} reason={}",
                original.getSubjectType(), idHasher.hash(original.getSubjectId()), refund, newScore, reason);
        return true;
    }

    /** 与 {@code CreditScoreRepository#applyDelta} 的 SQL 夹取口径保持一致（下界防负）。 */
    private static int clamp(int score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    /**
     * 流水 {@code score_after} 的预测值——与写库语义<b>逐字对齐</b>：{@code delta == 0} 时
     * {@code applyDelta} 被跳过、分值原样保留（即使越界，如商家初值 500），其余才按夹取口径。
     *
     * <p>必须精确而非「事后回填」兜底：无幂等键的流水行不可寻址（{@code WHERE idempotency_key = NULL}
     * 恒不匹配），回填对它永远不可达。守门：{@code CreditServiceScoreAfterAuditTest}。
     */
    private static int nextScoreAfter(int scoreBefore, int delta) {
        return delta == 0 ? scoreBefore : clamp(scoreBefore + delta);
    }
}
