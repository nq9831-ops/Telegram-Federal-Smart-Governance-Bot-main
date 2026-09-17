package com.tg.heyisheng.bot.core.groupconfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 群组配置读写。
 */
@Service
public class GroupConfigService {

    private static final Logger log = LoggerFactory.getLogger(GroupConfigService.class);

    private final GroupConfigRepository repository;

    public GroupConfigService(GroupConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 查询群组配置；未登记的群组返回默认配置（不视为错误）。
     *
     * <p><b>数据库故障时 fail-open（放行）</b>：群组配置是**增强**而非**门禁**，
     * 数据库抖动不应让机器人整体停止响应。
     *
     * <p>但必须记 ERROR 级日志——静默降级会造成「配置明明改了却不生效」
     * 且事后无从排查。
     *
     * <p><b>例外</b>：若将来某配置项是安全门禁（如黑名单判定），那条路径须改为
     * fail-close。届时单独设计，不在此处一刀切。
     */
    @Transactional(readOnly = true)
    public GroupConfigView findOrDefault(Long chatId) {
        if (chatId == null) {
            return GroupConfigView.defaultFor(null);
        }
        try {
            return repository.findById(chatId)
                    .map(c -> new GroupConfigView(c.getChatId(), c.getTitle(), c.isEnabled()))
                    .orElseGet(() -> GroupConfigView.defaultFor(chatId));
        } catch (RuntimeException ex) {
            log.error("读取群组配置失败（chatId={}），本次按默认配置放行", chatId, ex);
            return GroupConfigView.defaultFor(chatId);
        }
    }

    /** 登记群组（幂等：已存在则只更新标题）。 */
    @Transactional
    public GroupConfig register(Long chatId, String title) {
        return repository.findById(chatId)
                .map(existing -> {
                    existing.setTitle(title);
                    return existing;
                })
                .orElseGet(() -> repository.save(new GroupConfig(chatId, title)));
    }

    /** 开关某群的自动化能力。 */
    @Transactional
    public GroupConfigView setEnabled(Long chatId, boolean enabled) {
        GroupConfig config = repository.findById(chatId)
                .orElseGet(() -> repository.save(new GroupConfig(chatId, null)));
        config.setEnabled(enabled);
        return new GroupConfigView(config.getChatId(), config.getTitle(), config.isEnabled());
    }
}
