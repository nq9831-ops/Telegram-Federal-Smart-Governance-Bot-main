package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.credit.CreditService;
import com.tg.heyisheng.bot.listing.merchant.Merchant;
import com.tg.heyisheng.bot.listing.merchant.MerchantProperties;
import com.tg.heyisheng.bot.listing.merchant.MerchantRepository;
import com.tg.heyisheng.bot.listing.merchant.MerchantService;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /merchant_status} 的单元测试（gap-02：商家查状态时可见自己的信用分）。
 *
 * <p><b>为什么走真服务而不是 mock 掉 {@link MerchantService}</b>：本条缺陷恰恰是「tier 由分值算出，
 * 但分值本身从不回显」。若把服务整个 mock 掉，测试只能断言「handler 调了某个方法」——而
 * 「分值经服务链路取到并落进回执」这条真正的因果就测不到了。这里用真服务 + 替身仓库 / 替身记账，
 * 让分值沿 {@code MerchantService → CreditService.scoreOf} 链路真的流到回执里。
 *
 * <p><b>RED 依据</b>：修复前 handler 只回编号 / 名称 / 状态 / 等级，回执里没有「信用分」。
 */
class MerchantStatusCommandHandlerTest {

    private static final long OWNER = 42L;
    private static final long CHAT = -100L;
    private static final long MERCHANT_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    private final MerchantRepository repository = mock(MerchantRepository.class);
    private final CreditService creditService = mock(CreditService.class);
    private final MerchantProperties properties = new MerchantProperties();
    private final MerchantService service =
            new MerchantService(repository, properties, creditService, Clock.fixed(NOW, ZoneOffset.UTC));
    private final MerchantStatusCommandHandler handler = new MerchantStatusCommandHandler(service);

    private static UpdateContext statusCtx(long userId) {
        return new UpdateContext(1, userId, CHAT, 5, "merchant_status", "");
    }

    /** 模拟「已持久化的 ACTIVE 商家」：真实链路上它必然从库里取出（id 非空），信用分主体是商家 id。 */
    private static Merchant activeMerchant() {
        Merchant merchant = withId(new Merchant(OWNER, "测试小铺", null, null, null, NOW), MERCHANT_ID);
        merchant.beginReview(NOW);
        merchant.decide(Merchant.Status.APPROVED, NOW);
        merchant.markDepositPending(NOW);
        merchant.markActive(NOW);
        merchant.assignTier("GOLD", NOW);
        return merchant;
    }

    private static Merchant withId(Merchant merchant, long id) {
        try {
            Field field = Merchant.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(merchant, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法为测试实体设置 id", ex);
        }
        return merchant;
    }

    private static String textOf(Object result) {
        assertThat(result).as("命令应回复一条消息").isInstanceOf(SendMessage.class);
        return ((SendMessage) result).getText();
    }

    @Test
    void statusReplyShowsOwnCurrentCreditScore() {
        when(repository.findByOwnerUserIdOrderByIdAsc(OWNER)).thenReturn(List.of(activeMerchant()));
        when(creditService.scoreOf(CreditSubjectType.MERCHANT, MERCHANT_ID)).thenReturn(500);

        String text = textOf(handler.handle(statusCtx(OWNER)));

        assertThat(text).as("回执应含本人商家的当前信用分").contains("信用分").contains("500");
        // 等级来自分值——两者应同在一条回执里，商家才看得出「分值 → 等级」的关系
        assertThat(text).contains("等级").contains("GOLD");
    }

    @Test
    void noApplicationRepliesWithoutAnyScore() {
        when(repository.findByOwnerUserIdOrderByIdAsc(OWNER)).thenReturn(List.of());

        String text = textOf(handler.handle(statusCtx(OWNER)));

        assertThat(text).contains("还没有提交过商家入驻申请");
        assertThat(text).as("无商家时没有分值可回显").doesNotContain("信用分");
    }

    @Test
    void unknownIdentityIsSilentlyIgnored() {
        // 身份缺失（userId == null）不得回任何东西，也不得去查库——与既有纪律一致
        assertThat(handler.handle(new UpdateContext(1, null, CHAT, 5, "merchant_status", ""))).isNull();
    }
}
