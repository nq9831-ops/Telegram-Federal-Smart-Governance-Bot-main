package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfig;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigRepository;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;

import java.util.List;

/**
 * 联邦处罚的本地执行（模块八）：把已接受的处罚令落到**本节点已知各群**的封禁。
 *
 * <p><b>群范围</b>：取 {@code group_configs} 中**启用**的群——本节点未被收录的群无法封禁
 * （已记录为已知限制）。
 *
 * <p><b>本阶段只处理个人维度</b>：{@link CreditSubjectType#INDIVIDUAL} 才对应"封人"；
 * 群组/商家维度的动作待后续（不静默做错事）。
 *
 * <p><b>失败不抛</b>：单群封禁失败只记日志，不影响其他群、也不回滚"处罚令已接受"这一事实。
 */
public class FederationBanService implements FederationBanHandler {

    private static final Logger log = LoggerFactory.getLogger(FederationBanService.class);

    private final GroupConfigRepository groupConfigRepository;
    private final ModerationActionSender actionSender;

    public FederationBanService(GroupConfigRepository groupConfigRepository,
                                ModerationActionSender actionSender) {
        this.groupConfigRepository = groupConfigRepository;
        this.actionSender = actionSender;
    }

    @Override
    public void onPenaltyAccepted(CreditPenaltyOrder order) {
        if (order == null || order.subjectType() != CreditSubjectType.INDIVIDUAL) {
            return;
        }
        List<Long> chatIds = groupConfigRepository.findAll().stream()
                .filter(GroupConfig::isEnabled)
                .map(GroupConfig::getChatId)
                .toList();
        if (chatIds.isEmpty()) {
            log.info("联邦封禁：本节点无已知启用群，无处执行（userId 已脱敏，不在日志中记录）");
            return;
        }
        for (Long chatId : chatIds) {
            try {
                actionSender.send(BanChatMember.builder()
                        .chatId(chatId)
                        .userId(order.subjectId())
                        .build());
            } catch (RuntimeException ex) {
                // 单群失败不影响其他群，也不上抛
                log.warn("联邦封禁在群 {} 执行失败（已继续其他群）", chatId, ex);
            }
        }
        log.info("联邦封禁已下发至 {} 个群", chatIds.size());
    }
}
