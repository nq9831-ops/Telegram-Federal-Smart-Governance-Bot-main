package com.tg.heyisheng.bot.federation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 联邦申诉仓库。 */
public interface FederationAppealRepository extends JpaRepository<FederationAppeal, Long> {

    /** 待审申诉，按提交时间升序（先来先审）。 */
    List<FederationAppeal> findByStatusOrderByCreatedAtAsc(String status);
}
