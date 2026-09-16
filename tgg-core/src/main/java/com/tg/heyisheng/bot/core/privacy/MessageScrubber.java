package com.tg.heyisheng.bot.core.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * 消息正文清除器——V5.0「不存聊天记录，实时审核即时丢弃」的落地环节。
 *
 * <p><b>与切片 1 的分工</b>：切片 1 让 {@code UpdateContext} 不持有正文（结构性预防，
 * 使正文无法沿业务链路扩散）；本类处理的是**库对象里已经存在的正文**——
 * TelegramBots 反序列化出的 {@code Message} 天然带着 text/caption，
 * 它会随对象被日志、异常栈、审计记录带出去。
 *
 * <p><b>清除范围只限正文型字段</b>，路由元数据（messageId / chat / from / date）
 * 必须保留——否则审计追溯与按群配置都会失效。
 */
public class MessageScrubber {

    private static final Logger log = LoggerFactory.getLogger(MessageScrubber.class);

    /**
     * 清除一条消息中的正文型内容。幂等，且对 null 安全。
     *
     * @param message 待清除的消息，可为 null
     */
    public void scrub(Message message) {
        if (message == null) {
            return;
        }
        message.setText(null);
        message.setCaption(null);
        message.setEntities(null);
        message.setCaptionEntities(null);
        // 引用块本身携带被引用的原文片段
        message.setQuote(null);
        // 回复链里嵌着另一条消息，同样可能含正文
        message.setReplyToMessage(null);
        // 投票：question 与各选项文本都是用户内容
        message.setPoll(null);
        // 投票选项的增删事件（含 optionText）
        message.setPollOptionAdded(null);
        message.setPollOptionDeleted(null);
        // 地点：标题与地址是用户文本
        message.setVenue(null);
        // 转发故事与「回复某故事」——可能嵌套媒体说明
        message.setStory(null);
        message.setReplyToStory(null);
        log.debug("已清除消息正文（messageId={}）", message.getMessageId());
    }
}
