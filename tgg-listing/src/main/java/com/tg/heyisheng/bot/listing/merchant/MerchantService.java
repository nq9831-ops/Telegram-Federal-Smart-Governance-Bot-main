package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.credit.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 模块六 · 商家入驻服务：承载<b>入驻状态机</b>（设计文档 §2「数据流（模块六）」）。
 *
 * <pre>
 * SUBMITTED ──beginReview──▶ UNDER_REVIEW ──decide──▶ APPROVED / REJECTED / NEED_MORE
 * NEED_MORE ──beginReview──▶ UNDER_REVIEW                └─ APPROVED ─▶ markDepositPending
 *                                                                              │
 *                                                                          markActive
 *                                                                              ▼
 *                                                                            ACTIVE
 * </pre>
 *
 * <p><b>状态迁移的合法性由实体把关</b>（{@link Merchant#beginReview} 等），本类只负责
 * 「取行 → 迁移 → 落库」与<b>副作用编排</b>。任何人想放宽迁移规则，都必须先改实体里的
 * {@code requireStatus}。
 *
 * <p><b>为什么 {@link CreditService} 可为空</b>：模块六与模块七是<b>两个独立开关</b>
 * （{@code tgg.merchant.enabled} 与 {@code tgg.credit.enabled}）。商家模块启用而信用模块未启用
 * 是合法配置，此时入驻仍应可完成（状态照常迁移），只是信用分无法初始化——这种「半启用」
 * 必须是<b>可观测的</b>（WARN），而不是静默漏写。若这里强依赖 {@code CreditService} bean，
 * 未启用模块七就会让整个应用上下文起不来（本项目在模块九踩过「漏装配即启动失败」的坑）。
 *
 * <p>时钟经构造注入（{@link Clock}）：测试注入 {@code Clock.fixed} 即可断言
 * {@code updatedAt} 的确切时刻，不必真等到某个时刻。
 */
public class MerchantService {

    private static final Logger log = LoggerFactory.getLogger(MerchantService.class);

    /**
     * 「在办」状态：申请尚未落定。{@code ACTIVE} 不在其中——已入驻的商家可以再开一家
     * （连锁），而 {@code REJECTED} 是终态、也允许重新申请（新建条目）。
     */
    private static final List<Merchant.Status> OPEN_STATUSES = List.of(
            Merchant.Status.SUBMITTED,
            Merchant.Status.UNDER_REVIEW,
            Merchant.Status.APPROVED,
            Merchant.Status.NEED_MORE,
            Merchant.Status.DEPOSIT_PENDING);

    private final MerchantRepository merchants;
    private final MerchantProperties properties;
    /** 模块七的记账服务；模块七未启用时为 {@code null}（见类 javadoc）。 */
    private final CreditService creditService;
    private final Clock clock;
    /** 热读取源（初始信用分）；静态模式为 {@code null}。 */
    private final com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService config;

    public MerchantService(MerchantRepository merchants,
                           MerchantProperties properties,
                           CreditService creditService,
                           Clock clock) {
        this(merchants, properties, creditService, clock, null);
    }

    /** 热模式：初始信用分在调用期读取，改配置无需重启。 */
    public MerchantService(MerchantRepository merchants,
                           MerchantProperties properties,
                           CreditService creditService,
                           Clock clock,
                           com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService config) {
        this.merchants = merchants;
        this.properties = properties;
        this.creditService = creditService;
        this.clock = clock;
        this.config = config;
    }

    /** 商家初始信用分（热读取；静态模式回落到 {@link MerchantProperties}）。 */
    private int initialScore() {
        return config == null
                ? properties.getInitialScore()
                : config.getInt("tgg.merchant.initial-score", properties.getInitialScore());
    }

    /**
     * 提交入驻申请：新建一条 {@link Merchant.Status#SUBMITTED} 记录。
     *
     * <p><b>调用方应先经 {@link #findOpenByOwner} 去重</b>——本方法不做「已有在办」抑制，
     * 因为它无法替调用方回答「是提示既有编号、还是拒绝」（那是交互决策，不是领域规则）。
     */
    @Transactional
    public Merchant submit(long ownerUserId, String name, String category, String intro, String contact) {
        if (name == null || name.isBlank()) {
            throw new TggException("商家名称不得为空");
        }
        Instant now = clock.instant();
        return merchants.save(new Merchant(ownerUserId, name.trim(), category, intro, contact, now));
    }

    /** 该用户当前<b>在办</b>的申请（无则空）。用于抑制重复提交。 */
    public Optional<Merchant> findOpenByOwner(long ownerUserId) {
        return merchants.findByOwnerUserIdOrderByIdAsc(ownerUserId).stream()
                .filter(merchant -> OPEN_STATUSES.contains(Merchant.parse(merchant.getStatus())))
                .findFirst();
    }

    /** 按 id 取商家。 */
    public Optional<Merchant> find(long merchantId) {
        return merchants.findById(merchantId);
    }

    /** 该用户<b>最新</b>一条申请（按 id 取最大者；无则空）。供状态查询展示。 */
    public Optional<Merchant> latestByOwner(long ownerUserId) {
        List<Merchant> all = merchants.findByOwnerUserIdOrderByIdAsc(ownerUserId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    /** 开始复核：{@code SUBMITTED}/{@code NEED_MORE} → {@code UNDER_REVIEW}。 */
    @Transactional
    public Optional<Merchant> beginReview(long merchantId, Long operator) {
        return transition(merchantId, merchant -> {
            requireNotSelfReview(merchant, operator);
            merchant.beginReview(clock.instant());
        });
    }

    /** 写入复核结论：{@code UNDER_REVIEW} → {@code APPROVED}/{@code REJECTED}/{@code NEED_MORE}。 */
    @Transactional
    public Optional<Merchant> decide(long merchantId, Merchant.Status decision, Long operator) {
        return transition(merchantId, merchant -> {
            requireNotSelfReview(merchant, operator);
            merchant.decide(decision, clock.instant());
        });
    }

    /**
     * 「不可自审」：商家主本人不得复核、也不得裁定自己的申请。
     *
     * <p><b>为什么两处迁移都要拦</b>：{@code beginReview} 与 {@code decide} 是两次状态迁移。
     * 只拦后者的话，一次被拒的自审仍会把商家推进到 {@code UNDER_REVIEW}
     * ——一次本不该发生的状态变更，而且从数据上看不出来。
     *
     * <p>规则放在<b>服务层</b>而不是命令层预检：命令层只是入口之一（模块十一的 Web 后台
     * 同样会读写商家），写在服务里才是「谁调都绕不过」。与
     * {@code ModerationReviewDecisionService}、{@code FederationAppealService} 同一约束。
     *
     * <p>{@code operator} 为 {@code null} 时（无操作人上下文的内部调用）不拦截——那种场景
     * 不存在「自己」；命令入口的 operator 恒非空（门控已验明身份）。
     */
    private static void requireNotSelfReview(Merchant merchant, Long operator) {
        if (operator != null && operator.equals(merchant.getOwnerUserId())) {
            throw new TggException("不能复核自己的商家申请：商家 #" + merchant.getId()
                    + " 的申请人就是你。请让其他复核人处理。");
        }
    }

    /** 资质通过后进入缴费阶段：{@code APPROVED} → {@code DEPOSIT_PENDING}（由保证金流程驱动）。 */
    @Transactional
    public Optional<Merchant> markDepositPending(long merchantId) {
        return transition(merchantId, merchant -> merchant.markDepositPending(clock.instant()));
    }

    /**
     * 保证金到位，入驻成功：{@code DEPOSIT_PENDING} → {@code ACTIVE}，并<b>初始化商家信用分</b>。
     *
     * <p><b>信用分是「入驻成功」的产物，不是一次调用</b>：断言它的地方必须去查
     * {@code credit_scores} 的 {@code (MERCHANT, merchantId)} 行（见 {@code MerchantOnboardingIT}），
     * 而不是数 {@code ensureInitialized} 被调用了几次。
     *
     * <p><b>重复调用会被状态机拒绝</b>：{@code ACTIVE} → {@code ACTIVE} 不是合法迁移，
     * 实体抛异常——乱序调用与重放不会悄悄改状态。而信用分那次初始化本身是幂等的
     * （{@code INSERT IGNORE}，不覆盖已有分值）。
     */
    @Transactional
    public Optional<Merchant> markActive(long merchantId) {
        Optional<Merchant> activated = transition(merchantId, merchant -> merchant.markActive(clock.instant()));
        activated.ifPresent(this::initializeCreditIfAbsent);
        return activated;
    }

    /**
     * 按「信用分 + 保证金 + 流水量」评定等级并写入（设计文档 §3.4）。
     *
     * <p><b>信用分从账本读</b>（{@code CreditSubjectType.MERCHANT}）。模块七未启用时退回
     * {@code tgg.merchant.initial-score}——那时账本里本来就没有商家分，用配置初值代表
     * 「未受损的默认声誉」比用 0 更贴近事实（用 0 会让所有商家都评成 {@code NONE}，
     * 把「没开信用模块」伪装成「商家声誉差」）。
     *
     * @param depositAmount     保证金金额（判定输入之一）
     * @param transactionVolume 交易流水量；本阶段无数据源，调用方传 0
     */
    @Transactional
    public Optional<Merchant> evaluateTier(long merchantId, BigDecimal depositAmount, long transactionVolume) {
        Optional<Merchant> found = merchants.findById(merchantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Merchant merchant = found.get();
        MerchantTierEvaluator.Tier tier =
                MerchantTierEvaluator.evaluate(currentCreditScore(merchantId), depositAmount, transactionVolume);
        merchant.assignTier(tier.name(), clock.instant());
        return Optional.of(merchants.save(merchant));
    }

    /** 商家当前信用分（模块七未启用时用配置初值，理由见 {@link #evaluateTier}）。 */
    private int currentCreditScore(long merchantId) {
        if (creditService == null) {
            return initialScore();
        }
        return creditService.scoreOf(CreditSubjectType.MERCHANT, merchantId);
    }

    /**
     * 初始化商家信用分账本行（幂等；模块七未启用时记 WARN 并跳过）。
     *
     * <p>主体 id 用<b>商家 id</b>（{@code CreditSubjectType.MERCHANT} 的既定口径：
     * {@code subjectId} = 商户 id，不是 owner 的 userId）。
     */
    private void initializeCreditIfAbsent(Merchant merchant) {
        if (creditService == null) {
            log.warn("商家 #{} 已入驻，但模块七（tgg.credit）未启用：信用分未初始化。"
                    + "启用模块六时建议同时启用 tgg.credit.enabled，否则商家信用分为空。", merchant.getId());
            return;
        }
        creditService.ensureInitialized(CreditSubjectType.MERCHANT, merchant.getId(),
                initialScore());
    }

    /** 取行 → 迁移 → 落库；行不存在时返回空（由调用方决定如何提示）。 */
    private Optional<Merchant> transition(long merchantId, Consumer<Merchant> action) {
        Optional<Merchant> found = merchants.findById(merchantId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Merchant merchant = found.get();
        action.accept(merchant);
        return Optional.of(merchants.save(merchant));
    }
}
