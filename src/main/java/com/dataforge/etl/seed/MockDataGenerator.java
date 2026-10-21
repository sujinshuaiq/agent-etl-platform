package com.dataforge.etl.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 业务源表数据生成器:在 H2 中重建 ods_users(1 万)/ ods_orders(5 万)/ ods_events(10 万)
 * 三张源表并用 JdbcTemplate 批量插入贴近真实业务的数据(中文姓名、手机号、邮箱、金额、事件类型等)。
 *
 * <p>使用固定随机种子,保证每次全新初始化得到的数据完全一致(可重复演示)。</p>
 */
@Component
public class MockDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(MockDataGenerator.class);

    /** 固定随机种子:确定性数据 */
    private static final long RANDOM_SEED = 20240520L;
    private static final int BATCH_SIZE = 1000;

    private static final String[] SURNAMES = {"王", "李", "张", "刘", "陈", "杨", "黄", "赵", "周", "吴", "徐", "孙", "马", "朱", "胡", "郭", "何", "林", "罗", "郑"};
    private static final String[] GIVEN_NAMES = {"伟", "芳", "娜", "敏", "静", "磊", "军", "洋", "勇", "艳", "杰", "涛", "明", "超", "秀英", "霞", "平", "刚", "桂英", "子轩", "雨桐", "浩然", "欣怡", "梓萱"};
    private static final String[] CITIES = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆", "苏州", "长沙"};
    private static final String[] EMAIL_DOMAINS = {"qq.com", "163.com", "126.com", "gmail.com", "outlook.com", "sina.com"};
    private static final String[] PRODUCT_CATEGORIES = {"数码家电", "服饰鞋包", "食品饮料", "美妆个护", "家居家装", "图书音像", "母婴用品", "运动户外"};
    private static final String[] PRODUCTS = {"智能手机", "无线耳机", "运动鞋", "保温杯", "洗面奶", "书架", "小说套装", "婴儿奶粉", "瑜伽垫", "机械键盘", "行李箱", "咖啡豆"};
    private static final String[] ORDER_STATUSES = {"PAID", "SHIPPED", "COMPLETED", "REFUNDED", "PENDING"};
    private static final String[] EVENT_TYPES = {"CLICK", "VIEW", "PURCHASE", "LOGIN", "LOGOUT"};
    private static final String[] PAGES = {"/home", "/product/detail", "/cart", "/order/confirm", "/user/center", "/search"};
    private static final String[] DEVICES = {"iOS", "Android", "Web", "H5", "MiniProgram"};

    private final JdbcTemplate jdbcTemplate;

    public MockDataGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 生成全部业务源表(表存在且有数据时跳过) */
    public void generateBusinessTables() {
        Random random = new Random(RANDOM_SEED);
        generateUsers(random);
        generateOrders(random);
        generateEvents(random);
    }

    /** ods_users:1 万行用户数据 */
    private void generateUsers(Random random) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ods_users");
        jdbcTemplate.execute("CREATE TABLE ods_users ("
                + "user_id BIGINT PRIMARY KEY, "
                + "username VARCHAR(64), "
                + "real_name VARCHAR(64), "
                + "phone VARCHAR(20), "
                + "email VARCHAR(128), "
                + "gender VARCHAR(8), "
                + "age INT, "
                + "city VARCHAR(32), "
                + "register_time TIMESTAMP, "
                + "status VARCHAR(16))");
        long start = System.currentTimeMillis();
        int total = 10000;
        int inserted = 0;
        while (inserted < total) {
            int batch = Math.min(BATCH_SIZE, total - inserted);
            List<Object[]> args = new ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                long userId = inserted + i + 1L;
                String surname = SURNAMES[random.nextInt(SURNAMES.length)];
                String given = GIVEN_NAMES[random.nextInt(GIVEN_NAMES.length)];
                String username = (surname + given).toLowerCase() + random.nextInt(10000);
                String phone = "1" + (3 + random.nextInt(7)) + String.format("%09d", random.nextInt(1000000000));
                String email = username + "@" + EMAIL_DOMAINS[random.nextInt(EMAIL_DOMAINS.length)];
                String gender = random.nextBoolean() ? "M" : "F";
                int age = 18 + random.nextInt(43);
                String city = CITIES[random.nextInt(CITIES.length)];
                LocalDateTime registerTime = LocalDateTime.now().minusDays(random.nextInt(720))
                        .minusHours(random.nextInt(24));
                String status = random.nextInt(100) < 96 ? "NORMAL" : "FROZEN";
                args.add(new Object[]{userId, username, surname + given, phone, email, gender, age, city,
                        Timestamp.valueOf(registerTime), status});
            }
            jdbcTemplate.batchUpdate("INSERT INTO ods_users VALUES (?,?,?,?,?,?,?,?,?,?)", args);
            inserted += batch;
        }
        log.info("种子数据: ods_users 生成完成, {} 行, 耗时 {}ms", total, System.currentTimeMillis() - start);
    }

    /** ods_orders:5 万行订单数据 */
    private void generateOrders(Random random) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ods_orders");
        jdbcTemplate.execute("CREATE TABLE ods_orders ("
                + "order_id BIGINT PRIMARY KEY, "
                + "order_no VARCHAR(40), "
                + "user_id BIGINT, "
                + "product_name VARCHAR(64), "
                + "category VARCHAR(32), "
                + "amount DECIMAL(12,2), "
                + "quantity INT, "
                + "status VARCHAR(16), "
                + "pay_time TIMESTAMP, "
                + "created_at TIMESTAMP)");
        long start = System.currentTimeMillis();
        int total = 50000;
        int inserted = 0;
        while (inserted < total) {
            int batch = Math.min(BATCH_SIZE, total - inserted);
            List<Object[]> args = new ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                long orderId = inserted + i + 1L;
                String orderNo = "SO" + String.format("%012d", orderId);
                long userId = 1 + random.nextInt(10000);
                String product = PRODUCTS[random.nextInt(PRODUCTS.length)];
                String category = PRODUCT_CATEGORIES[random.nextInt(PRODUCT_CATEGORIES.length)];
                double amount = Math.round((9.9 + random.nextDouble() * 4990.0) * 100) / 100.0;
                int quantity = 1 + random.nextInt(5);
                String status = ORDER_STATUSES[random.nextInt(ORDER_STATUSES.length)];
                LocalDateTime createdAt = LocalDateTime.now().minusDays(random.nextInt(90))
                        .minusHours(random.nextInt(24)).minusMinutes(random.nextInt(60));
                LocalDateTime payTime = createdAt.plusMinutes(1 + random.nextInt(30));
                args.add(new Object[]{orderId, orderNo, userId, product, category, amount, quantity, status,
                        Timestamp.valueOf(payTime), Timestamp.valueOf(createdAt)});
            }
            jdbcTemplate.batchUpdate("INSERT INTO ods_orders VALUES (?,?,?,?,?,?,?,?,?,?)", args);
            inserted += batch;
        }
        log.info("种子数据: ods_orders 生成完成, {} 行, 耗时 {}ms", total, System.currentTimeMillis() - start);
    }

    /** ods_events:10 万行行为事件数据 */
    private void generateEvents(Random random) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ods_events");
        jdbcTemplate.execute("CREATE TABLE ods_events ("
                + "event_id BIGINT PRIMARY KEY, "
                + "user_id BIGINT, "
                + "event_type VARCHAR(16), "
                + "page VARCHAR(64), "
                + "device VARCHAR(16), "
                + "ip VARCHAR(40), "
                + "duration INT, "
                + "created_at TIMESTAMP)");
        long start = System.currentTimeMillis();
        int total = 100000;
        int inserted = 0;
        while (inserted < total) {
            int batch = Math.min(BATCH_SIZE, total - inserted);
            List<Object[]> args = new ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                long eventId = inserted + i + 1L;
                long userId = 1 + random.nextInt(10000);
                String eventType = EVENT_TYPES[random.nextInt(EVENT_TYPES.length)];
                String page = PAGES[random.nextInt(PAGES.length)];
                String device = DEVICES[random.nextInt(DEVICES.length)];
                String ip = "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
                int duration = random.nextInt(3600);
                LocalDateTime createdAt = LocalDateTime.now().minusDays(random.nextInt(30))
                        .minusHours(random.nextInt(24)).minusMinutes(random.nextInt(60));
                args.add(new Object[]{eventId, userId, eventType, page, device, ip, duration,
                        Timestamp.valueOf(createdAt)});
            }
            jdbcTemplate.batchUpdate("INSERT INTO ods_events VALUES (?,?,?,?,?,?,?,?)", args);
            inserted += batch;
        }
        log.info("种子数据: ods_events 生成完成, {} 行, 耗时 {}ms", total, System.currentTimeMillis() - start);
    }
}
