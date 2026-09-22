package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /menu} 的目录装配：把「对该用户可见的命令」算出来，并按 {@link MenuCategory} 分组。
 *
 * <p><b>它回答两个问题</b>：此人能看见哪些命令（可见性）、这些命令各归哪一类（分组）。
 * 键盘渲染在 {@link MenuView}，本类不碰 Telegram 组装——判定与呈现分开，便于单测。
 *
 * <p><b>注册表必须惰性取用</b>：{@code CommandRegistry} 由全部 {@code CommandHandler} 构造，
 * 而 {@code /menu} 自身也是一个 handler——直接依赖会构成构造期环，上下文起不来
 * （实测教训见 {@code MenuCommandHandler} 的 javadoc）。故收 {@code ObjectProvider}，
 * 真正取用发生在请求时刻，那时注册表早已就绪。
 *
 * <p><b>可见性的三条来源必须与执行门控逐条对齐</b>（{@code CommandDispatcher.resolvableHandler}）：
 * <ol>
 *   <li>有接缝的命令 → 接缝判定（各模块自己的平台白名单 Guard，与 handler 内用的是同一个）；</li>
 *   <li>其余命令 → {@code requiredPermission != NONE} 才收（保持升级前语义），再走
 *       {@link PermissionChecker}；无权限点但声明了 {@code publicCommand} 的「自助命令」对所有人可见
 *       （客户端 {@code /} 菜单已收敛为只留 {@code /menu}，它们是唯一入口之外的发现路径）；</li>
 *   <li>群开关 → {@code groupEnabled || worksWhenDisabled}。</li>
 * </ol>
 * 任一不一致都会让面板显示「点了却无声」或（更糟）对无权者暴露命令。
 */
public class MenuCatalog {

    /** 面板命令自身不进目录（它没有「所属功能」可言）。 */
    private static final String PANEL_COMMAND = MenuCommandHandler.CALLBACK_ACTION;

    private final ObjectProvider<CommandRegistry> registryProvider;
    private final PermissionChecker permissionChecker;
    /** command → 负责它的接缝；无则为「走注册表权限」的那批。 */
    private final Map<String, MenuVisibility> seamByCommand;

    public MenuCatalog(ObjectProvider<CommandRegistry> registryProvider,
                       PermissionChecker permissionChecker,
                       List<MenuVisibility> seams) {
        this.registryProvider = registryProvider;
        this.permissionChecker = permissionChecker;
        this.seamByCommand = index(seams);
    }

    /**
     * 建立「命令 → 接缝」索引。
     *
     * <p>重复登记在**装配期**即失败（同 {@code CommandRegistry} 的命令名冲突口径）——
     * 两条接缝都声称负责一条命令属于配置错误，留到运行期只会表现为「谁最后装配谁说了算」，
     * 排查成本远高于启动即报。
     */
    private static Map<String, MenuVisibility> index(List<MenuVisibility> seams) {
        Map<String, MenuVisibility> map = new HashMap<>();
        for (MenuVisibility seam : seams == null ? List.<MenuVisibility>of() : seams) {
            for (String command : seam.commands()) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                MenuVisibility existing = map.putIfAbsent(command, seam);
                if (existing != null) {
                    throw new IllegalStateException("MenuVisibility 命令重复登记：" + command);
                }
            }
        }
        return Map.copyOf(map);
    }

    /** 命令名 → 描述（供渲染按钮文案；取自注册表，不另存一份）。 */
    public Map<String, String> descriptions() {
        return registryProvider.getObject().mainCommands();
    }

    /** 该用户在此群可见、且属于 /menu 的命令（保持注册表的名称顺序）。 */
    public List<String> visibleCommands(long chatId, Long userId, boolean groupEnabled) {
        CommandRegistry registry = registryProvider.getObject();
        // 场景规则：私聊里群限定命令（handler 的 chatId>=0 硬门）不可用——不进「可用」列表，
        // 否则点了只会撞「这条命令得在群里发才管用」，即本类 javadoc 点名的「点了却无声」。
        boolean privateChat = userId != null && IdentityPresenter.isPrivate(chatId, userId);
        return registry.mainCommands().keySet().stream()
                .filter(name -> !PANEL_COMMAND.equals(name))
                .filter(name -> !privateChat || !registry.groupOnly(name))
                .filter(name -> isVisible(name, chatId, userId, registry))
                .filter(name -> groupEnabled || registry.worksWhenDisabled(name))
                .toList();
    }

    /**
     * 「这些得到群里用」——群限定命令中，该用户在**任一群**有权使用的那些（存在量词）。
     *
     * <p>私聊场景没有「当前群」语义（chatId == userId，群管理员身份不跟过来），跨场景提示
     * 只能按存在量词回答「你在某个群能用它」。数据源与执行门同一份 {@code RoleSource.grants()}。
     *
     * <p>fail-closed：无身份不列；无权限不列；与 {@link #isVisible} 的三条来源逐条对齐
     * （接缝 → 接缝判定；无权限点 → 须 publicCommand；否则须在任一群有该权限点）。
     */
    public List<String> groupBoundCommands(Long userId) {
        CommandRegistry registry = registryProvider.getObject();
        List<String> result = new ArrayList<>();
        for (String name : registry.mainCommands().keySet()) {
            if (registry.groupOnly(name) && visibleInSomeGroup(name, userId, registry)) {
                result.add(name);
            }
        }
        return List.copyOf(result);
    }

    /** 与 {@link #isVisible} 同构的「任一群」判定：接缝优先，其次公开性/权限（存在量词）。 */
    private boolean visibleInSomeGroup(String command, Long userId, CommandRegistry registry) {
        MenuVisibility seam = seamByCommand.get(command);
        if (seam != null) {
            // 平台白名单是全局的、与群无关（各接缝实现同此口径）——chatId 传 0 仅为接口统一
            return seam.visible(0L, userId);
        }
        Permission required = registry.requiredPermission(command);
        if (required == Permission.NONE) {
            return registry.publicCommand(command);
        }
        return permissionChecker.hasInAnyGroup(userId, required);
    }

    /** 该命令是否群限定（{@code @BotCommand(groupOnly)} 声明；供 /help 群聊版打「（得在群里用）」标注）。 */
    public boolean isGroupOnly(String command) {
        return registryProvider.getObject().groupOnly(command);
    }

    /**
     * 可见命令按分类分组；只含**非空**分类。
     *
     * <p>顺序：分类按 {@link MenuCategory} 的声明顺序（{@code LinkedHashMap} + 遍历
     * {@code values()} 无关，这里按命令出现顺序建立，故显式按枚举序重排，见下）。
     */
    public Map<MenuCategory, List<String>> grouped(long chatId, Long userId, boolean groupEnabled) {
        CommandRegistry registry = registryProvider.getObject();
        Map<MenuCategory, List<String>> byCommandOrder = new LinkedHashMap<>();
        for (String command : visibleCommands(chatId, userId, groupEnabled)) {
            byCommandOrder.computeIfAbsent(registry.categoryOf(command), key -> new ArrayList<>())
                    .add(command);
        }
        // 重排为「枚举声明顺序」——面板按钮的顺序不该随命令名漂移
        Map<MenuCategory, List<String>> ordered = new LinkedHashMap<>();
        for (MenuCategory category : MenuCategory.values()) {
            List<String> commands = byCommandOrder.get(category);
            if (commands != null && !commands.isEmpty()) {
                ordered.put(category, List.copyOf(commands));
            }
        }
        return ordered;
    }

    /**
     * 单条命令的可见性判定。
     *
     * <p>三条来源，与执行门控逐条对齐：
     * <ol>
     *   <li><b>接缝优先</b>：某命令若被某接缝认领，**只**由该接缝判定（不再叠加注册表权限）——
     *       白名单类命令的注解权限是 {@code NONE}，若再走 {@code PermissionChecker} 会因
     *       {@code NONE} 恒过而与接缝结论冲突；</li>
     *   <li><b>自助命令</b>（无权限点 + 声明 {@code publicCommand}）→ 对全体成员可见
     *       （客户端 {@code /} 菜单已收敛为只留 {@code /menu}，这些命令必须能从面板到达）；</li>
     *   <li><b>其余有权限点的命令</b> → 走 {@link PermissionChecker}。</li>
     * </ol>
     *
     * <p>无权限点、非接缝、又未声明 {@code publicCommand} 的命令**不进面板**（fail-closed）——
     * 这类命令既不该对所有人可见，也不该经面板暴露；其存在应由
     * {@code CommandMenuRegistrar.planMenus} 的启动告警与 {@code CommandMenuContentTest} 拦下。
     */
    private boolean isVisible(String command, long chatId, Long userId, CommandRegistry registry) {
        MenuVisibility seam = seamByCommand.get(command);
        if (seam != null) {
            return seam.visible(chatId, userId);
        }
        Permission required = registry.requiredPermission(command);
        if (required == Permission.NONE) {
            // 无权限点的命令：只有显式声明 publicCommand 的「自助命令」才进面板（fail-closed）
            return registry.publicCommand(command);
        }
        return permissionChecker.has(chatId, userId, required);
    }

    /** 已登记的接缝覆盖了多少条命令（供装配测试与诊断）。 */
    int seamCoverage() {
        return seamByCommand.size();
    }
}
