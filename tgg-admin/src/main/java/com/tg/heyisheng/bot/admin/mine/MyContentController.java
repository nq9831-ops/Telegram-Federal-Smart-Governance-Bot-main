package com.tg.heyisheng.bot.admin.mine;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.listing.ListingGroup;
import com.tg.heyisheng.bot.listing.ListingGroupRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 「我的内容」端点（模块十一 · 数据范围）——<b>数据范围 OWN 的实现示范</b>。
 *
 * <p>用户需求：*「tg 用户登陆这个 web 面板只能查看更改自己权限写的内容」*。
 * 本端点即该范围的入口：只返回<b>当前主体自己提交</b>的行。
 *
 * <p><b>过滤进查询条件</b>（{@link ListingGroupRepository#findBySubmitterUserIdOrderByIdDesc}），
 * <b>绝不</b>「查全部再在控制器里过滤」——后者一旦漏过滤就会把别人的行发出去，
 * 而本项目的纪律是「数据范围过滤必须在查询条件里」（见 {@code AuthorColumn}）。
 *
 * <p><b>为什么后台账号返回空</b>：收录的提交者恒为 TG 用户；后台账号（超管/操作员）没有
 * 「自己提交的收录」。超管要看全部收录应走平台管理端点（不在本类范围）。
 *
 * <p>其余作者表（{@code merchants} / {@code moderation_rules} / {@code banned_words} /
 * {@code group_topic_tags} / {@code federation_appeals}）循同一模式：各自的模块暴露
 * 「按作者查」的仓库方法，后台端点只做「当前主体 → 作者 id」的映射。本类先落地 listings 一条，
 * 作为该模式的样板。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/my", produces = MediaType.APPLICATION_JSON_VALUE)
public class MyContentController {

    private final ListingGroupRepository listings;

    public MyContentController(ListingGroupRepository listings) {
        this.listings = listings;
    }

    /** 「我提交的收录」——只含 {@code submitter_user_id == 当前主体} 的行。 */
    @GetMapping("/listings")
    public ResponseEntity<?> myListings(HttpServletRequest request) {
        ActorType subjectType = (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
        Long subjectId = (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
        if (subjectId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "无会话主体"));
        }
        if (subjectType != ActorType.TG_USER) {
            // 后台账号没有「自己提交的收录」
            return ResponseEntity.ok(List.of());
        }
        List<Map<String, Object>> views = listings
                .findBySubmitterUserIdOrderByIdDesc(subjectId).stream()
                .map(MyContentController::toView)
                .toList();
        return ResponseEntity.ok(views);
    }

    private static Map<String, Object> toView(ListingGroup group) {
        return Map.of(
                "id", group.getId(),
                "chatId", group.getChatId(),
                "title", group.getTitle() == null ? "" : group.getTitle(),
                "status", group.getStatus(),
                "createdAt", String.valueOf(group.getCreatedAt()));
    }
}
