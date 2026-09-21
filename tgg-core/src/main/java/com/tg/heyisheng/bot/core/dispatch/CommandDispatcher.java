package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigView;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 命令分发器：按上下文中的命令路由到对应处理器，并在路由前做权限门控。
 *
 * <p>命令文本由 {@code UpdateDispatcher} 在构造上下文时用库的 {@code Message.getCommand()}
 * 提取（基于 Telegram 服务端标注的 {@code MessageEntity}(bot_command)）——因此
 * {@code /echo@MyBot} 等形式都能正确归一化。
 *
 * <p><b>权限不足时的行为是「静默忽略」</b>：不回复、不报错。
 * 这样不会向无权用户暴露命令是否存在（回复"权限不足"等于确认了命令存在）。
 */
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final CommandRegistry registry;
    private final PermissionChecker permissionChecker;
    /**
     * 确认卡接缝；为 {@code null}（老构造器）时**不拦截任何命令**——未装配即零影响。
     */
    private final ConfirmationRequests confirmationRequests;

    /** 默认用「全部按最小权限处理」的判定器——未装配权限源时不放行任何受限命令。 */
    public CommandDispatcher(CommandRegistry registry) {
        this(registry, new PermissionChecker(Role.MEMBER));
    }

    public CommandDispatcher(CommandRegistry registry, PermissionChecker permissionChecker) {
        this(registry, permissionChecker, null);
    }

    public CommandDispatcher(CommandRegistry registry, PermissionChecker permissionChecker,
                             ConfirmationRequests confirmationRequests) {
        this.registry = registry;
        this.permissionChecker = permissionChecker;
        this.confirmationRequests = confirmationRequests;
    }

    /**
     * 解析并路由命令。
     *
     * <p><b>只接收 {@link UpdateContext}</b>，不再从 Update 重新构造上下文——
     * 否则中间件链 enrich 到上下文里的信息传不到 handler。
     *
     * @return 处理器产出的 Bot API 调用；无命令、未知命令、权限不足或处理器返回 null 时为空
     */
    public Optional<BotApiMethod<?>> dispatch(UpdateContext ctx) throws Exception {
        Optional<CommandHandler> resolvable = resolvableHandler(ctx);
        if (resolvable.isEmpty()) {
            return Optional.empty();
        }

        String command = ctx.command().orElseThrow();

        // 确认卡：在**执行之前**拦一道。放在这里而不是各 handler 里的理由——
        // 危险命令无需各自记得「先确认」，标注一处即对所有入口生效（文本命令、按钮、将来的 Web），
        // 不会有漏网的调用路径。
        if (confirmationRequests != null) {
            Confirm mode = registry.confirmationOf(command);
            if (mode != Confirm.NEVER && confirmationRequests.requiresConfirmation(ctx, mode)) {
                return Optional.of(confirmationCard(ctx, command, confirmationRequests.issue(ctx)));
            }
        }

        try {
            return Optional.ofNullable(resolvable.get().handle(ctx));
        } catch (Exception ex) {
            // 统一包装为项目异常，便于上游 @RestControllerAdvice 识别与记录
            throw new TggDispatchException("命令处理失败：" + command, ex);
        }
    }

    /**
     * 确认卡：复述将要执行的命令，给出「确认 / 取消」。
     *
     * <p>复述参数是刻意的——用户点按钮时多半已忘了当初打的什么，卡片必须把「将要发生什么」写清楚。
     *
     * <p><b>不写「该操作不可撤销」</b>：被标需要确认的 9 条命令里，`/disable`（可 `/enable` 回来）、
     * `/data_breach`（追加一条合规记录）、`/merchant_exit`（走申请流程）都不是严格不可逆。
     * 对**所有**命令一律断言「不可撤销」会让用户学会无视这句话，反而削弱了真正不可逆那几条的警示。
     * 改成准确且不说满的措辞，把注意力锚在唯一真正能防的错误——参数/命令看错。
     */
    private static BotApiMethod<?> confirmationCard(UpdateContext ctx, String command, String nonce) {
        String args = ctx.commandArgs().orElse(null);
        // 命令名先经 CommandRegistry.normalize 再显示：库的 Message.getCommand() 会带上**前导斜杠**，
        // 这里再前缀一个 "/" 就会渲染成 "//review_approve"（实测：合成请求的确认卡即如此）。
        // normalize 负责去斜杠、去 @BotName 后缀、转小写——正是显示所需的口径。
        String preview = "/" + CommandRegistry.normalize(command)
                + (args == null || args.isBlank() ? "" : " " + args);

        InlineKeyboardButton confirm = InlineKeyboardButton.builder()
                .text(DispatchMessages.CONFIRM_BUTTON)
                .callbackData(ConfirmationRequests.CONFIRM_ACTION + ":" + nonce)
                .build();
        InlineKeyboardButton cancel = InlineKeyboardButton.builder()
                .text(DispatchMessages.CANCEL_BUTTON)
                .callbackData(ConfirmationRequests.CANCEL_ACTION + ":" + nonce)
                .build();

        return SendMessage.builder()
                .chatId(String.valueOf(ctx.chatId()))
                .text(DispatchMessages.confirmPrompt(preview))
                .replyMarkup(InlineKeyboardMarkup.builder()
                        .keyboard(List.of(new InlineKeyboardRow(confirm), new InlineKeyboardRow(cancel)))
                        .build())
                .build();
    }

    /**
     * 这条更新是否会**真的执行**一条命令（已注册 + 权限足够 + 群开关允许）。
     *
     * <p><b>供审核层使用</b>：内容审核对命令消息的豁免必须以此为条件，而**不能**用
     * {@code Message.isCommand()}——后者只表示"文本看起来像命令"（有 offset 0 的 bot_command
     * entity），与命令是否注册、发送者有无权限毫无关系。若拿它当豁免依据，任何成员只要把违规内容
     * 写成 {@code /任意词 <违规内容>} 就能绕过内容审核（消息不会被删，命令又因未注册/无权限而不执行）。
     *
     * <p>注意：本判定发生在中间件链<b>之前</b>时会拿不到群配置（群开关退化为"启用"）——
     * 该边界已在 KNOWN-ISSUES 记录，影响面仅"管理员在已停用群内发命令"。
     */
    public boolean willExecute(UpdateContext ctx) {
        return resolvableHandler(ctx).isPresent();
    }

    /**
     * 参数承载<b>用户手写正文</b>的公开命令——它们的自由文本必须豁免审核，否则会「自己拦掉自己」：
     * {@code /case_appeal 7 <理由>} 的理由里一旦出现违禁词，**申诉本身**就被删了，当事人永远申诉不了。
     *
     * <p>名单刻意**只列这三条**（申诉 / 申请类）。其余公开命令（{@code /echo}、{@code /quiet_hours}、
     * {@code /whoami}、{@code /menu}…）的参数要么固定、要么不被系统消费，**照常送审**。
     */
    private static final Set<String> MODERATION_EXEMPT_PUBLIC_COMMANDS =
            Set.of("case_appeal", "listing_appeal", "merchant_apply");

    /**
     * 这条更新是否**豁免内容审核**（供审核层使用）。
     *
     * <p>判据是「会真的执行一条命令」<b>且</b>属于下列之一：
     * <ol>
     *   <li><b>有权限要求的命令</b>——管理/运营命令属**控制面**（{@code /delword <词>} 的参数天然
     *       含该词，送审会被自己的规则删掉，命令永不执行）；</li>
     *   <li><b>白名单里的公开命令</b>——参数是用户手写的申诉/申请正文，送审会拦掉申诉本身。</li>
     * </ol>
     *
     * <p>⚠️ <b>不能只看「已注册且在权限上放行」</b>：{@code /echo} 这类自助命令对**任何成员**
     * 都满足该条件，而它的参数会被原样留在群里——若一并豁免，任何成员发
     * {@code /echo <违规内容>} 就能让违规正文**免于删除**（消息不删、命令照常执行、无审核记录）。
     */
    public boolean exemptFromModeration(UpdateContext ctx) {
        if (resolvableHandler(ctx).isEmpty()) {
            return false;
        }
        String command = CommandRegistry.normalize(ctx.command().orElseThrow());
        return registry.requiredPermission(command) != Permission.NONE
                || MODERATION_EXEMPT_PUBLIC_COMMANDS.contains(command);
    }

    /**
     * 依次施加三道门禁（已注册 → 权限 → 群开关），返回可执行的处理器。
     *
     * <p>抽出来是为了让 {@link #dispatch} 与 {@link #willExecute} 共用同一套判定条件——
     * 两处若各写一份，迟早会漂移成"审核以为会执行、实际不执行"或反之。
     */
    private Optional<CommandHandler> resolvableHandler(UpdateContext ctx) {
        if (ctx == null || !ctx.hasCommand()) {
            return Optional.empty();
        }

        String command = ctx.command().orElseThrow();
        Optional<CommandHandler> handler = registry.find(command);
        if (handler.isEmpty()) {
            return Optional.empty();
        }

        Permission required = registry.requiredPermission(command);
        if (!permissionChecker.has(ctx.chatId(), ctx.userId(), required)) {
            log.debug("权限不足（需要 {}），忽略命令 {}", required, command);
            return Optional.empty();
        }

        // 群功能开关：关闭时只放行「恢复类」命令。
        // 若在此处一刀切地拒绝，被停用的群连 /enable 都进不来——会永久锁死。
        if (!registry.worksWhenDisabled(command) && !isGroupEnabled(ctx)) {
            log.debug("群 {} 功能已关闭，忽略命令 {}", ctx.chatId(), command);
            return Optional.empty();
        }

        return handler;
    }

    /**
     * 读取上下文里由 {@code GroupConfigMiddleware} 挂载的开关状态。
     *
     * <p>未挂载时按「启用」处理——这保证不装配该中间件的场景（如单元测试）行为不变。
     */
    private static boolean isGroupEnabled(UpdateContext ctx) {
        return ctx.find(GroupConfigView.class).map(GroupConfigView::enabled).orElse(true);
    }
}
