package com.tg.heyisheng.bot.admin.config;

import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.config.dynamic.ConfigAdminGuard;
import com.tg.heyisheng.bot.core.config.dynamic.ConfigWriteException;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 配置写/清的<b>失败</b>动作必须可审计（GUARD-5）。
 *
 * <p>守的重点：{@link ConfigController#clear} 的 {@code ConfigWriteException} 分支此前直接回
 * 404 <b>不写审计</b>，而 {@link ConfigController#update} 的同一分支写 {@code FAILURE} 审计——
 * 于是「谁、何时试图清除哪个键但失败了」在审计里是一段空白，而审计的失败面恰恰是
 * 越权尝试与误操作最该被看见的地方。本类把「两个分支对称」固化为回归。
 *
 * <p><b>失败模式用真实异常</b>（{@code ConfigWriteException}），不靠 mock 返回假值——
 * 断言的是控制器 catch 分支的实际行为。
 */
class ConfigControllerClearAuditTest {

    private static final String KEY = "tgg.nope";
    private static final long ACTOR_ID = 42L;

    private final RuntimeConfigService config = mock(RuntimeConfigService.class);
    private final ConfigAdminGuard guard = mock(ConfigAdminGuard.class);
    private final AuditService audit = mock(AuditService.class);
    private final ConfigController controller = new ConfigController(config, guard, audit);

    /** 超管请求：天然全权，不必走白名单，便于把焦点放在失败的审计行为上。 */
    private static HttpServletRequest superAdminRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE)).thenReturn(AdminRole.SUPER_ADMIN);
        when(request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE)).thenReturn(ActorType.ADMIN_ACCOUNT);
        when(request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE)).thenReturn(ACTOR_ID);
        return request;
    }

    @Test
    void failedClearIsNotFoundAndWritesFailureAudit() {
        when(config.resolve(KEY)).thenReturn(Optional.empty());
        doThrow(new ConfigWriteException(ConfigWriteException.Kind.UNKNOWN_KEY, "未知配置键：" + KEY))
                .when(config).clear(eq(KEY), any());

        ResponseEntity<Map<String, String>> response = controller.clear(KEY, superAdminRequest());

        assertThat(response.getStatusCode()).as("未知键的清除仍应回 404").isEqualTo(HttpStatus.NOT_FOUND);
        verify(audit).record(eq(ActorType.ADMIN_ACCOUNT), eq(ACTOR_ID), eq(ConfigController.AUDIT_ACTION),
                isNull(), eq(AuditEntry.Outcome.FAILURE), anyString());
    }

    /** 对称回归：update 的失败分支本来就写审计，固化它以免修复 clear 时被反向改坏。 */
    @Test
    void failedUpdateWritesFailureAudit() {
        when(config.resolve(KEY)).thenReturn(Optional.empty());
        doThrow(new ConfigWriteException(ConfigWriteException.Kind.UNKNOWN_KEY, "未知配置键：" + KEY))
                .when(config).set(eq(KEY), any(), any());

        ResponseEntity<Map<String, String>> response =
                controller.update(KEY, new ConfigController.UpdateRequest("1"), superAdminRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(audit).record(eq(ActorType.ADMIN_ACCOUNT), eq(ACTOR_ID), eq(ConfigController.AUDIT_ACTION),
                isNull(), eq(AuditEntry.Outcome.FAILURE), anyString());
    }

    /** 无写权限的 403 早于写入，不产生写审计——403 由会话层记，这里不应多写。 */
    @Test
    void forbiddenClearWritesNoAudit() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE)).thenReturn(AdminRole.OPERATOR);
        when(request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE)).thenReturn(ActorType.ADMIN_ACCOUNT);
        when(request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE)).thenReturn(ACTOR_ID);
        when(guard.isConfigAdmin(ActorType.ADMIN_ACCOUNT, ACTOR_ID)).thenReturn(false);

        ResponseEntity<Map<String, String>> response = controller.clear(KEY, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(audit);
    }
}
