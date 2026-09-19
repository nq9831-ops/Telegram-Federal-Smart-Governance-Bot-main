package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未映射路径必须得到 <b>404</b>，而不是被全局异常处理器吞成 <b>200</b>。
 *
 * <p><b>为什么值得一条 IT</b>：{@code WebhookExceptionHandler} 用
 * {@code @ExceptionHandler(Exception.class)} 把所有异常统一吞成 200——这是为了
 * **避免 Telegram 对失败投递重试风暴**的既有设计（见 {@code LESSONS.md} 坑 2）。
 * 但 {@code NoResourceFoundException}（DispatcherServlet 找不到任何映射/静态资源时抛）**与 Telegram 无关**：
 * 它只出现在**未知路径**上。把这类请求也答成 200 有两个真实代价（均在公网部署后实测）：
 * <ol>
 *   <li><b>日志噪声淹没真实故障</b>：互联网扫描器（{@code /index.php/vod/...} 之类）每个请求都写一条
 *       ERROR 堆栈——生产开放 1 小时内即积累 299 条，排查时无法分辨噪音与真故障；</li>
 *   <li><b>对端误判成功</b>：联邦未启用时 {@code POST /federation/penalty} 返回 200，
 *       对端会把「路径根本不存在」读成「送达成功」。</li>
 * </ol>
 *
 * <p>修法是让该 handler 对 {@code NoResourceFoundException} 放行（保持 404），**不动**「业务异常吞成 200」
 * 那条既有策略——Telegram 的 update 走的是 {@code /webhook}（单段，由 TelegramBots 的
 * {@code /{botPath}} 映射处理），不会落到这里。
 *
 * <p><b>对照组</b>：{@link WebhookDispatchIT} 守的是 webhook 正常路径仍返回 200——本 IT 不重复它，
 * 只主张「未知路径」这一侧。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnknownPathIT {

    @Autowired
    private MockMvc mvc;

    /** 多段路径（扫描器常见形态）：不匹配 TelegramBots 的单段 {@code /{botPath}} 映射，落到静态资源处理器。 */
    @Test
    void unmappedGetPathReturns404() throws Exception {
        mvc.perform(get("/index.php/vod/xxx"))
                .andExpect(status().isNotFound());
    }

    /** 任意 HTTP 方法都应如此——未知路径的判定与动词无关。 */
    @Test
    void unmappedPostPathReturns404() throws Exception {
        mvc.perform(post("/no-such-module/no-such-endpoint"))
                .andExpect(status().isNotFound());
    }
}
