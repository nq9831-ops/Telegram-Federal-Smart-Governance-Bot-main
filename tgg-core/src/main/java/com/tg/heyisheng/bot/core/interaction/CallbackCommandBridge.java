package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 回调 → 命令桥：把「按钮点击」变成一次**受同一条门控**的命令执行。
 *
 * <p><b>存在理由</b>：{@code UpdateDispatcher} 的 callback 分流位于审核、中间件链与
 * {@code CommandDispatcher} <b>之前</b>——按钮天然绕过权限、群开关与限流。若为按钮另写一套门控，
 * 必然与命令路径漂移（项目已有同类 P0 教训）。故这里只做四件事：合成等价上下文、加载群配置、
 * 限流、交给同一个 {@code CommandDispatcher}。
 *
 * <p><b>四个不变量（改本类前先读）</b>：
 * <ol>
 *   <li><b>门控只走 {@code CommandDispatcher}</b>——本类不出现任何权限/开关判定；</li>
 *   <li><b>必须显式加载群配置并挂到上下文</b>——否则 {@code CommandDispatcher.isGroupEnabled}
 *       的 {@code orElse(true)} 会把停用群当成启用，按钮就成了绕过 {@code /disable} 的通道；</li>
 *   <li><b>必须限流</b>——按钮是新的可刷入口，既有链路对 callback 不限流；</li>
 *   <li><b>命令名必须在注册表内</b>——防伪造 {@code callback_data} 注入任意命令。</li>
 * </ol>
 *
 * <p><b>返回值规则（webhook 一次只能返回一个方法）</b>：<b>命令结果始终走返回值</b>（这条通道由框架
 * 保证投递）；{@code proactiveSender} 存在时额外发一个「应答」让按钮停止转圈——应答是尽力而为，
 * 失败了也不影响结果送达。没有结果时（如权限不足被静默拒绝）才退化为只回应答。
 */
public class CallbackCommandBridge {

    private static final Logger log = LoggerFactory.getLogger(CallbackCommandBridge.class);

    private static final String UNKNOWN = "未知操作。";
    private static final String TOO_FAST = "操作过于频繁，请稍后再试。";
    private static final String FAILED = "执行失败，请稍后再试。";

    private final CommandDispatcher dispatcher;
    private final CommandRegistry registry;
    private final GroupConfigService groupConfigs;
    private final RateLimiter callbackLimiter;
    /** 主动发送通道；{@code null} = 未配置 bot token（见类 javadoc 的返回值规则）。 */
    private final Consumer<BotApiMethod<?>> proactiveSender;

    public CallbackCommandBridge(CommandDispatcher dispatcher,
                                 CommandRegistry registry,
                                 GroupConfigService groupConfigs,
                                 RateLimiter callbackLimiter,
                                 Consumer<BotApiMethod<?>> proactiveSender) {
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.groupConfigs = groupConfigs;
        this.callbackLimiter = callbackLimiter;
        this.proactiveSender = proactiveSender;
    }

    /**
     * 执行一次按钮触发的命令。
     *
     * @param query    回调（点击者身份取自 {@code query.getFrom()}，由 Telegram 提供、不可伪造）
     * @param chatId   作用域群（由调用方从自己的 {@code callback_data} 解析——与
     *                 {@code VerificationCallbackHandler} 同一约定：data 由 bot 生成）
     * @param command  命令名（不含斜杠）
     * @param args     命令操作数；无则为 {@code null}
     * @param confirmed 本次是否已经过确认卡确认
     * @return 交给 webhook 的那一个方法；无话可说时为空
     */
    public Optional<BotApiMethod<?>> execute(CallbackQuery query, Long chatId, String command,
                                             String args, boolean confirmed) {
        if (query == null || chatId == null) {
            log.warn("回调缺少 chatId，已忽略");
            return Optional.empty();
        }
        Long userId = query.getFrom() == null ? null : query.getFrom().getId();
        if (userId == null) {
            log.warn("回调缺少点击者身份，已忽略");
            return Optional.empty();
        }
        if (command == null || registry.find(command).isEmpty()) {
            log.warn("回调引用了未注册的命令，已拒绝：command={}", command);
            return Optional.of(answer(query, UNKNOWN));
        }
        if (!callbackLimiter.tryAcquire("cb:u:" + userId)
                || !callbackLimiter.tryAcquire("cb:g:" + chatId)) {
            log.warn("按钮触发过于频繁，已限流：command={}", command);
            return Optional.of(answer(query, TOO_FAST));
        }

        UpdateContext ctx = new UpdateContext(null, userId, chatId, null, command, args);
        // 不变量 2：把群配置挂上去，门控里的「群开关」才真的生效
        ctx.attach(groupConfigs.findOrDefault(chatId));
        if (confirmed) {
            ctx.attach(new ConfirmationGranted());
        }

        Optional<BotApiMethod<?>> result;
        try {
            result = dispatcher.dispatch(ctx);
        } catch (Exception ex) {
            // 命令处理异常不得冒到 webhook（那会变成 500/重试风暴）
            log.warn("按钮触发的命令执行失败，已兜住：command={}", command, ex);
            return Optional.of(answer(query, FAILED));
        }

        // 结果走 webhook 返回值这条**可靠**通道；「应答」只是让按钮别转圈，属尽力而为的补充。
        // 反过来（结果走主动通道、返回 ack）会把主输出放到一条可能失败的通路上——实测教训：
        // 测试用假 token 时主动发送必然失败，命令产出就丢了，端到端测试无法观察它。
        if (result.isPresent()) {
            if (proactiveSender != null) {
                proactiveSender.accept(answer(query, null));
            }
            return result;
        }
        // 没有结果时（如权限不足被静默拒绝）也必须应答，否则按钮一直转圈
        return Optional.of(answer(query, null));
    }

    private static AnswerCallbackQuery answer(CallbackQuery query, String text) {
        return AnswerCallbackQuery.builder()
                .callbackQueryId(query.getId())
                .text(text)
                .build();
    }

    /**
     * 尽力而为地应答一次按钮点击（让客户端停止转圈）。
     *
     * <p>供**非命令产出**的卡片操作复用同一套取舍：产出（如 /menu 分类导航编辑后的卡片）走
     * webhook 返回值那条**可靠**通道，应答只是补充、走主动通道。未配置 bot token 时什么都不做
     * ——与 {@link #execute} 的既有行为一致（结果照旧送达，只是按钮可能转圈到超时）。
     */
    public void acknowledge(CallbackQuery query) {
        if (query != null) {
            sendSupplement(answer(query, null));
        }
    }

    /**
     * 尽力而为地发一个**补充**方法（典型用途：把确认卡置为终态）。
     *
     * <p>一次 webhook 只有一个返回槽，那条通路要留给**不可丢的产出**（命令结果、卡片终态本身）；
     * 其余界面更新走这里，失败只表现为「少一次更新」，不影响命令是否执行。
     * 未配置 bot token 时什么都不做——与 {@link #execute} 的既有行为一致。
     */
    public void sendSupplement(BotApiMethod<?> method) {
        if (proactiveSender != null && method != null) {
            proactiveSender.accept(method);
        }
    }
}
