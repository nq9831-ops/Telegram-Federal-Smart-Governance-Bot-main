package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 信用记账服务（模块七）：把一条信用事件落到账本，并判定应触发的处罚档位。
 *
 * <p><b>不是 {@code @Service}</b>：本类受 {@code tgg.credit.enabled} 门控，由
 * {@link CreditConfiguration} 以 {@code @Bean} 装配——若直接标 {@code @Service}，
 * 组件扫描会在开关关闭时也实例化它，而其依赖（规则引擎）未装配即导致整个应用上下文启动失败
 * （本项目实测踩过：连累既有的 {@code TggApplicationContextTest}）。
 *
 * <p><b>记账流程</b>：取增量 → 确保账本行存在（首次建行）→ 数据库侧原子应用增量 → 回读新分 → 判阈值。
 *
 * <p><b>失败策略</b>：本方法在事务内执行；调用方（信用事件适配器）负责吞掉异常，
 * 使得"记账失败"不会中断消息处理主链路——信用分是增强，不是消息处理的必要环节。
 *
 * <p>日志中的主体标识<b>经 {@link IdHasher} 哈希</b>（Telegram userId 空间小，明文进日志属个人数据处理）。
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
    private final IdHasher idHasher;

    public CreditService(CreditRuleEngine ruleEngine, CreditScoreRepository repository, IdHasher idHasher) {
        this.ruleEngine = ruleEngine;
        this.repository = repository;
        this.idHasher = idHasher;
    }

    /**
     * 应用一条信用事件：记账并返回结果。
     *
     * @param event 信用事件；为 null 时返回 null
     * @return 记账结果（含最新分值与应触发的处罚档位）；event 为 null 时为 null
     */
    @Transactional
    public CreditOutcome apply(CreditEvent event) {
        if (event == null) {
            return null;
        }

        int delta = ruleEngine.deltaFor(event);
        Instant now = Instant.now();
        String subjectType = event.subjectType().name();

        // 首次记账时建行（幂等）；随后原子应用增量。
        repository.insertIfAbsent(subjectType, event.subjectId(), INITIAL_SCORE, now);
        if (delta != 0) {
            repository.applyDelta(subjectType, event.subjectId(), delta, MIN_SCORE, MAX_SCORE, now);
        }

        int newScore = repository.findBySubjectTypeAndSubjectId(event.subjectType(), event.subjectId())
                .map(CreditScore::getScore)
                .orElse(INITIAL_SCORE);

        PenaltyType penalty = CreditThresholds.penaltyFor(newScore);
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
     * 模型落差属模块七的已知缺口（见 {@code docs/KNOWN-ISSUES.md}），不在本方法内「顺手修正」。
     *
     * @return {@code true} = 本次真的新建了账本行；{@code false} = 已有行（分值保持不变）
     */
    @Transactional
    public boolean ensureInitialized(CreditSubjectType subjectType, long subjectId, int initialScore) {
        if (subjectType == null) {
            return false;
        }
        boolean created = repository.insertIfAbsent(subjectType.name(), subjectId, initialScore,
                Instant.now()) > 0;
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
}
