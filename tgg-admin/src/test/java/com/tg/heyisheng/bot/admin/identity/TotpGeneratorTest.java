package com.tg.heyisheng.bot.admin.identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOTP（RFC 6238）。
 *
 * <p>守：当前步的码能过、窗口外的码不能过、Base32 编解码往返一致。
 */
class TotpGeneratorTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);

    @Test
    void currentStepCodePasses() {
        String secret = TotpGenerator.newSecret();
        long counter = NOW.getEpochSecond() / 30;

        String code = TotpGenerator.generateAt(secret, counter);

        assertThat(code).hasSize(6);
        assertThat(TotpGenerator.verify(secret, code, NOW)).isTrue();
    }

    @Test
    void previousAndNextStepCodesPassWithinWindow() {
        String secret = TotpGenerator.newSecret();
        long counter = NOW.getEpochSecond() / 30;

        assertThat(TotpGenerator.verify(secret, TotpGenerator.generateAt(secret, counter - 1), NOW)).isTrue();
        assertThat(TotpGenerator.verify(secret, TotpGenerator.generateAt(secret, counter + 1), NOW)).isTrue();
    }

    @Test
    void codesOutsideWindowFail() {
        String secret = TotpGenerator.newSecret();
        long counter = NOW.getEpochSecond() / 30;

        assertThat(TotpGenerator.verify(secret, TotpGenerator.generateAt(secret, counter + 5), NOW)).isFalse();
        assertThat(TotpGenerator.verify(secret, TotpGenerator.generateAt(secret, counter - 5), NOW)).isFalse();
    }

    @Test
    void malformedCodeFails() {
        String secret = TotpGenerator.newSecret();

        assertThat(TotpGenerator.verify(secret, "abc", NOW)).isFalse();
        assertThat(TotpGenerator.verify(secret, null, NOW)).isFalse();
        assertThat(TotpGenerator.verify(null, "123456", NOW)).isFalse();
    }

    @Test
    void base32RoundTrips() {
        byte[] original = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, 0x10, 0x20};

        byte[] decoded = TotpGenerator.base32Decode(TotpGenerator.base32Encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void otpauthUrlContainsSecretAndIssuer() {
        String url = TotpGenerator.otpauthUrl("TGG", "root", "GEZDGNBVGY3TQOJQ");

        assertThat(url).startsWith("otpauth://totp/").contains("secret=GEZDGNBVGY3TQOJQ").contains("issuer=TGG");
    }
}
