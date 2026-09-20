package com.tg.heyisheng.bot.core.config.dynamic;

/**
 * 配置写入被拒——带**可判别的种类**，供端点映射到不同 HTTP 状态码。
 *
 * <p>为什么需要种类而不是一个笼统的「非法」：三种失败对调用方的含义完全不同——
 * 键不存在（404，可能拼错）、键不可写（409，语义冲突：这键就是不给写）、值非法（400，改改就能过）。
 * 把它们都当 400 会让前端无法给出正确提示。
 *
 * <p>继承 {@link IllegalArgumentException} 是刻意的：既有调用方（与 Wave 1 单测）把它当非法参数捕获，
 * 加了种类也不破坏它们。
 */
public class ConfigWriteException extends IllegalArgumentException {

    /** 拒绝原因。 */
    public enum Kind {
        /** 键不在 {@link ConfigCatalog} 中。 */
        UNKNOWN_KEY,
        /** 键存在但分类为密钥/引导态，不可经 Web 改写。 */
        NOT_WRITABLE,
        /** 值未通过类型/范围/前置条件校验。 */
        INVALID_VALUE
    }

    private final Kind kind;

    public ConfigWriteException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
