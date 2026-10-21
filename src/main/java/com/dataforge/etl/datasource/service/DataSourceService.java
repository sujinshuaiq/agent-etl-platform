package com.dataforge.etl.datasource.service;

import com.dataforge.etl.common.enums.DataSourceType;
import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.datasource.entity.DataSource;
import com.dataforge.etl.datasource.repository.DataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源业务服务:CRUD、连接测试(H2 类型真实探测,其余模拟)、表清单查询。
 */
@Service
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    private final DataSourceRepository dataSourceRepository;
    private final JdbcTemplate jdbcTemplate;

    public DataSourceService(DataSourceRepository dataSourceRepository, JdbcTemplate jdbcTemplate) {
        this.dataSourceRepository = dataSourceRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DataSource> listAll() {
        return dataSourceRepository.findAll();
    }

    public DataSource getById(Long id) {
        return dataSourceRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("数据源不存在: id=" + id));
    }

    @Transactional
    public DataSource create(DataSource ds) {
        if (ds.getCode() == null || ds.getCode().isBlank()) {
            throw BizException.badRequest("数据源编码不能为空");
        }
        if (dataSourceRepository.existsByCode(ds.getCode())) {
            throw BizException.badRequest("数据源编码已存在: " + ds.getCode());
        }
        ds.setId(null);
        log.info("新增数据源: {} ({})", ds.getName(), ds.getCode());
        return dataSourceRepository.save(ds);
    }

    @Transactional
    public DataSource update(Long id, DataSource input) {
        DataSource ds = getById(id);
        if (input.getCode() != null && !input.getCode().equals(ds.getCode())
                && dataSourceRepository.existsByCode(input.getCode())) {
            throw BizException.badRequest("数据源编码已存在: " + input.getCode());
        }
        ds.setName(input.getName());
        ds.setCode(input.getCode());
        ds.setType(input.getType());
        ds.setConnectionConfig(input.getConnectionConfig());
        ds.setDescription(input.getDescription());
        if (input.getStatus() != null) {
            ds.setStatus(input.getStatus());
        }
        log.info("更新数据源: {} ({})", ds.getName(), ds.getCode());
        return dataSourceRepository.save(ds);
    }

    @Transactional
    public void delete(Long id) {
        DataSource ds = getById(id);
        dataSourceRepository.delete(ds);
        log.info("删除数据源: {} ({})", ds.getName(), ds.getCode());
    }

    /**
     * 测试连接:H2 类型执行真实 SELECT 1 探测并计时;
     * 其余类型模拟 5~200ms 的网络延迟后判定成功(演示用途)。
     * 测试结果回写 lastTestAt / lastTestSuccess。
     */
    @Transactional
    public Map<String, Object> testConnection(Long id) {
        DataSource ds = getById(id);
        long begin = System.currentTimeMillis();
        boolean success;
        String message;
        if (ds.getType() == DataSourceType.H2) {
            try {
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                success = true;
                message = "H2 内嵌库连接正常";
            } catch (Exception e) {
                success = false;
                message = "H2 连接失败: " + e.getMessage();
            }
        } else {
            // 模拟外部数据源网络延迟(由 id 决定,保证结果稳定可演示)
            long simulated = 5 + (id * 37) % 195;
            try {
                Thread.sleep(Math.min(simulated, 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            success = true;
            message = ds.getType() + " 数据源连接正常(模拟)";
        }
        long latency = System.currentTimeMillis() - begin;
        ds.setLastTestAt(LocalDateTime.now());
        ds.setLastTestSuccess(success);
        dataSourceRepository.save(ds);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("latencyMs", latency);
        result.put("message", message);
        return result;
    }

    /**
     * 表清单:H2 类型返回当前库中真实业务表(含行数与估算大小);
     * 其余类型返回模拟表清单。
     */
    public List<Map<String, Object>> listTables(Long id) {
        DataSource ds = getById(id);
        List<Map<String, Object>> tables = new ArrayList<>();
        if (ds.getType() == DataSourceType.H2) {
            // 查询 H2 元数据:排除平台自身的业务表,仅展示 ods_/dwd_ 开头的数据表
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE TABLE_SCHEMA = 'PUBLIC' AND (TABLE_NAME LIKE 'ods_%' OR TABLE_NAME LIKE 'dwd_%') "
                            + "ORDER BY TABLE_NAME", String.class);
            for (String name : names) {
                long rows = safeCount(name);
                tables.add(tableInfo(name, rows));
            }
        } else {
            // 模拟表清单:不同类型给出贴近业务的表名
            String[][] mock = switch (ds.getType()) {
                case MYSQL -> new String[][]{{"users", "10240"}, {"orders", "51230"}, {"products", "860"}};
                case CSV -> new String[][]{{"access_log_2024.csv", "120000"}, {"access_log_2025.csv", "86000"}};
                case REST_API -> new String[][]{{"/api/events", "—"}, {"/api/metrics", "—"}};
                case KAFKA -> new String[][]{{"topic.order.created", "—"}, {"topic.user.login", "—"}};
                case HDFS_SIM -> new String[][]{{"/warehouse/dwd/dwd_users", "9800"}, {"/warehouse/ods/ods_events", "99500"}};
                default -> new String[][]{{"default_table", "0"}};
            };
            for (String[] m : mock) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", m[0]);
                info.put("rows", m[1]);
                info.put("sizeDesc", "模拟");
                tables.add(info);
            }
        }
        return tables;
    }

    /** 安全计数:表不存在时返回 0 */
    private long safeCount(String table) {
        try {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            return count == null ? 0 : count;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<String, Object> tableInfo(String name, long rows) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        info.put("rows", rows);
        // 粗略按每行 200 字节估算表大小
        long bytes = rows * 200;
        info.put("sizeDesc", bytes >= 1024 * 1024
                ? String.format("%.1f MB", bytes / 1024.0 / 1024.0)
                : String.format("%.1f KB", bytes / 1024.0));
        return info;
    }
}
