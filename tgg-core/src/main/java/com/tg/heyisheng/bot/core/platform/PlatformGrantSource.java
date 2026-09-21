package com.tg.heyisheng.bot.core.platform;

import com.tg.heyisheng.bot.core.audit.ActorType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台能力账本——四套 guard 的<b>统一授权来源</b>。
 *
 * <p><b>读路径</b>：{@link #hasPermission(ActorType, Long, PlatformPermission)} 按
 * {@code (主体类型, 主体 id)} 判定——类型不可省（后台账号 id 与 TG userId 数值空间重叠）。
 *
 * <p><b>写路径</b>：只有超管（见账号管理端点）才能调用 {@link #grant} / {@link #revoke}；
 * 且 {@link PlatformPermission#GRANT_MANAGE} <b>拒绝授予</b>——它是「分配权限」的权限，
 * 只属超管，授予出去等于开出一条提权路。
 */
@Service
public class PlatformGrantSource {

    private final PlatformGrantRepository grants;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformGrantSource(PlatformGrantRepository grants) {
        this(grants, Clock.systemUTC());
    }

    PlatformGrantSource(PlatformGrantRepository grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    /** 该主体是否拥有该能力。任一入参为空一律 false（未知即拒绝）。 */
    @Transactional(readOnly = true)
    public boolean hasPermission(ActorType subjectType, Long subjectId, PlatformPermission permission) {
        if (subjectType == null || subjectId == null || permission == null) {
            return false;
        }
        return grants.findBySubjectTypeAndSubjectIdAndPermission(subjectType, subjectId, permission)
                .isPresent();
    }

    /** 该主体拥有的全部能力（账号管理界面显示用）。 */
    @Transactional(readOnly = true)
    public Set<PlatformPermission> permissionsOf(ActorType subjectType, Long subjectId) {
        if (subjectType == null || subjectId == null) {
            return Set.of();
        }
        return grants.findBySubjectTypeAndSubjectId(subjectType, subjectId).stream()
                .map(PlatformGrant::getPermission)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 授予一项能力（幂等）。
     *
     * @throws IllegalArgumentException 试图授予 {@link PlatformPermission#GRANT_MANAGE}
     */
    @Transactional
    public void grant(ActorType subjectType, Long subjectId, PlatformPermission permission, Long grantedBy) {
        if (permission == PlatformPermission.GRANT_MANAGE) {
            throw new IllegalArgumentException(
                    "GRANT_MANAGE 只属超管，不可授予其它主体（否则等于开出提权路）");
        }
        if (grants.findBySubjectTypeAndSubjectIdAndPermission(subjectType, subjectId, permission).isEmpty()) {
            grants.save(new PlatformGrant(subjectType, subjectId, permission, grantedBy, clock.instant()));
        }
    }

    /** 收回一项能力（幂等）。 */
    @Transactional
    public void revoke(ActorType subjectType, Long subjectId, PlatformPermission permission) {
        grants.deleteBySubjectTypeAndSubjectIdAndPermission(subjectType, subjectId, permission);
    }
}
