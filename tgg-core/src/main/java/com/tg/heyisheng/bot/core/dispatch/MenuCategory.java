package com.tg.heyisheng.bot.core.dispatch;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code /menu} 面板的**业务域分类**（模块九 §7.4 可见性接缝的配套）。
 *
 * <p><b>为什么是枚举而不是映射表</b>：命令属于哪一类，由命令自己在
 * {@link BotCommand#category()} 里声明——与 {@code requiredPermission}、{@code confirm} 同款。
 * 若在菜单侧维护一份「命令 → 分类」的表，新增命令时必然有人忘了登记，表会**悄悄过期**
 * （本项目在 {@code MenuCallbackHandler} 的用法文案上已有同类教训）。
 *
 * <p><b>{@link #OTHER} 是兜底而非垃圾桶</b>：未分类但**可见**的命令会落进这里并照常显示
 * （fail-visible——宁可多一个「其他」，也不让命令从面板里静默消失）。测试锁住「能进面板的命令
 * 都得有明确分类」，防止长期依赖兜底。
 *
 * <p><b>{@code key}</b>：回调 {@code data} 里用的稳定标识（小写枚举名），据此回查分类，
 * 不把中文标题塞进 {@code data}（长度与转义都更可控）。
 */
public enum MenuCategory {

    /** 自助功能：个人偏好与自助操作（免打扰、数据导出、连通性测试、申诉等）。 */
    SELF_SERVICE(DispatchMessages.CATEGORY_SELF_SERVICE),

    /** 内容审核：词库、教学规则等。 */
    MODERATION(DispatchMessages.CATEGORY_MODERATION),

    /** 群设置：群级开关与群属性声明。 */
    GROUP(DispatchMessages.CATEGORY_GROUP),

    /** 复核合规：平台层复核队列、合规证据、联邦申诉裁决。 */
    REVIEW(DispatchMessages.CATEGORY_REVIEW),

    /** 群组收录（模块五）：收录库与链接验证——把好群收进目录、浏览与申诉。 */
    LISTING(DispatchMessages.CATEGORY_LISTING),

    /**
     * 商家收录（模块六）：入驻申请链（公众自助：apply/status/exit）与商家管理
     * （联邦管理员独占：review/deposit/settle——「商家只有联邦管理员才可以审核处理」，2026-09-22）。
     * 两族**按角色切片呈现**——管理侧只见管理
     * （{@code MerchantSubmissionCuration}），公众侧只见自助；此前与群组收录混在一枚
     * 「收录商家」分类里，正是用户点名的「功能模糊」（2026-09-22 拆分）。
     */
    MERCHANT(DispatchMessages.CATEGORY_MERCHANT),

    /**
     * 担保交易（模块十二）：发起、托管、交付、验收、争议、查询。
     *
     * <p>单列一类而不是并进「其他」——它是资金面，用户在 {@code /menu} 里需要一眼找到；
     * 落 {@link #OTHER} 会让它混进未分类项里（那正是本枚举存在的意义）。
     */
    ESCROW(DispatchMessages.CATEGORY_ESCROW),

    /** 兜底：尚未声明分类的可见命令。 */
    OTHER(DispatchMessages.CATEGORY_OTHER);

    private final String title;

    MenuCategory(String title) {
        this.title = title;
    }

    /** 面板上显示的中文分类名。 */
    public String title() {
        return title;
    }

    /** 回调 {@code data} 里使用的稳定 key（小写枚举名）。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 由 {@link #key()} 反查分类。
     *
     * <p>不认识（含 {@code null}、大小写不符、空白）一律返回空——调用方据此判「非法 key」，
     * 不猜、不静默降级。
     */
    public static Optional<MenuCategory> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (MenuCategory category : values()) {
            if (category.key().equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
