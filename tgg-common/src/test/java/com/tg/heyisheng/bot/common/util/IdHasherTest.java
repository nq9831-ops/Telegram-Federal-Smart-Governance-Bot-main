package com.tg.heyisheng.bot.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标识哈希化测试。
 *
 * <p>这是隐私合规的基础件：V5.0 要求「审核结果中的用户标识必须哈希化」，
 * 日志与审计里不得出现明文 userId。
 */
class IdHasherTest {

    private final IdHasher hasher = new IdHasher("test-salt");

    @Test
    void hashesToShortHexDigest() {
        String hashed = hasher.hash(42L);

        assertThat(hashed).hasSize(12);
        assertThat(hashed).matches("[0-9a-f]{12}");
        assertThat(hashed).as("不得包含原值").doesNotContain("42");
    }

    @Test
    void isDeterministic() {
        assertThat(hasher.hash(42L)).isEqualTo(hasher.hash(42L));
    }

    @Test
    void differentIdsProduceDifferentHashes() {
        assertThat(hasher.hash(42L)).isNotEqualTo(hasher.hash(43L));
    }

    /**
     * 盐必须影响结果——否则攻击者可预计算字典反推（Telegram userId 是有限数字空间）。
     */
    @Test
    void saltChangesTheHash() {
        IdHasher other = new IdHasher("another-salt");

        assertThat(hasher.hash(42L))
                .as("不同盐必须产出不同哈希，否则盐形同虚设")
                .isNotEqualTo(other.hash(42L));
    }

    @Test
    void nullIdIsSafe() {
        assertThat(hasher.hash(null)).isNull();
    }

    @Test
    void blankSaltFallsBackToDevSaltAndIsFlagged() {
        IdHasher noSalt = new IdHasher("");

        assertThat(noSalt.usingDevFallbackSalt())
                .as("未配置盐时必须可被检测到，以便生产环境告警")
                .isTrue();
        assertThat(hasher.usingDevFallbackSalt()).isFalse();
    }
}
