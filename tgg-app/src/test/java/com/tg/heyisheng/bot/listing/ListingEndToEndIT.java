package com.tg.heyisheng.bot.listing;

import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.listing.notify.SubmitterNotifier;
import com.tg.heyisheng.bot.listing.verification.GroupLinkVerificationJob;
import com.tg.heyisheng.bot.listing.verification.GroupLinkVerifier;
import com.tg.heyisheng.bot.listing.verification.Sleeper;
import com.tg.heyisheng.bot.listing.verification.VerificationRecordRepository;
import com.tg.heyisheng.bot.listing.verification.VerificationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块五端到端测试（<b>不 mock 中间层</b>）：真实 {@code UpdateDispatcher} 链路 + 真实命令注册表
 * + 真实 MySQL，把「提交收录 → 定时验证连续失败 → 软删下架 → 通知提交者 → 申诉」整条走通。
 *
 * <p><b>只替换两个接缝</b>：
 * <ul>
 *   <li>{@link GroupLinkVerifier} —— 真实探针需要 token + 公网，本机必然走 ERROR（探针不可用 ≠ 群失效），
 *       故用「恒返回 FAIL」的替身来驱动状态机；</li>
 *   <li>{@link SubmitterNotifier} —— 用<b>可捕获</b>的替身断言「通知真的被调用」，
 *       而不是断言日志里出现了某行字（日志实现随时可换，调用才是契约）。</li>
 * </ul>
 * 服务、仓库、状态机、命令分发、权限门控<b>全部是真的</b>——这正是本测试存在的意义：
 * 单测能证明状态机逻辑，但证明不了「命令真的注册上了、链路真的通电」。
 *
 * <p><b>为什么值得跑真链路</b>：命令注册表是「一个命令名 → 一个处理器」的严格映射，
 * 重复注册同名命令会在上下文档构造期直接失败。这条测试以真实上下文启动，
 * 因此它是「三个命令处理器能共存」的守门人。
 */
@SpringBootTest(properties = {
        "tgg.listing.enabled=true",
        // 关掉重试退避：本测试要的是「连打 3 轮各记 1 次失败」，而不是真等 5 分钟 × N
        "tgg.listing.retry-times=0",
        // 本测试用自己专用的群与管理員，故覆盖测试 profile 的授权清单
        "tgg.permission.admins=-100900001:42:ADMIN"
})
class ListingEndToEndIT {

    private static final long CHAT_ID = -100900001L;
    private static final long ADMIN_USER = 42L;
    private static final long OTHER_USER = 999L;
    private static final String INVITE_LINK = "https://t.me/+e2eListing";

    /** Telegram 单条消息硬上限（超长整条发送失败）。 */
    private static final int TELEGRAM_TEXT_LIMIT = 4096;

    /** 被真实调用到的下架通知（可捕获替身）。跨用例清空。 */
    private static final List<ListingGroup> NOTIFIED = new CopyOnWriteArrayList<>();

    /** 真实链路的三处替身：探针恒 FAIL、通知被捕获、等待不真等。 */
    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        GroupLinkVerifier alwaysFailingVerifier() {
            return entry -> VerificationResult.FAIL;
        }

        @Bean
        @Primary
        SubmitterNotifier capturingNotifier() {
            return NOTIFIED::add;
        }

        /** 记录即返回：即便配置里忘了关重试，也不会让本测试真等 5 分钟。 */
        @Bean
        @Primary
        Sleeper noopSleeper() {
            return duration -> {
            };
        }
    }

    @Autowired
    private UpdateDispatcher updateDispatcher;

    @Autowired
    private GroupLinkVerificationJob job;

    @Autowired
    private ListingGroupService listingService;

    @Autowired
    private ListingGroupRepository groups;

    @Autowired
    private ListingAppealRepository appeals;

    @Autowired
    private VerificationRecordRepository records;

    @Autowired
    private GroupConfigService groupConfigService;

    @BeforeEach
    void setUp() {
        clean();
        // 前置：本群处于启用态（群开关关闭时命令一律被拒，与权限无关）
        groupConfigService.setEnabled(CHAT_ID, true);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        NOTIFIED.clear();
        appeals.deleteAll();
        records.deleteAll();
        groups.deleteAll();
    }

    /** 主链路：提交（幂等）→ 三轮 FAIL → 软删 → 通知提交者 → 申诉落 PENDING。 */
    @Test
    void submitThenThresholdFailuresSuspendAndNotifySubmitter() throws Exception {
        // 1. 收录提交（经真实命令链路：注册表 → 权限 → 处理器 → 幂等插入）
        assertThat(replyTo("/listing_add " + INVITE_LINK, ADMIN_USER))
                .as("首次收录应新建条目")
                .isEqualTo("收录已经提交，我会定期验证链接是否还有效。");

        // 2. 重复提交被幂等吸收：不抛异常、不产生第二行
        assertThat(replyTo("/listing_add " + INVITE_LINK, ADMIN_USER))
                .as("重复提交应被忽略")
                .isEqualTo("这个群已经在收录库里了（重复提交忽略了）。");
        assertThat(groups.findAll()).as("同 chatId 只应有一行").hasSize(1);

        ListingGroup entry = groups.findAll().get(0);
        Long listingId = entry.getId();
        assertThat(entry.getStatus()).isEqualTo(ListingGroup.Status.ACTIVE.name());
        assertThat(entry.getSubmitterUserId()).isEqualTo(ADMIN_USER);

        // 3. 连打 3 轮（每轮 1 次 FAIL）：前两轮仍 ACTIVE 且不发通知
        job.verifyAllActive();
        job.verifyAllActive();
        assertThat(groups.findById(listingId).orElseThrow().getStatus())
                .as("2 次失败仍应 ACTIVE（阈值 3）")
                .isEqualTo(ListingGroup.Status.ACTIVE.name());
        assertThat(NOTIFIED).as("未判失效不得发下架通知").isEmpty();

        // 4. 第 3 轮：转 SUSPENDED（软删）+ 通知提交者
        job.verifyAllActive();

        ListingGroup reloaded = groups.findById(listingId).orElseThrow(
                () -> new AssertionError("软删：行必须仍在库中（可审计 / 可申诉），绝不物理删"));
        assertThat(reloaded.getStatus()).isEqualTo(ListingGroup.Status.SUSPENDED.name());
        assertThat(reloaded.getFailCount()).isEqualTo(3);
        assertThat(reloaded.getSuspendedAt()).isNotNull();

        assertThat(listingService.activeGroups()).as("下架后不再出现在有效集合中").isEmpty();
        assertThat(groups.findByStatusOrderByIdAsc(ListingGroup.Status.SUSPENDED.name()))
                .as("SUSPENDED 侧仍可查（审计入口）")
                .hasSize(1);

        assertThat(records.findByListingIdOrderByIdAsc(listingId))
                .as("每次验证都留痕，且不含消息正文")
                .hasSize(3);

        // 5. 通知必须真的被调用到（断言调用，不断言日志）
        assertThat(NOTIFIED).as("判失效当刻应通知提交者，且恰好一次").hasSize(1);
        assertThat(NOTIFIED.get(0).getId()).isEqualTo(listingId);
        assertThat(NOTIFIED.get(0).getSubmitterUserId()).isEqualTo(ADMIN_USER);
        assertThat(NOTIFIED.get(0).getChatId()).isEqualTo(CHAT_ID);

        // 6. 他人不得代为申诉（提交者以外一律拒绝，且不落库）
        assertThat(replyTo("/listing_appeal " + listingId + " 我朋友说这群还在", OTHER_USER))
                .isEqualTo("只有这个群的提交者本人能申诉。");
        assertThat(appeals.findByListingIdOrderByIdAsc(listingId)).isEmpty();

        // 7. 提交者本人申诉：落 PENDING
        assertThat(replyTo("/listing_appeal " + listingId + " 群只是改成了邀请制，并未失效", ADMIN_USER))
                .startsWith("申诉已提交（编号 #");

        List<ListingAppeal> submitted = appeals.findByListingIdOrderByIdAsc(listingId);
        assertThat(submitted).hasSize(1);
        assertThat(submitted.get(0).getStatus()).isEqualTo(ListingAppeal.Status.PENDING.name());
        assertThat(submitted.get(0).getListingId()).isEqualTo(listingId);
        assertThat(submitted.get(0).getUserId()).isEqualTo(ADMIN_USER);
        assertThat(submitted.get(0).getCreatedAt()).isNotNull();
    }

    /** {@code /listing_list} 必须截断：Telegram 单条上限 4096，超长会整条发送失败。 */
    @Test
    void listReplyIsTruncatedBecomesWithinTelegramLimitAndLeaksNoInviteLinks() throws Exception {
        String longTitle = "超长群标题".repeat(20);
        for (int i = 0; i < 150; i++) {
            listingService.submit(CHAT_ID - 1 - i, "https://t.me/+bulk" + i, longTitle, ADMIN_USER);
        }
        assertThat(listingService.activeGroups()).hasSize(150);

        String reply = replyTo("/listing_list", OTHER_USER);
        assertThat(reply.length())
                .as("回复不得超过 Telegram 单条上限（超长会整条发送失败）")
                .isLessThanOrEqualTo(TELEGRAM_TEXT_LIMIT);
        assertThat(reply).as("超长时必须显式说明被截断").contains("已截断");
        assertThat(reply).as("不得公示邀请链接（t.me 链接的 token 等同入群凭证）")
                .doesNotContain("t.me");
        assertThat(reply).startsWith("收录库（有效）：");
    }

    /** 经真实分发链发送命令，取回回复正文。 */
    private String replyTo(String commandText, long userId) throws Exception {
        Optional<BotApiMethod<?>> reply = updateDispatcher.dispatch(update(commandText, userId));
        assertThat(reply).as("命令 %s 应被真的执行并产出回复", commandText).isPresent();
        return ((SendMessage) reply.orElseThrow()).getText();
    }

    private static Update update(String commandText, long userId) {
        int commandLength = commandText.indexOf(' ') < 0 ? commandText.length() : commandText.indexOf(' ');
        MessageEntity entity = MessageEntity.builder()
                .type(EntityType.BOTCOMMAND)
                .offset(0)
                .length(commandLength)
                .build();

        Message message = Message.builder()
                .messageId(1)
                .text(commandText)
                .entities(List.of(entity))
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(userId).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }
}
