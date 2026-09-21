package com.tg.heyisheng.bot.core.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志「只追加」的真库测试（模块十 §11.2）——直连本机 MySQL 测试库。
 *
 * <p>⚠️ <b>本测试刻意不宣称「数据库层不可删」</b>：触发器在本机建不了
 * （MySQL 开 binlog 时要求 SUPER，报 ERROR 1419），托管库也常禁用。
 * 故这里如实只验证两层能保证的东西：
 * <ol>
 *   <li><b>应用层</b>——仓库接口不暴露任何删除方法（编译期即不可达）；</li>
 *   <li><b>写入与查询</b>——审计真的落库、真的可查。</li>
 * </ol>
 * 数据层硬保证属部署侧，见部署清单。**不写没验证过的断言**是本项目的底线。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditLogAppendOnlyIT {

    /** 每次运行用不同的 actorId，避免与历史数据相互干扰（审计表只追加、不清理）。 */
    private final long actorId = System.currentTimeMillis();

    @Autowired
    private AuditLogRepository repository;

    @Test
    void appendAndQueryWorkOnRealDatabase() {
        AuditEntry saved = repository.save(new AuditEntry(actorId, "SomeCommandHandler#handle",
                -100900999L, AuditEntry.Outcome.SUCCESS, null, Instant.now()));

        assertThat(saved.getId()).as("真实落库后应有自增主键").isNotNull();
        assertThat(repository.findById(saved.getId())).isPresent();
        assertThat(repository.findByActorTypeAndActorIdOrderByIdDesc(ActorType.TG_USER, actorId))
                .extracting(AuditEntry::getAction)
                .containsExactly("SomeCommandHandler#handle");
    }

    @Test
    void repositoryExposesNoDeleteCapability() {
        assertThat(AuditLogRepository.class.getMethods())
                .extracting(Method::getName)
                .as("仓库不得暴露任何删除能力——这是应用层能给的「不可删」保证")
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
    }
}
