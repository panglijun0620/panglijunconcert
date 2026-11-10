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
    // 【2026-11-10 词典扩充】人工标注 300 条评测暴露了第一个问题：词典太小。
    // 226 条被标成"好评"的评论里有 42 条，规则一个正面词都没命中——"感动""治愈""燃""惊艳"
    // 这些满篇都是的夸人话，原词典里一个都没有，于是整条被判成中性或负面。
    // 这一版把正面词从 24 个扩到 81 个、负面词从 20 个扩到 72 个，
    // 补的都是评论里高频出现的说法（唱功、诚意、回本、值回票价／难听、敷衍、遮挡、听不清）。
    private static final List<String> POSITIVE = List.of(
            "amazing", "excellent", "good", "great", "love", "perfect", "wonderful", "yyds", "nice",
            "不错", "喜欢", "好听", "好看", "好帅", "很帅", "太帅", "惊喜", "满意", "精彩", "震撼", "超赞",
            "开心", "值得", "太棒", "真棒", "很棒", "好棒", "棒", "封神", "值了", "好听哭", "值得一看",
            "值回票价", "回本", "超值", "太值", "感动", "治愈", "享受", "舒服", "完美", "燃", "炸裂",
            "超燃", "太燃", "绝了", "牛", "无敌", "厉害", "诚意", "用心", "认真", "稳", "稳如", "顶",
            "满分", "难忘", "推荐", "好听极了", "唱得好", "唱得", "演得好", "唱功", "实力", "实力派",
            "氛围", "尽兴", "意犹未尽", "圈粉", "高质量", "惊艳", "帅", "美", "不虚", "名不虚传",
            "太强", "很强", "太好了", "很好", "超好", "真好");
    private static final List<String> NEGATIVE = List.of(
            "awful", "bad", "disappoint", "poor", "terrible", "worse",
            "一般", "不好", "失望", "差", "差劲", "差不了", "糟糕", "混乱", "拥挤", "退票", "贵", "太贵",
            "好贵", "贵得", "不满", "不值", "假唱", "跑调", "走音", "破音", "划水", "难听", "难看",
            "敷衍", "糊弄", "尴尬", "无聊", "闷", "闷热", "挤", "排队", "迟到", "延迟", "临时取消",
            "坑", "骗", "听不清", "看不见", "看不清", "被挡", "遮挡", "视野差", "坐得远", "断电", "断音",
            "卡顿", "拉胯", "拉垮", "崩", "吐槽", "槽点", "不足", "遗憾", "可惜", "吵", "脏", "乱",
            "素质差", "态度差", "没诚意", "退钱", "不推荐", "后悔", "无语", "离谱", "失误", "翻车",
            "音响差", "声音小", "没声音", "热死", "冷");

    // 【答辩讲解】这是后来补的"误伤拦截表"，也是我这次自检里最实际的收获。
    // 词典里"差""贵"是单个汉字，直接做子串匹配会在别的词里撞车：
    //   ① 地名——"贵阳站""贵州"，一场在贵阳的演出，标题里带"贵"就被判成负面，
    //      实测 GAI周延 154 条"负面"里有 145 条是这么来的，评论原文其实是"帅的批爆""夯爆了"；
    //   ② 否定式——"不贵""没差""没让我失望"，字面上有负面词，意思正好反过来；
    //   ③ 近义好词——"珍贵""宝贵"本身就是褒义。
    // 处理办法：先把这些片段从文本里抹掉（换成空格），再做词表匹配。
    // 这样"差""贵"这两个字本身还留着，正常语境下的"太差""好贵"照样能识别，
    // 只是不会再被地名和否定句骗到。属于规则法里标准的"停用片段"处理。
    //
    // 第二轮自检（11-09）又补了三类：抽查判负评论原文时发现，
    // "史诗一般""神一般的演唱会""如诗人一般"里的"一般"是在打比方，是夸人的；
    // "好不好嘛""我拍的好不好看"里的"好不好"是疑问，不带贬义；
    // "翩翩贵公子""新贵妃醉酒""风雨贵人来"里的"贵"是称谓和歌名。
    // 另外"只是差一张门票"的"差"是"还差"的意思，不是"不好"。
    // 这几类在小样本艺人身上特别致命：毛不易只有 29 条可判定，错 1 条就差 3.4 个百分点。
    private static final java.util.regex.Pattern MISLEADING_FRAGMENT =
            java.util.regex.Pattern.compile(
                    // 地名与含"贵"的专名、称谓、歌名
                    "贵阳|贵州|贵安|贵在|富贵|贵族|贵气|珍贵|宝贵|贵重|贵宾|贵人|贵妃|贵公子|贵妇"
                    // 含"差"的常用词、"还差"语义
                    + "|差不多|差点|差一点|时差|出差|误差|差异|反差|参差|只是差|只差|就差|差一张|差两张|差几张"
                    // 否定式：字面有负面词，语义相反
                    + "|不贵|不算贵|没多贵|不差|没差|不一般|非一般"
                    + "|不失望|不会失望|没让.{0,6}失望|没.{0,4}失望"
                    // 疑问式"好不好"，不是"不好"
                    + "|好不好"
                    // 比喻式"一般"：史诗一般、神一般、如诗人一般，是夸
                    + "|史诗一般|神一般|神仙一般|诗人一般|天使一般|梦幻一般|梦一般|童话一般|一般不"
                    + "|如.{0,4}一般|像.{0,4}一般"
                    // 【2026-11-10 补】固定搭配里带"不"但不是否定：名不虚传、不虚此行、不得不……
                    + "|名不虚传|不虚此行|不虚|不得不|忍不住|不得了|不容易|不仅|不但|不断|不少|不赖"
                    + "|错不了|差不了");
    // 【答辩讲解】主题词典：六个主题，每个主题配一组关键词。
    // 网页上的评论主题分布由这些规则对当前批次逐条归类得到。
    private static final Map<String, List<String>> TOPICS = topics();

    private TextRules() {
    }

    static Double sentiment(String text) {
        // 【答辩讲解】情感打分算法：
        // 数一下评论里出现了几个正面词、几个负面词，
        // 然后用 (正面数 - 负面数) / 总命中数，算出来的值范围天然就在 -1 到 1 之间，
        // 最后再用 max/min 卡一下边界，防止越界。
        // 举例：一条评论命中 3 个正面词、1 个负面词 → (3-1)/4 = 0.5，属于偏正面。
        // 注意：如果一条评论一个情感词都没命中，返回 null，前端显示为未判定，不做瞎猜。
        //
        // 【2026-11-10 加的否定判断】人工标注 300 条暴露出第二个问题：
        // "那首新歌不好听"里同时有"不好"（负面）和"好听"（正面），两个词各记一票，
        // 一正一负正好抵消成 0 分，一条真正的差评被判成了中性。
        // "不值得""不满意""不划算"全是这个毛病。
        // 处理办法：命中正面词时，先看它前面两个字里有没有"不/没/别/未/无/零"，
        // 有就说明这个词被否定了，票要投给负面；反过来"不划水""没迟到"票要投给正面。
        // 上面那张误伤拦截表已经把"名不虚传""不得不"这类假否定先抹掉了，不会误判。
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        // 先抹掉会和负面词撞车的片段（地名、否定式、近义好词、固定搭配），再数词。
        // 抹成空格而不是直接删除，是为了不让前后两个字粘成新词。
        normalized = MISLEADING_FRAGMENT.matcher(normalized).replaceAll(" ");
        int positive = 0;
        int negative = 0;
        for (String term : POSITIVE) {
            for (int index : occurrences(normalized, term)) {
                if (negated(normalized, index)) {
                    negative++;
                } else {
                    positive++;
                }
            }
        }
        for (String term : NEGATIVE) {
            for (int index : occurrences(normalized, term)) {
                if (negated(normalized, index)) {
                    positive++;
                } else {
                    negative++;
                }
            }
        }
        int total = positive + negative;
        if (total == 0) {
            return null;
        }
        return Math.max(-1.0, Math.min(1.0, (positive - negative) / (double) total));
    }

    /** 情感词前面两个字里出现否定词，说明这个词被否定了，极性要反过来。 */
    private static boolean negated(String text, int index) {
        int from = Math.max(0, index - 2);
        for (int i = from; i < index; i++) {
            char current = text.charAt(i);
            if (current == '不' || current == '没' || current == '别'
                    || current == '未' || current == '无' || current == '零') {
                return true;
            }
        }
        return false;
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
            result += occurrences(text, term).length;
        }
        return result;
    }

    /** 返回某个词在文本里每次出现的位置，供否定判断逐个检查。 */
    private static int[] occurrences(String text, String term) {
        int[] buffer = new int[8];
        int size = 0;
        int start = 0;
        while ((start = text.indexOf(term, start)) >= 0) {
            if (size == buffer.length) {
                buffer = java.util.Arrays.copyOf(buffer, size * 2);
            }
            buffer[size++] = start;
            start += term.length();
        }
        return java.util.Arrays.copyOf(buffer, size);
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
