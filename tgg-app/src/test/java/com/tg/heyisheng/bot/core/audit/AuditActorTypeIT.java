package com.tg.heyisheng.bot.core.audit;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.notify.NotificationPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计主体隔离的<b>真库测试</b>——本波（Wave 1）的关键 RED 证据。
 *
 * <p><b>它守的不变量</b>：后台账号 id 与 Telegram userId 落在<b>同一个 BIGINT 数值空间</b>里，
 * 因此「按主体取记录」<b>必须</b>是 (类型, id) 双条件。若退回裸 {@code actor_id} 单条件，
 * id=42 的后台账号记录会被 userId=42 的 TG 用户读到——{@code /export_my_data} 正是这条路径。
 *
 * <p><b>为什么必须真库</b>：mock 仓库无法表达「同 id 两类型是否真串号」——它只是照着调用返回。
 * 只有真实 SQL（带 {@code actor_type} 条件的 WHERE）才能证明隔离成立、单条件真的会漏。
 *
 * <p><b>RED 锚点（{@link #exportHandlerDoesNotLeakAdminAccountRecordsToTgUser}）</b>：
 * 该测试走真实的 {@link ExportMyDataCommandHandler} + 真实仓库。在旧的单条件实现下，
 * handler 会把同 id 的后台账号记录也返回给 TG 用户 → 断言 {@code doesNotContain("admin.config.update")} 失败（红）；
 * 换成双条件后转绿。
 *
 * <p><b>为什么用唯一 id</b>：{@code audit_log} 是<b>只追加</b>且被 {@code @SpringBootTest} 的 IT
 * 真实提交过大量历史行（actor_id 固定值如 42）。若本测试也用固定 id，会读到历史数据而误判。
 * 故主体 id 取 {@code nanoTime}（唯一），保证库里只有本测试写入的行。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditActorTypeIT {

    /** 关键：账号 id 与 TG userId 故意撞同一个值——这正是攻击面。取唯一值以避开历史数据。 */
    private final long sharedId = System.nanoTime();

    @Autowired
    private AuditLogRepository repository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sameIdDifferentSubjectTypeStayIsolatedAndSingleConditionWouldLeak() {
        repository.save(new AuditEntry(ActorType.ADMIN_ACCOUNT, sharedId, "admin.config.update",
                null, null, AuditEntry.Outcome.SUCCESS, "account-side", Instant.now()));
        repository.save(new AuditEntry(ActorType.TG_USER, sharedId, "TeachCommandHandler#handle",
                null, null, AuditEntry.Outcome.SUCCESS, "user-side", Instant.now()));
        em.flush();   // 落库，使 JdbcTemplate 可见

        assertThat(repository.findByActorTypeAndActorIdOrderByIdDesc(ActorType.TG_USER, sharedId))
                .extracting(AuditEntry::getAction)
                .as("TG 用户只能看到自己作为 TG 用户的记录")
                .containsExactly("TeachCommandHandler#handle");

        assertThat(repository.findByActorTypeAndActorIdOrderByIdDesc(ActorType.ADMIN_ACCOUNT, sharedId))
                .extracting(AuditEntry::getAction)
                .as("后台账号只能看到自己作为后台账号的记录")
                .containsExactly("admin.config.update");

        // 反证：退回裸 actor_id 单条件会同时命中两行——缺陷真实存在，故类型条件不可省
        Long leaked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE actor_id = ? AND action IN (?, ?)",
                Long.class, sharedId, "TeachCommandHandler#handle", "admin.config.update");
        assertThat(leaked)
                .as("裸 actor_id 单条件会命中 TG 用户与后台账号两条记录——这正是引入 actor_type 的原因")
                .isEqualTo(2L);
    }

    /**
     * RED 锚点：走真实的导出命令（而非仅仓库），证明 TG 用户看不到同 id 的后台账号记录。
     */
    @Test
    void exportHandlerDoesNotLeakAdminAccountRecordsToTgUser() {
        repository.save(new AuditEntry(ActorType.ADMIN_ACCOUNT, sharedId, "admin.config.update",
                null, null, AuditEntry.Outcome.SUCCESS, "account-side", Instant.now()));
        repository.save(new AuditEntry(ActorType.TG_USER, sharedId, "TeachCommandHandler#handle",
                null, null, AuditEntry.Outcome.SUCCESS, "user-side", Instant.now()));
        em.flush();

        NotificationPreferenceService prefs = mock(NotificationPreferenceService.class);
        when(prefs.quietHoursOf(sharedId)).thenReturn(null);
        ExportMyDataCommandHandler handler = new ExportMyDataCommandHandler(repository, prefs);

        SendMessage msg = (SendMessage) handler.handle(
                new UpdateContext(1, sharedId, -100L, 5, "export_my_data", null));

        assertThat(msg.getText())
                .as("导出必须含请求者自己作为 TG 用户的记录")
                .contains("TeachCommandHandler#handle");
        assertThat(msg.getText())
                .as("TG 用户绝不能在导出里看到同 id 后台账号的记录（旧的单条件实现会漏）")
                .doesNotContain("admin.config.update");
    }
}
