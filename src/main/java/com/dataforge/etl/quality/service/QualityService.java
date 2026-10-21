package com.dataforge.etl.quality.service;

import com.dataforge.etl.common.enums.CheckType;
import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.quality.entity.QualityResult;
import com.dataforge.etl.quality.entity.QualityRule;
import com.dataforge.etl.quality.repository.QualityResultRepository;
import com.dataforge.etl.quality.repository.QualityRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 数据质量服务:规则 CRUD + 针对 H2 业务表的真实检查执行。
 * 所有 SQL 的表名/字段名都做白名单字符校验,防止注入。
 */
@Service
public class QualityService {

    private static final Logger log = LoggerFactory.getLogger(QualityService.class);

    /** 表名/字段名白名单:仅允许字母、数字、下划线 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");

    private final QualityRuleRepository ruleRepository;
    private final QualityResultRepository resultRepository;
    private final JdbcTemplate jdbcTemplate;

    public QualityService(QualityRuleRepository ruleRepository,
                          QualityResultRepository resultRepository,
                          JdbcTemplate jdbcTemplate) {
        this.ruleRepository = ruleRepository;
        this.resultRepository = resultRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<QualityRule> listRules() {
        return ruleRepository.findAll();
    }

    public QualityRule getRule(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("质量规则不存在: id=" + id));
    }

    @Transactional
    public QualityRule createRule(QualityRule rule) {
        rule.setId(null);
        log.info("新增质量规则: {} -> {}.{}", rule.getName(), rule.getTargetTable(), rule.getField());
        return ruleRepository.save(rule);
    }

    @Transactional
    public QualityRule updateRule(Long id, QualityRule input) {
        QualityRule rule = getRule(id);
        rule.setName(input.getName());
        rule.setTargetTable(input.getTargetTable());
        rule.setCheckType(input.getCheckType());
        rule.setField(input.getField());
        rule.setExpression(input.getExpression());
        rule.setDescription(input.getDescription());
        if (input.getSeverity() != null) {
            rule.setSeverity(input.getSeverity());
        }
        if (input.getEnabled() != null) {
            rule.setEnabled(input.getEnabled());
        }
        return ruleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(Long id) {
        ruleRepository.delete(getRule(id));
    }

    /** 检查结果分页查询(可按 ruleId 过滤,按检查时间倒序) */
    public Page<QualityResult> pageResults(Long ruleId, int page, int size) {
        Specification<QualityResult> spec = (root, q, cb) ->
                ruleId == null ? cb.conjunction() : cb.equal(root.get("ruleId"), ruleId);
        return resultRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "checkedAt", "id")));
    }

    /**
     * 执行一条规则的检查,落库并返回结果。
     *
     * @param rule     规则
     * @param runId    关联的管道运行(可为空)
     * @param nodeName 执行节点名称(可为空)
     */
    @Transactional
    public QualityResult executeCheck(QualityRule rule, Long runId, String nodeName) {
        String table = requireSafeIdentifier(rule.getTargetTable(), "目标表");
        QualityResult result = new QualityResult();
        result.setRuleId(rule.getId());
        result.setRuleName(rule.getName());
        result.setRunId(runId);
        result.setNodeName(nodeName);
        result.setCheckType(rule.getCheckType());
        result.setCheckedAt(LocalDateTime.now());
        try {
            switch (rule.getCheckType()) {
                case NULL_CHECK -> {
                    String field = requireSafeIdentifier(rule.getField(), "字段");
                    long violations = count("SELECT COUNT(*) FROM " + table + " WHERE " + field + " IS NULL");
                    result.setExpected(field + " NULL 数 = 0");
                    result.setActual("NULL 数 = " + violations);
                    result.setPassed(violations == 0);
                    result.setDetail("扫描 " + table + "." + field + " 空值");
                }
                case UNIQUE_CHECK -> {
                    String field = requireSafeIdentifier(rule.getField(), "字段");
                    long total = count("SELECT COUNT(*) FROM " + table);
                    long distinct = count("SELECT COUNT(DISTINCT " + field + ") FROM " + table);
                    long duplicates = total - distinct;
                    result.setExpected(field + " 重复数 = 0");
                    result.setActual("重复数 = " + duplicates);
                    result.setPassed(duplicates == 0);
                    result.setDetail("总行数 " + total + ",去重后 " + distinct);
                }
                case RANGE_CHECK -> {
                    String field = requireSafeIdentifier(rule.getField(), "字段");
                    double[] range = parseRange(rule.getExpression());
                    long violations = count("SELECT COUNT(*) FROM " + table + " WHERE " + field
                            + " < " + range[0] + " OR " + field + " > " + range[1]);
                    result.setExpected(field + " 在 [" + trimNum(range[0]) + ", " + trimNum(range[1]) + "] 内");
                    result.setActual("越界数 = " + violations);
                    result.setPassed(violations == 0);
                    result.setDetail("扫描 " + table + "." + field + " 取值范围");
                }
                case REGEX_CHECK -> {
                    String field = requireSafeIdentifier(rule.getField(), "字段");
                    String regex = rule.getExpression() == null ? "" : rule.getExpression();
                    // H2 2.x 支持 REGEXP_LIKE 函数
                    long violations = count("SELECT COUNT(*) FROM " + table
                            + " WHERE " + field + " IS NOT NULL AND NOT REGEXP_LIKE(" + field + ", ?)", regex);
                    result.setExpected(field + " 匹配 /" + regex + "/");
                    result.setActual("不匹配数 = " + violations);
                    result.setPassed(violations == 0);
                    result.setDetail("正则校验 " + table + "." + field);
                }
                case ENUM_CHECK -> {
                    String field = requireSafeIdentifier(rule.getField(), "字段");
                    List<String> values = parseEnumValues(rule.getExpression());
                    String placeholders = String.join(",", values.stream().map(v -> "?").toList());
                    long violations = count("SELECT COUNT(*) FROM " + table + " WHERE " + field
                            + " NOT IN (" + placeholders + ")", values.toArray());
                    result.setExpected(field + " ∈ {" + String.join(", ", values) + "}");
                    result.setActual("非法值数 = " + violations);
                    result.setPassed(violations == 0);
                    result.setDetail("枚举校验 " + table + "." + field);
                }
                case ROW_COUNT -> {
                    long rows = count("SELECT COUNT(*) FROM " + table);
                    boolean passed = evalRowCount(rule.getExpression(), rows);
                    result.setExpected("行数 " + rule.getExpression());
                    result.setActual("行数 = " + rows);
                    result.setPassed(passed);
                    result.setDetail("统计表 " + table + " 行数");
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // SQL 执行失败本身记为一次不通过的检查结果
            log.warn("质量检查执行异常: rule={}, error={}", rule.getName(), e.getMessage());
            result.setExpected(rule.getExpression());
            result.setActual("执行异常");
            result.setPassed(false);
            result.setDetail("检查 SQL 执行失败: " + e.getMessage());
        }
        return resultRepository.save(result);
    }

    // ---------- 内部工具 ----------

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private String requireSafeIdentifier(String identifier, String what) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw BizException.badRequest(what + "名称非法(仅允许字母/数字/下划线): " + identifier);
        }
        return identifier;
    }

    /** 解析 "min,max" 形式的范围表达式 */
    private double[] parseRange(String expression) {
        try {
            String[] parts = expression.split(",");
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (Exception e) {
            throw BizException.badRequest("范围表达式格式应为 \"min,max\": " + expression);
        }
    }

    private List<String> parseEnumValues(String expression) {
        if (expression == null || expression.isBlank()) {
            throw BizException.badRequest("枚举表达式不能为空,格式: \"A,B,C\"");
        }
        return List.of(expression.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 解析 ">=1000" / ">5" / "<=100" / "=10" 形式的行数表达式并比较 */
    private boolean evalRowCount(String expression, long actual) {
        if (expression == null) {
            throw BizException.badRequest("行数表达式不能为空,如 \">=1000\"");
        }
        String expr = expression.trim();
        String op;
        double threshold;
        if (expr.startsWith(">=")) {
            op = ">=";
            threshold = Double.parseDouble(expr.substring(2).trim());
        } else if (expr.startsWith("<=")) {
            op = "<=";
            threshold = Double.parseDouble(expr.substring(2).trim());
        } else if (expr.startsWith(">")) {
            op = ">";
            threshold = Double.parseDouble(expr.substring(1).trim());
        } else if (expr.startsWith("<")) {
            op = "<";
            threshold = Double.parseDouble(expr.substring(1).trim());
        } else if (expr.startsWith("=")) {
            op = "=";
            threshold = Double.parseDouble(expr.substring(1).trim());
        } else {
            throw BizException.badRequest("行数表达式格式非法: " + expression);
        }
        return switch (op) {
            case ">=" -> actual >= threshold;
            case "<=" -> actual <= threshold;
            case ">" -> actual > threshold;
            case "<" -> actual < threshold;
            default -> actual == threshold;
        };
    }

    /** 数字展示优化:整数不带小数点 */
    private String trimNum(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
