package cn.edu.pulseatlas.spark;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TextRules {
    // 【答辩讲解】这是情感词典——整套情感分析的基础就在这两个列表里。
    // 正面词：不错、喜欢、好听、惊喜、满意、精彩、震撼、超赞、开心、值得（还有英文的）
    // 负面词：一般、不好、失望、差、糟糕、混乱、拥挤、退票、贵、不满
    // 我把这些词事先整理成固定词典，运行时只做"评论里有没有出现这些词"的匹配。
    // 这样做的好处是**可解释、可复查**——被问到某条评论为什么得这个分，我能指着词典说是命中了哪些词，
    // 而黑盒模型给不出这个交代（这是我不用现成 NLP 模型的原因）。
    private static final List<String> POSITIVE = List.of(
            "amazing", "excellent", "good", "great", "love", "perfect", "wonderful",
            "不错", "喜欢", "好听", "惊喜", "满意", "精彩", "震撼", "超赞", "开心", "值得");
    private static final List<String> NEGATIVE = List.of(
            "awful", "bad", "disappoint", "poor", "terrible", "worse",
            "一般", "不好", "失望", "差", "糟糕", "混乱", "拥挤", "退票", "贵", "不满");
    // 【答辩讲解】主题词典：六个主题，每个主题配一组关键词。
    // 网页上的评论主题分布由这些规则对当前批次逐条归类得到。
    private static final Map<String, List<String>> TOPICS = topics();

    private TextRules() {
    }

    static Double sentiment(String text) {
        // 【答辩讲解】情感打分算法（就三行，但逻辑要说清楚）：
        // 数一下评论里出现了几个正面词、几个负面词，
        // 然后用 (正面数 - 负面数) / 总命中数，算出来的值范围天然就在 -1 到 1 之间，
        // 最后再用 max/min 卡一下边界，防止越界。
        // 举例：一条评论命中 3 个正面词、1 个负面词 → (3-1)/4 = 0.5，属于偏正面。
        // 注意：如果一条评论一个情感词都没命中，返回 null，前端显示为未判定，不做瞎猜。
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        int positive = occurrences(normalized, POSITIVE);
        int negative = occurrences(normalized, NEGATIVE);
        int total = positive + negative;
        if (total == 0) {
            return null;
        }
        return Math.max(-1.0, Math.min(1.0, (positive - negative) / (double) total));
    }

    static String topic(String text) {
        // 【答辩讲解】主题分类算法：六个主题逐个去数命中次数，谁命中得多就归谁；
        // 一个都没命中就归"其他"。这也是规则法，所以能解释为什么这条被归到"票价"。
        if (text == null || text.isBlank()) {
            return "other";
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        String bestTopic = "other";
        int bestScore = 0;
        for (Map.Entry<String, List<String>> entry : TOPICS.entrySet()) {
            int score = occurrences(normalized, entry.getValue());
            if (score > bestScore) {
                bestTopic = entry.getKey();
                bestScore = score;
            }
        }
        return bestTopic;
    }

    private static int occurrences(String text, List<String> terms) {
        int result = 0;
        for (String term : terms) {
            int start = 0;
            while ((start = text.indexOf(term, start)) >= 0) {
                result++;
                start += term.length();
            }
        }
        return result;
    }

    private static Map<String, List<String>> topics() {
        Map<String, List<String>> topics = new LinkedHashMap<>();
        topics.put("ticketing", List.of("ticket", "refund", "抢票", "票务", "退票", "出票", "黄牛"));
        topics.put("price", List.of("price", "expensive", "价格", "票价", "太贵", "性价比"));
        topics.put("performance", List.of("sing", "performance", "voice", "演唱", "唱功", "表演", "歌手", "演员"));
        topics.put("audio_visual", List.of("sound", "light", "screen", "音响", "灯光", "大屏", "舞美", "视线"));
        topics.put("venue_service", List.of("staff", "seat", "service", "场馆", "座位", "服务", "安检"));
        topics.put("transport", List.of("traffic", "parking", "subway", "交通", "停车", "地铁", "散场"));
        return topics;
    }

    // ================= 场馆名 / 艺人名清洗 =================
    // 【答辩讲解】这两个函数是后来补的。爬下来的是页面原文，场馆字段里会连带
    // 页面上的装饰点（"· 润百颜ECM中心"）、开场时间（"14:30 云间剧院"）、
    // 地址备注（"(上元门地铁站东400米)"）；艺人有 "Unknown artist" 占位值，
    // 还有被截断的名字（"BABYMONSTER…[춤 (CHOOM"）。入库前统一清一遍。

    private static final java.util.regex.Pattern VENUE_LEADING_NOISE =
            java.util.regex.Pattern.compile("^[\\s·•\\-—–、|/\\\\.,，]+");

    private static final java.util.regex.Pattern VENUE_TIME_PREFIX =
            java.util.regex.Pattern.compile("^\\d{1,2}[:：]\\d{2}\\s*");

    private static final java.util.regex.Pattern VENUE_PARENTHETICAL =
            java.util.regex.Pattern.compile("[（(][^（()）]*[)）]");

    /** 这些值代表"场馆还没公布"，不是真场馆名，由调用方决定怎么显示。 */
    static String venue(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replaceAll("\\s+", " ").trim();
        if (value.isEmpty() || isPlaceholder(value, "unknown venue", "未知场馆", "场馆待定", "场馆未公布")) {
            return null;
        }
        for (int round = 0; round < 2; round++) {
            value = VENUE_LEADING_NOISE.matcher(value).replaceFirst("");
            value = VENUE_TIME_PREFIX.matcher(value).replaceFirst("");
        }
        value = VENUE_PARENTHETICAL.matcher(value).replaceAll("").trim();
        value = value.replaceFirst("[\\s·•\\-—–、|/.,，]+$", "").trim();
        if (value.isEmpty() || isPlaceholder(value, "未知场馆", "场馆待定", "场馆未公布")) {
            return null;
        }
        if (value.length() > 60) {
            value = value.substring(0, 60).trim();
        }
        return value.isEmpty() ? null : value;
    }

    /** 艺人名清洗：去掉占位值，修掉被截断的方括号。 */
    static String artist(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replaceAll("\\s+", " ").trim();
        if (value.isEmpty() || isPlaceholder(value, "unknown artist", "未知艺人", "未知歌手")) {
            return null;
        }
        value = truncateAtUnbalancedBracket(value).trim();
        value = value.replaceFirst("^[\\]\\}）)」』】\\s]+", "").trim();
        value = value.replaceFirst("[\\s\\-—–·、,，]+$", "").trim();
        if (value.isEmpty() || value.length() > 120) {
            return null;
        }
        return value;
    }

    private static boolean isPlaceholder(String value, String... candidates) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (lower.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 左括号没有闭合说明名字被截断了，从最外层那个没闭合的 "[" 处切掉。 */
    private static String truncateAtUnbalancedBracket(String raw) {
        java.util.Deque<Integer> open = new java.util.ArrayDeque<>();
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
        return raw.substring(0, open.peekLast());
    }
}
