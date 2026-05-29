package com.deluce.oncall.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从文档内容与文件名中提取可用于展示/检索的关键词。
 */
public final class KeywordExtractor {

    private static final Pattern MARKDOWN_HEADER = Pattern.compile("(?m)^#{1,3}\\s+(.+?)\\s*$");
    private static final Pattern BOLD_TERM = Pattern.compile("\\*\\*([^*\\n]{2,40})\\*\\*");
    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^[-*\\d.)]+\\s+(.{2,40})$");
    private static final int MAX_KEYWORDS = 48;

    private KeywordExtractor() {
    }

    public static List<String> extract(List<Document> chunks, String sourceName) {
        Set<String> keywords = new LinkedHashSet<>();

        if (sourceName != null && !sourceName.isBlank()) {
            keywords.add(sourceName.trim());
        }

        for (Document chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            collectMatches(keywords, MARKDOWN_HEADER, text);
            collectMatches(keywords, BOLD_TERM, text);
            collectMatches(keywords, LIST_ITEM, text);
        }

        List<String> result = new ArrayList<>();
        for (String keyword : keywords) {
            String normalized = normalize(keyword);
            if (normalized != null) {
                result.add(normalized);
            }
            if (result.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return result;
    }

    private static void collectMatches(Set<String> keywords, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            keywords.add(matcher.group(1));
        }
    }

    private static String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String value = keyword
                .replaceAll("[#*`]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (value.length() < 2 || value.length() > 40) {
            return null;
        }
        return value;
    }

    public static String displayNameFromPath(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        String name = fileName;
        int underscore = name.indexOf('_');
        if (underscore > 0 && underscore < name.length() - 1) {
            String prefix = name.substring(0, underscore);
            if (prefix.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                    || prefix.matches("[0-9a-fA-F]{32}")) {
                name = name.substring(underscore + 1);
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.isBlank() ? fileName : name;
    }
}
