package com.tg.heyisheng.bot.core.moderation;

/**
 * 案件号在链路中的载体——{@code moderation_review_queue.id}，即「复核 #N」里的 N。
 *
 * <p><b>为什么要一个专用类型</b>：案件号入队后才产生（在 {@code UpdateDispatcher.moderateInto} 里），
 * 但要到<b>处置阶段</b>才被消费（{@code ModerationEnforcer} 的群内告知要给出编号，
 * 当事人据此申诉）。两处之间靠 {@code UpdateContext.attach} 传递，而该属性袋
 * <b>以运行时类型为键</b>——直接用 {@code Long} 会与其它 Long 属性撞键，
 * 故包一层具名类型，让「这是案件号」在类型上就无歧义。
 *
 * <p>未入队（空实现 / 入队失败 / clean）时此对象不存在——消费方据此降级为不带编号的告知，
 * 绝不渲染 {@code #null}。
 *
 * @param caseId 案件号（队列项主键）
 */
public record ModerationCaseRef(long caseId) {
}
