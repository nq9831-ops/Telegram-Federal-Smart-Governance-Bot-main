package com.tg.heyisheng.bot.core.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 案件申诉的仓库。
 *
 * <p><b>幂等的落点</b>：{@link #findByReviewIdAndUserId} 与表上的
 * {@code uk_case_appeal_review_user} 唯一键配对使用——先查后写挡掉常规重复提交，
 * 唯一键兜住并发竞态（两个请求同时通过检查）。
 */
public interface CaseAppealRepository extends JpaRepository<CaseAppeal, Long> {

    /** 某人对某案件的既有申诉（幂等判据）。 */
    Optional<CaseAppeal> findByReviewIdAndUserId(Long reviewId, Long userId);

    /** 按状态列出（后台申诉队列用；id 升序 = 提交顺序）。 */
    List<CaseAppeal> findByStatusOrderByIdAsc(String status);

    /** 某案件的全部申诉（案件详情页展示用）。 */
    List<CaseAppeal> findByReviewIdOrderByIdAsc(Long reviewId);
}
