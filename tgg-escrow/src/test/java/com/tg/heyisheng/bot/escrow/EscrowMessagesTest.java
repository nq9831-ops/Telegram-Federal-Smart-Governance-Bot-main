package com.tg.heyisheng.bot.escrow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 担保交易文案的守卫（VOICE 规范的可机器判定部分 + 担保交易特有约束）。
 *
 * <p><b>为什么要有本地守卫</b>：{@code VoiceConformanceTest} 的扫描面由它自己的
 * {@code MODULES} 常量决定，而 {@code tgg-escrow} 尚未登记进去——即本模块文案<b>不在</b>
 * 既有门禁的管辖内。在补齐登记之前，这层本地测试是唯一的抓手。
 */
class EscrowMessagesTest {

    /** 与 {@code VoiceConformanceTest} 判据 1 同款：模板符号与未填占位符。 */
    private static final Pattern TEMPLATE_MARK = Pattern.compile("[<>|]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d\\}|XXX|TODO|FIXME");

    /** 全部文案（<b>新增文案方法时请同步加入本列表</b>，否则新文案逃出本层守卫）。 */
    private static List<String> everyMessage() {
        return List.of(
                EscrowMessages.USAGE,
                EscrowMessages.LIST_EMPTY,
                EscrowMessages.created(1L, "100", "USDT"),
                EscrowMessages.awaitingDeposit(1L),
                EscrowMessages.lockedNotifySeller(1L),
                EscrowMessages.deliveredNotifyBuyer(1L, 7),
                EscrowMessages.released(1L),
                EscrowMessages.refunded(1L),
                EscrowMessages.cancelled(1L),
                EscrowMessages.advanced("确认", 1L, "待托管资金"),
                EscrowMessages.notFound(1L),
                EscrowMessages.onlyPartyAction(1L),
                EscrowMessages.statusLine(1L, "资金托管中，待交付", "100", "USDT", "11", "22"),
                EscrowMessages.listHeader(1),
                EscrowMessages.listItem(1L, "资金托管中，待交付", "100", "USDT"),
                EscrowMessages.notPartyToOrder(1L),
                EscrowMessages.cannotCancelAfterFundsHeld(1L));
    }

    @Test
    void everyStateHasAChineseName() {
        for (EscrowOrder.State state : EscrowOrder.State.values()) {
            assertThat(EscrowMessages.stateName(state.name()))
                    .as("状态 %s 必须有中文名——否则界面上会裸显英文枚举", state)
                    .doesNotContain("未知");
        }
    }

    @Test
    void unknownStateIsSurfacedRatherThanGuessed() {
        assertThat(EscrowMessages.stateName("SOMETHING_NEW"))
                .as("未知状态要原样带出（便于排查），不得静默映射成某个默认名")
                .contains("SOMETHING_NEW");
        assertThat(EscrowMessages.stateName(null)).isEqualTo("状态未知");
    }

    @Test
    void noMessageCarriesTemplateSymbolsOrPlaceholders() {
        for (String message : everyMessage()) {
            assertThat(TEMPLATE_MARK.matcher(message).find())
                    .as("文案含模板符号（<>|），会被用户整串照抄：%s", message)
                    .isFalse();
            assertThat(PLACEHOLDER.matcher(message).find())
                    .as("文案含未填占位符：%s", message)
                    .isFalse();
        }
    }

    @Test
    void rejectionAndFailureMessagesGiveAWayOut() {
        assertThat(EscrowMessages.notPartyToOrder(7L)).contains("你可以");
        assertThat(EscrowMessages.cannotCancelAfterFundsHeld(7L)).contains("你可以");
    }

    @Test
    void messageCarriesOrderIdAndAmountForReconciliation() {
        assertThat(EscrowMessages.created(42L, "100", "USDT"))
                .as("创建回执必须带编号与金额——双方对账的依据")
                .contains("#42")
                .contains("100");
        assertThat(EscrowMessages.deliveredNotifyBuyer(42L, 7))
                .as("验收提醒必须说明超时后果（争议窗口天数）")
                .contains("7");
    }
}
