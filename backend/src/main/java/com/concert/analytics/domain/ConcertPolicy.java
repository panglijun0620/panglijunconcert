package com.concert.analytics.domain;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ConcertPolicy {

    public static final String CATEGORY = "演唱会";
    public static final String ENDED_STATUS = "演出已结束";
    public static final String UNVERIFIED_STATUS = "票务状态待核实";
    public static final String PURCHASE_ACTION = "去购票";

    /** 城市名最长不会超过这个长度，超过的几乎都是把整段文案塞进了 city 字段。 */
    private static final int MAX_CITY_LENGTH = 16;

    /** 艺人名可能很长（拼盘演出会把好几个名字连起来），只挡明显异常的超长值。 */
    private static final int MAX_ARTIST_LENGTH = 120;

    /** 场馆名一般不超过 30 字，超过的多半是把地址、交通提示一起塞了进来。 */
    private static final int MAX_VENUE_LENGTH = 60;

    /** 场馆名前常见的装饰符号，例如 "· 润百颜ECM中心"。 */
    private static final Pattern VENUE_LEADING_NOISE =
            Pattern.compile("^[\\s·•\\-—–、|/\\\\.,，]+");

    /** 场馆名前被拼上的开场时间，例如 "14:30 云间剧院"。 */
    private static final Pattern VENUE_TIME_PREFIX =
            Pattern.compile("^\\d{1,2}[:：]\\d{2}\\s*");

    /** 场馆名后的括号备注，例如 "(上元门地铁站东400米)"、"（鸟巢）"。 */
    private static final Pattern VENUE_PARENTHETICAL =
            Pattern.compile("[（(][^（()）]*[)）]");

    /** 日期的星期标签，索引 0 对应周一（MySQL WEEKDAY 的返回值）。 */
    private static final String[] WEEKDAY_LABELS =
            {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 常见 HTML 命名实体，用于还原被转义的艺人名。 */
    private static final Map<String, String> HTML_ENTITIES;

    static {
        Map<String, String> entities = new LinkedHashMap<>();
        entities.put("amp", "&");
        entities.put("lt", "<");
        entities.put("gt", ">");
        entities.put("quot", "\"");
        entities.put("apos", "'");
        entities.put("nbsp", " ");
        entities.put("bull", "•");
        entities.put("middot", "·");
        entities.put("mdash", "—");
        entities.put("ndash", "–");
        entities.put("hellip", "…");
        entities.put("lsquo", "‘");
        entities.put("rsquo", "’");
        entities.put("ldquo", "“");
        entities.put("rdquo", "”");
        entities.put("times", "×");
        entities.put("divide", "÷");
        entities.put("plusmn", "±");
        entities.put("deg", "°");
        entities.put("copy", "©");
        entities.put("reg", "®");
        entities.put("trade", "™");
        HTML_ENTITIES = Map.copyOf(entities);
    }

    private ConcertPolicy() {
    }

    public static String saleStatus(Concert concert, LocalDate today) {
        return concert.getShowTime().toLocalDate().isBefore(today)
                ? ENDED_STATUS
                : upcomingSaleStatus(concert.getSaleStatus());
    }

    public static String upcomingSaleStatus(String sourceStatus) {
        return PURCHASE_ACTION.equals(sourceStatus) ? UNVERIFIED_STATUS : sourceStatus;
    }

    /**
     * 清洗城市筛选项：源数据里混进了 "Unknown"、"演出延期"，以及把促销文案拼进城市的情况
     * （例如 "中国香港[限时立减30元"）。这类值出现在下拉框里会让人一眼看出数据没洗干净。
     *
     * @return 可用的城市名；不是合法城市值时返回 null，由调用方过滤掉
     */
    public static String normalizeCity(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_CITY_LENGTH) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("unknown".equals(lower) || "null".equals(lower) || "n/a".equals(lower) || "none".equals(lower)) {
            return null;
        }
        // 促销文案/备注被拼进了城市：出现括号、价格、折扣字样就判为脏值
        if (value.indexOf('[') >= 0 || value.indexOf(']') >= 0
                || value.indexOf('【') >= 0 || value.indexOf('】') >= 0
                || value.indexOf('(') >= 0 || value.indexOf('（') >= 0) {
            return null;
        }
        if (value.contains("限时") || value.contains("立减") || value.contains("折扣")
                || value.contains("元") || value.contains("起")) {
            return null;
        }
        // 票务状态被误写进了城市字段
        if (value.contains("延期") || value.contains("取消") || value.contains("待定")
                || value.equals(ENDED_STATUS) || value.equals(UNVERIFIED_STATUS)) {
            return null;
        }
        return value;
    }

    /**
     * 清洗艺人筛选项：源数据里带着没解码的 HTML 实体（&amp;bull;、&amp;mdash; 等），直接展示会变成乱码；
     * 另外还有 "Unknown artist" 这种占位值，以及被截断的 "[춤 (CHOOM"（左括号没有对应的右括号）。
     */
    public static String normalizeArtist(String raw) {
        if (raw == null) {
            return null;
        }
        String value = decodeHtmlEntities(raw).replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("unknown".equals(lower) || "unknown artist".equals(lower) || "未知艺人".equals(lower)
                || "未知歌手".equals(lower)
                || "null".equals(lower) || "n/a".equals(lower) || "none".equals(lower)) {
            return null;
        }
        // 名字被截断时，左括号后面没有右括号，从最外层那个没有闭合的 "[" 处截断
        value = truncateAtUnbalancedBracket(value);
        // 去掉开头残留的 "]"、"」" 等右半边括号
        value = value.replaceFirst("^[\\]\\}）)」』】\\s]+", "").trim();
        value = value.replaceFirst("[\\s\\-—–·、,，]+$", "").trim();
        if (value.isEmpty() || value.length() > MAX_ARTIST_LENGTH) {
            return null;
        }
        return value;
    }

    /**
     * 清洗场馆名：源数据里混着 "· 润百颜ECM中心"（前面带装饰点）、"14:30 云间剧院"（把开场时间当成了名字）、
     * "(上元门地铁站东400米)"（地址备注跟在后面），还有 "Unknown" 这类占位值。
     *
     * @return 可用的场馆名；明显不是场馆值时返回 null，由调用方过滤掉
     */
    public static String normalizeVenue(String raw) {
        if (raw == null) {
            return null;
        }
        String value = decodeHtmlEntities(raw).replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("unknown".equals(lower) || "unknown venue".equals(lower) || "未知场馆".equals(lower)
                || "场馆待定".equals(lower) || "场馆未公布".equals(lower)
                || "null".equals(lower) || "n/a".equals(lower) || "none".equals(lower)) {
            return null;
        }
        // 前导符号与开场时间可能同时出现，例如 "· 14:30 云间剧院"，所以清理两轮
        for (int round = 0; round < 2; round++) {
            value = VENUE_LEADING_NOISE.matcher(value).replaceFirst("");
            value = VENUE_TIME_PREFIX.matcher(value).replaceFirst("");
        }
        value = VENUE_PARENTHETICAL.matcher(value).replaceAll("").trim();
        value = value.replaceFirst("[\\s·•\\-—–、|/.,，]+$", "").trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_VENUE_LENGTH) {
            value = value.substring(0, MAX_VENUE_LENGTH).trim();
        }
        return value.isEmpty() ? null : value;
    }

    /** 把 MySQL WEEKDAY() 的返回值（0=周一）换成中文标签。 */
    public static String weekdayLabel(int mysqlWeekday) {
        int index = Math.floorMod(mysqlWeekday, WEEKDAY_LABELS.length);
        return WEEKDAY_LABELS[index];
    }

    /** 从最外层没有闭合的 "[" 处截断，用于修复爬取时被截掉尾巴的名字。 */
    private static String truncateAtUnbalancedBracket(String raw) {
        Deque<Integer> open = new ArrayDeque<>();
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current == '[') {
                open.push(index);
            } else if (current == ']' && !open.isEmpty()) {
                open.pop();
            }
        }
        if (open.isEmpty()) {
            return raw;
        }
        int cut = open.peekLast();
        return raw.substring(0, cut);
    }

    /** 还原命名实体与数字实体，碰到不认识的实体原样保留，不抛异常。 */
    public static String decodeHtmlEntities(String raw) {
        if (raw == null || raw.indexOf('&') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int index = 0;
        while (index < raw.length()) {
            char current = raw.charAt(index);
            if (current == '&') {
                int semicolon = raw.indexOf(';', index + 1);
                if (semicolon > index + 1 && semicolon - index <= 12) {
                    String body = raw.substring(index + 1, semicolon);
                    String replacement = resolveEntity(body);
                    if (replacement != null) {
                        out.append(replacement);
                        index = semicolon + 1;
                        continue;
                    }
                }
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private static String resolveEntity(String body) {
        if (body.startsWith("#")) {
            try {
                int codePoint = (body.length() > 2 && (body.charAt(1) == 'x' || body.charAt(1) == 'X'))
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                return new String(Character.toChars(codePoint));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return HTML_ENTITIES.get(body.toLowerCase(Locale.ROOT));
    }
}
