package com.tg.heyisheng.bot.admin.system;

/**
 * 重启动作接缝。
 *
 * <p><b>为什么抽成接口而不是在控制器里直接 {@code System.exit}</b>：真实退出会杀掉测试 JVM。
 * 抽成可注入的接缝后，测试用替身即可验证「权限与开关都放行时才触发重启」，
 * 而**永不真的退出**——这是能在 CI 里跑的前提。
 *
 * <p>默认实现见 {@code AdminConfiguration#restartAction}：优雅退出，由外部监管进程拉起。
 */
@FunctionalInterface
public interface RestartAction {

    /** 触发一次重启（默认实现为「退出进程，等监管进程拉起」）。 */
    void restart();
}
