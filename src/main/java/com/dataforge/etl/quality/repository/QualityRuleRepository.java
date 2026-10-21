package com.dataforge.etl.quality.repository;

import com.dataforge.etl.quality.entity.QualityRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 质量规则数据访问层 */
@Repository
public interface QualityRuleRepository extends JpaRepository<QualityRule, Long> {

    List<QualityRule> findByEnabledTrue();
}
