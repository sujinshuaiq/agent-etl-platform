package com.dataforge.etl.schedule.service;

import com.dataforge.etl.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeSet;

/**
 * 简化版 cron 解析器:六位表达式「秒 分 时 日 月 周」。
 *
 * <p>支持语法:
 * <ul>
 *   <li>{@code *} 任意值;{@code ?} 视同 {@code *}(用于 日/周 位)</li>
 *   <li>{@code *}{@code /n} 步进,如 "0/5" 或 "*\/5"</li>
 *   <li>单个数字,如 "30"</li>
 *   <li>逗号列表,如 "0,15,30,45"</li>
 *   <li>区间,如 "9-18"</li>
 * </ul>
 * 周字段:0 和 7 都表示周日,1~6 表示周一到周六。
 * 当「日」与「周」同时被限制时,按标准 cron 语义取「或」。</p>
 */
@Component
public class CronParser {

    /** 前向查找的最大迭代保护(约 4 年) */
    private static final int MAX_ITERATIONS = 1500;

    /** 解析后的表达式:各字段允许值的集合 */
    private record ParsedCron(Set<Integer> seconds, Set<Integer> minutes, Set<Integer> hours,
                              Set<Integer> daysOfMonth, boolean domRestricted,
                              Set<Integer> months,
                              Set<Integer> daysOfWeek, boolean dowRestricted) {
    }

    /** 校验表达式是否合法 */
    public boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 校验并抛出业务异常(带原因) */
    public void validate(String expression) {
        parse(expression);
    }

    /**
     * 计算 from 之后的下一次触发时间(严格大于 from,精确到秒)。
     * 找不到(如 2 月 30 日之类永不满足的表达式)时抛 BizException。
     */
    public LocalDateTime nextFireTime(String expression, LocalDateTime from) {
        ParsedCron cron = parse(expression);
        LocalDateTime t = from.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < MAX_ITERATIONS * 24 * 60; i++) {
            if (!cron.months().contains(t.getMonthValue())) {
                t = t.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
                continue;
            }
            if (!dayMatches(cron, t)) {
                t = t.plusDays(1).truncatedTo(ChronoUnit.DAYS);
                continue;
            }
            if (!cron.hours().contains(t.getHour())) {
                t = t.plusHours(1).truncatedTo(ChronoUnit.HOURS);
                continue;
            }
            if (!cron.minutes().contains(t.getMinute())) {
                t = t.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
                continue;
            }
            if (!cron.seconds().contains(t.getSecond())) {
                // 跳到本分钟内下一个合法秒,或进位到下一分钟
                Integer nextSec = null;
                for (Integer sec : cron.seconds()) {
                    if (sec > t.getSecond()) {
                        nextSec = sec;
                        break;
                    }
                }
                t = nextSec != null
                        ? t.withSecond(nextSec)
                        : t.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
                continue;
            }
            return t;
        }
        throw BizException.badRequest("cron 表达式在未来 4 年内没有触发时间: " + expression);
    }

    // ==================== 解析与匹配 ====================

    private ParsedCron parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw BizException.badRequest("cron 表达式不能为空");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 6) {
            throw BizException.badRequest("cron 表达式必须是 6 位(秒 分 时 日 月 周): " + expression);
        }
        Set<Integer> seconds = parseField(fields[0], 0, 59, "秒");
        Set<Integer> minutes = parseField(fields[1], 0, 59, "分");
        Set<Integer> hours = parseField(fields[2], 0, 23, "时");
        boolean domRestricted = isRestricted(fields[3]);
        Set<Integer> daysOfMonth = parseField(fields[3], 1, 31, "日");
        Set<Integer> months = parseField(fields[4], 1, 12, "月");
        boolean dowRestricted = isRestricted(fields[5]);
        Set<Integer> daysOfWeek = parseField(fields[5], 0, 7, "周");
        // 7 归一化为 0(周日)
        if (daysOfWeek.remove(7)) {
            daysOfWeek.add(0);
        }
        return new ParsedCron(seconds, minutes, hours, daysOfMonth, domRestricted, months, daysOfWeek, dowRestricted);
    }

    /** 该字段是否被显式限制(非 * / ?) */
    private boolean isRestricted(String field) {
        return !(field.equals("*") || field.equals("?"));
    }

    /** 解析单个字段为允许值集合 */
    private Set<Integer> parseField(String field, int min, int max, String label) {
        Set<Integer> values = new TreeSet<>();
        if (field.equals("*") || field.equals("?")) {
            for (int i = min; i <= max; i++) {
                values.add(i);
            }
            return values;
        }
        for (String part : field.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                throw BizException.badRequest("cron " + label + "字段含空项: " + field);
            }
            if (part.contains("/")) {
                // 步进: "*/n"、"a/n"、"a-b/n"
                String[] seg = part.split("/");
                if (seg.length != 2) {
                    throw BizException.badRequest("cron " + label + "字段步进格式错误: " + part);
                }
                int step = parseInt(seg[1], label);
                if (step <= 0) {
                    throw BizException.badRequest("cron " + label + "字段步长必须为正数: " + part);
                }
                int from = seg[0].equals("*") ? min : parseInt(seg[0], label);
                int to = seg[0].contains("-") ? parseInt(seg[0].split("-")[1], label) : max;
                if (seg[0].contains("-")) {
                    from = parseInt(seg[0].split("-")[0], label);
                }
                for (int i = from; i <= Math.min(to, max); i += step) {
                    values.add(i);
                }
            } else if (part.contains("-")) {
                // 区间: "a-b"
                String[] seg = part.split("-");
                if (seg.length != 2) {
                    throw BizException.badRequest("cron " + label + "字段区间格式错误: " + part);
                }
                int from = parseInt(seg[0], label);
                int to = parseInt(seg[1], label);
                if (from > to) {
                    throw BizException.badRequest("cron " + label + "字段区间起点大于终点: " + part);
                }
                for (int i = from; i <= to; i++) {
                    checkRange(i, min, max, label);
                    values.add(i);
                }
            } else {
                int v = parseInt(part, label);
                checkRange(v, min, max, label);
                values.add(v);
            }
        }
        return values;
    }

    private int parseInt(String s, String label) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw BizException.badRequest("cron " + label + "字段含非数字: " + s);
        }
    }

    private void checkRange(int v, int min, int max, String label) {
        if (v < min || v > max) {
            throw BizException.badRequest("cron " + label + "字段越界[" + min + "," + max + "]: " + v);
        }
    }

    /** 日/周匹配:两者都被限制时取「或」,否则取「与」 */
    private boolean dayMatches(ParsedCron cron, LocalDateTime t) {
        boolean domMatch = cron.daysOfMonth().contains(t.getDayOfMonth());
        // Java DayOfWeek: 周一1..周日7;cron: 周日0,周一1..周六6
        int cronDow = t.getDayOfWeek().getValue() % 7;
        boolean dowMatch = cron.daysOfWeek().contains(cronDow);
        if (cron.domRestricted() && cron.dowRestricted()) {
            return domMatch || dowMatch;
        }
        return domMatch && dowMatch;
    }
}
