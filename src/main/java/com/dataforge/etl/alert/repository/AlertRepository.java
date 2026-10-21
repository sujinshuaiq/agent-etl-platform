package com.dataforge.etl.alert.repository;

import com.dataforge.etl.alert.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 告警数据访问层 */
@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {

    Page<Alert> findByReadOrderByIdDesc(boolean read, Pageable pageable);

    long countByReadFalse();

    List<Alert> findByReadFalse();

    /** 全部标记已读(批量更新) */
    @Modifying
    @Query("UPDATE Alert a SET a.read = true WHERE a.read = false")
    int markAllRead();
}
