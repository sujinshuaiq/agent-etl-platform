package com.dataforge.etl.schedule.service;

import com.dataforge.etl.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CronParser 单元测试:六位 cron(秒 分 时 日 月 周)解析与下次触发时间计算。
 */
class CronParserTest {

    private final CronParser cronParser = new CronParser();

    @Test
    void 步进秒_每10秒() {
        LocalDateTime base = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        assertEquals(LocalDateTime.of(2025, 6, 1, 12, 0, 10),
                cronParser.nextFireTime("*/10 * * * * *", base));
        // 从 12:00:05 出发,下一次是 12:00:10
        assertEquals(LocalDateTime.of(2025, 6, 1, 12, 0, 10),
                cronParser.nextFireTime("*/10 * * * * *", base.plusSeconds(5)));
        // 从 12:00:10 出发,严格取下一个:12:00:20
        assertEquals(LocalDateTime.of(2025, 6, 1, 12, 0, 20),
                cronParser.nextFireTime("*/10 * * * * *", base.plusSeconds(10)));
    }

    @Test
    void 定点_每日9点30分() {
        LocalDateTime morning = LocalDateTime.of(2025, 6, 1, 8, 0, 0);
        assertEquals(LocalDateTime.of(2025, 6, 1, 9, 30, 0),
                cronParser.nextFireTime("0 30 9 * * *", morning));
        // 当天已过 9:30 → 次日
        LocalDateTime after = LocalDateTime.of(2025, 6, 1, 10, 0, 0);
        assertEquals(LocalDateTime.of(2025, 6, 2, 9, 30, 0),
                cronParser.nextFireTime("0 30 9 * * *", after));
    }

    @Test
    void 问号视同星号() {
        LocalDateTime base = LocalDateTime.of(2025, 6, 1, 8, 0, 0);
        assertEquals(LocalDateTime.of(2025, 6, 1, 9, 30, 0),
                cronParser.nextFireTime("0 30 9 ? * *", base));
    }

    @Test
    void 逗号列表_每小时的0分和30分() {
        LocalDateTime base = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        assertEquals(LocalDateTime.of(2025, 6, 1, 12, 30, 0),
                cronParser.nextFireTime("0 0,30 * * * *", base));
        LocalDateTime after30 = LocalDateTime.of(2025, 6, 1, 12, 30, 0);
        assertEquals(LocalDateTime.of(2025, 6, 1, 13, 0, 0),
                cronParser.nextFireTime("0 0,30 * * * *", after30));
    }

    @Test
    void 周字段_每周一中午12点() {
        // 2025-06-02 是周一
        LocalDateTime sunday = LocalDateTime.of(2025, 6, 1, 12, 0, 0); // 周日
        LocalDateTime next = cronParser.nextFireTime("0 0 12 * * 1", sunday);
        assertEquals(LocalDateTime.of(2025, 6, 2, 12, 0, 0), next);
        assertEquals(1, next.getDayOfWeek().getValue());
    }

    @Test
    void 区间_工作时段每小时() {
        LocalDateTime base = LocalDateTime.of(2025, 6, 1, 8, 0, 0);
        assertEquals(LocalDateTime.of(2025, 6, 1, 9, 0, 0),
                cronParser.nextFireTime("0 0 9-18 * * *", base));
    }

    @Test
    void 月份限制_每年1月1日() {
        LocalDateTime base = LocalDateTime.of(2025, 6, 1, 0, 0, 0);
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                cronParser.nextFireTime("0 0 0 1 1 *", base));
    }

    @Test
    void 非法表达式_校验失败() {
        assertFalse(cronParser.isValid("not a cron"));
        assertFalse(cronParser.isValid("* * *"));          // 位数不足
        assertFalse(cronParser.isValid("99 * * * * *"));   // 秒越界
        assertFalse(cronParser.isValid(null));
        assertThrows(BizException.class, () -> cronParser.nextFireTime("abc * * * * *", LocalDateTime.now()));
    }

    @Test
    void 合法表达式_校验通过() {
        for (String expr : List.of("* * * * * *", "0 0 2 * * *", "*/5 * * * * *",
                "0 0,30 9-18 ? * 1-5", "0 0 0 1 1 *")) {
            assertTrue(cronParser.isValid(expr), "应当合法: " + expr);
        }
    }
}
