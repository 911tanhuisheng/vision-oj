package com.spingbootinit.service;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只用真实提交记录和题目标签排序；每条推荐都能解释其依据。 */
public final class StudyRecommendationEngine {
    private static final Set<String> DIFFICULTY = Set.of("入门", "简单", "中等", "困难");

    private StudyRecommendationEngine() {}

    public record Candidate(long questionId, String title, String questionType, List<String> tags) {}
    public record Attempt(long questionId, String verdict) {}
    public record Recommendation(@JsonSerialize(using = ToStringSerializer.class) long questionId,
                                 String title, String questionType, String focusTag, String reason) {}

    public static List<Recommendation> rank(List<Candidate> questions, List<Attempt> attempts, int limit) {
        if (questions == null || questions.isEmpty() || limit <= 0) return List.of();
        Map<Long, Candidate> byId = new HashMap<>();
        for (Candidate q : questions) if (q != null) byId.put(q.questionId(), q);
        Set<Long> solved = new HashSet<>();
        Set<Long> tried = new HashSet<>();
        Map<String, Integer> weakTags = new HashMap<>();
        if (attempts != null) {
            for (Attempt attempt : attempts) {
                if (attempt == null) continue;
                tried.add(attempt.questionId());
                if ("Accepted".equalsIgnoreCase(attempt.verdict())) solved.add(attempt.questionId());
            }
            for (Attempt attempt : attempts) {
                if (attempt == null || solved.contains(attempt.questionId())
                        || "Accepted".equalsIgnoreCase(attempt.verdict()) || attempt.verdict() == null
                        || Set.of("Waiting", "Judging", "System Error").contains(attempt.verdict())) continue;
                Candidate question = byId.get(attempt.questionId());
                if (question == null) continue;
                for (String tag : knowledgeTags(question.tags())) weakTags.merge(tag, 1, Integer::sum);
            }
        }
        record Scored(Recommendation recommendation, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (Candidate question : questions) {
            if (question == null || solved.contains(question.questionId())) continue;
            List<String> tags = knowledgeTags(question.tags());
            String focus = tags.stream().max(Comparator.comparingInt(t -> weakTags.getOrDefault(t, 0))).orElse("");
            int weakness = weakTags.getOrDefault(focus, 0);
            int score = Math.min(weakness, 4) * 25 + (tried.contains(question.questionId()) ? 0 : 8);
            if (question.tags() != null && question.tags().stream().anyMatch(t -> "入门".equals(t) || "简单".equals(t))) score += 3;
            String reason = weakness > 0
                    ? "你最近在「" + focus + "」相关题目尚未通过，推荐继续练习这个知识点。"
                    : tried.contains(question.questionId())
                    ? "你尝试过这道题但尚未通过，适合回顾后再挑战。"
                    : "根据当前可练题目推荐；完成提交后会结合你的薄弱知识点调整。";
            scored.add(new Scored(new Recommendation(question.questionId(), question.title(),
                    question.questionType(), focus, reason), score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparingLong(s -> s.recommendation().questionId()));
        return scored.stream().limit(limit).map(Scored::recommendation).toList();
    }

    private static List<String> knowledgeTags(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream().filter(t -> t != null && !t.isBlank() && !DIFFICULTY.contains(t)).distinct().toList();
    }
}
