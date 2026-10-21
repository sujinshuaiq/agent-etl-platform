package com.dataforge.etl.datasource.repository;

import com.dataforge.etl.datasource.entity.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 数据源数据访问层 */
@Repository
public interface DataSourceRepository extends JpaRepository<DataSource, Long> {

    boolean existsByCode(String code);
}
