package com.tg.heyisheng.bot.core.breach;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 数据泄露登记与 72 小时计时（模块十 §11.2）。
 *
 * <p><b>它做什么、不做什么</b>：通报本身是<b>运营者的法定义务</b>（GDPR Art. 33 等），软件不代为履行。
 * 软件负责把「72 小时」变成可追踪对象：登记 → 计时 → 提前催办 → 留痕（谁在何时通报）。
 * 没有一个工具能替运营者决定「这算不算需要通报的泄露」——那是法律判断。
 */
public class DataBreachService {

    /** 通报窗口：72 小时（GDPR Art. 33）。 */
    public static final Duration REPORT_WINDOW = Duration.ofHours(72);
    /** 剩余时间少于该值即开始催办（提前量，避免卡点当天才发现）。 */
    public static final Duration REMIND_WITHIN = Duration.ofHours(24);

    private final DataBreachRepository repository;
    private final Clock clock;

    public DataBreachService(DataBreachRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 登记一起泄露事件。
     *
     * @param detectedAt    发现时间（72h 从此刻起算；{@code null} 视为当下）
     * @param scope         影响范围简述（<b>不含个人数据本身</b>）
     * @param affectedCount 预计受影响人数（负数按 0 计）
     */
    @Transactional
    public DataBreachIncident register(Instant detectedAt, String scope,
                                       int affectedCount, Long operator) {
        if (scope == null || scope.isBlank()) {
            throw new TggException("影响范围不得为空");
        }
        if (scope.length() > 255) {
            throw new TggException("影响范围过长（上限 255 字符）");
        }
        Instant detected = detectedAt == null ? clock.instant() : detectedAt;
        return repository.save(new DataBreachIncident(detected, detected.plus(REPORT_WINDOW),
                scope.trim(), Math.max(affectedCount, 0), operator, clock.instant()));
    }

    /**
     * 标记已通报。
     *
     * <p><b>原子性</b>：判定「reported_at 是否为空」下推到 SQL 的 {@code WHERE ... IS NULL}，
     * 以受影响行数作<b>唯一闸门</b>。此前的读-改-写（findById → 内存判定 → save）在两个复核人
     * 并发标记时会让两者都判定「我是首个」并互相覆盖首报时间——那是合规证据。
     *
     * @return {@code false} = 事件不存在或已通报（幂等；首次通报时间不会被覆盖——那是证据）
     */
    @Transactional
    public boolean markReported(long id, Long operator) {
        return repository.markReportedIfUnreported(id, clock.instant(), operator) > 0;
    }

    /** 尚未通报的事件。 */
    public List<DataBreachIncident> pending() {
        return repository.findByReportedAtIsNullOrderByIdAsc();
    }

    /** 需要催办的事件：未通报，且距截止不足 {@link #REMIND_WITHIN}（含已逾期）。 */
    public List<DataBreachIncident> dueForReminder() {
        Instant threshold = clock.instant().plus(REMIND_WITHIN);
        return pending().stream()
                .filter(incident -> !threshold.isBefore(incident.getDeadlineAt()))
                .toList();
    }
}
