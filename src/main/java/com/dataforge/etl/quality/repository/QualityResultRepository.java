package com.dataforge.etl.quality.repository;

import com.dataforge.etl.quality.entity.QualityResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/** 质量检查结果数据访问层 */
@Repository
public interface QualityResultRepository extends JpaRepository<QualityResult, Long>, JpaSpecificationExecutor<QualityResult> {

    long countByPassed(Boolean passed);
}
