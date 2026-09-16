package com.tg.heyisheng.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Telegram 联邦智慧政务群组治理机器人 —— 启动入口。
 *
 * <p>开源项目（AGPL-3.0）；开发者只提供代码，不参与运营。
 */
@SpringBootApplication
public class TggApplication {

    public static void main(String[] args) {
        SpringApplication.run(TggApplication.class, args);
    }
}
