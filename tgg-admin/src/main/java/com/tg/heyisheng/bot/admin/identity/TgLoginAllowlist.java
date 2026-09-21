package com.tg.heyisheng.bot.admin.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Telegram 登录白名单（模块十一 · TG 用户登录的授权闸门）。
 *
 * <p><b>为什么需要它</b>：{@link TelegramLoginVerifier} 只证明「这条回调确实来自 Telegram」，
 * <b>不证明</b>「这个人有权进后台」——任何 Telegram 用户都能对该 bot 完成 Login Widget。
 * 故签发会话前必须再过一道「此人是否被授权」的判定，否则后台对全网 Telegram 用户开放。
 *
 * <p><b>fail-closed</b>：名单为空 = <b>无人</b>可经 TG 登录（而非「人人可登」）。
 * 这是刻意的——「没配授权名单」与「授权所有人」是两回事，后者会让后台登录入口无门槛。
 *
 * <p>名单形态与 {@code TGG_MODERATION_REVIEWERS} / {@code TGG_FEDERATION_ADMINS} 一致：
 * 逗号分隔的 userId；非数字项忽略并告警，不因一个笔误让整条配置失效。
 */
public class TgLoginAllowlist {

    private static final Logger log = LoggerFactory.getLogger(TgLoginAllowlist.class);

    private final Set<Long> ids;

    public TgLoginAllowlist(String csv) {
        this.ids = parse(csv);
    }

    /** 该 Telegram userId 是否被授权经 TG 登录后台。 */
    public boolean allows(long userId) {
        return ids.contains(userId);
    }

    /** 名单是否为空（装配期告警判据：为空则无人可 TG 登录）。 */
    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /** 已登记的授权 userId 数量。 */
    public int size() {
        return ids.size();
    }

    /** 解析逗号分隔的 userId 名单；非数字项忽略并告警。 */
    static Set<Long> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<Long> parsed = new HashSet<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Long.parseLong(token));
            } catch (NumberFormatException ex) {
                log.warn("TG 登录白名单含非数字项，已忽略：{}", token);
            }
        }
        return Set.copyOf(parsed);
    }
}
