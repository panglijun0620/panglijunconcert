package com.concert.analytics.export;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CSV 生成工具。
 *
 * <p>处理三件事：一是加 UTF-8 BOM，否则 Excel 双击打开中文全是乱码；
 * 二是字段里带逗号、引号、换行时按 CSV 规范加引号转义；
 * 三是以等号、加号、@ 开头的文本前置单引号，避免被表格软件当成公式执行。
 */
public final class CsvWriter {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String NEEDS_QUOTE = ",\"\r\n";

    private static final String FORMULA_PREFIXES = "=+@\t\r";

    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?%?");

    private CsvWriter() {
    }

    public static byte[] render(List<String> header, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        appendRow(builder, header);
        for (List<String> row : rows) {
            appendRow(builder, row);
        }
        byte[] body = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, result, 0, BOM.length);
        System.arraycopy(body, 0, result, BOM.length, body.length);
        return result;
    }

    private static void appendRow(StringBuilder builder, List<String> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(escape(cells.get(index)));
        }
        builder.append("\r\n");
    }

    static String escape(String raw) {
        String value = neutralize(raw == null ? "" : raw);
        boolean needsQuote = false;
        for (int index = 0; index < value.length(); index++) {
            if (NEEDS_QUOTE.indexOf(value.charAt(index)) >= 0) {
                needsQuote = true;
                break;
            }
        }
        if (needsQuote) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** 防公式注入：Excel 会把 =1+1 当公式跑，纯数字的负号不算在内。 */
    private static String neutralize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (FORMULA_PREFIXES.indexOf(first) < 0 && first != '-') {
            return value;
        }
        if (NUMERIC.matcher(value).matches()) {
            return value;
        }
        return "'" + value;
    }
}
