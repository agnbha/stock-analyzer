package com.stockanalyzer.canslim;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * screener.in's AI summary of an earnings call, as lines to show one under another: its headings, and the points
 * under each at the depth they were nested. Also the allowance the page states under it, how many summaries the
 * account has opened in the last thirty days out of how many it may.
 *
 * @param viewed    summaries opened in the last thirty days, this one included; null if the page did not say
 * @param allowance how many the account may open in thirty days; null if the page did not say
 */
public record ConcallSummary(List<Line> lines, Integer viewed, Integer allowance) {

    /** @param depth 0 for a heading, 1 for a point or a paragraph, 2 and more for a point under a point */
    public record Line(int depth, String text) {
    }

    private static final Pattern BLOCK = Pattern.compile("<(/?)(h[1-6]|ul|ol|li|p|hr)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Pattern VIEWED = Pattern.compile("Summaries viewed:\\s*(\\d+)\\s*/\\s*(\\d+)");
    /** A summary runs to a few dozen lines; anything far longer is not one. */
    private static final int MAX_LINES = 300;

    public ConcallSummary {
        lines = List.copyOf(lines);
    }

    /** The summary inside the page's {@code <article>}, and the allowance line after it. */
    public static ConcallSummary parse(String html) {
        int start = html.indexOf("<article");
        int end = html.indexOf("</article>", Math.max(start, 0));
        String article = start < 0 ? "" : html.substring(html.indexOf('>', start) + 1, end < 0 ? html.length() : end);
        Matcher viewed = VIEWED.matcher(TAG.matcher(html).replaceAll(" "));
        return new ConcallSummary(lines(article), viewed.find() ? Integer.valueOf(viewed.group(1)) : null,
                viewed.find(0) ? Integer.valueOf(viewed.group(2)) : null);
    }

    private static List<Line> lines(String article) {
        List<Line> lines = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int depth = 1;
        int lists = 0;
        int at = 0;
        Matcher block = BLOCK.matcher(article);
        while (block.find()) {
            // Every block boundary ends the text before it, at the depth that text was written at.
            text.append(article, at, block.start());
            at = block.end();
            flush(lines, text, depth);
            boolean closing = !block.group(1).isEmpty();
            String tag = block.group(2).toLowerCase();
            if (tag.equals("ul") || tag.equals("ol")) {
                lists = Math.max(0, lists + (closing ? -1 : 1));
            }
            depth = tag.startsWith("h") && !closing ? 0 : Math.max(1, lists);
        }
        text.append(article.substring(at));
        flush(lines, text, depth);
        return lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
    }

    private static void flush(List<Line> lines, StringBuilder text, int depth) {
        String clean = decode(TAG.matcher(text).replaceAll("")).replaceAll("\\s+", " ").strip();
        if (!clean.isEmpty()) {
            lines.add(new Line(depth, clean));
        }
        text.setLength(0);
    }

    private static String decode(String text) {
        Matcher numeric = NUMERIC.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (numeric.find()) {
            int code = Integer.parseInt(numeric.group(2), numeric.group(1).isEmpty() ? 10 : 16);
            numeric.appendReplacement(decoded, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        numeric.appendTail(decoded);
        return decoded.toString().replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&ndash;", "–").replace("&mdash;", "—")
                .replace("&rsquo;", "’").replace("&lsquo;", "‘").replace("&ldquo;", "“")
                .replace("&rdquo;", "”").replace("&hellip;", "…").replace("&amp;", "&");
    }
}
