package com.spingbootinit.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StudyRecommendationEngineTest {
    @Test
    void recommendsUnsolvedQuestionSharingWeakKnowledgeTag() {
        var questions = List.of(
                new StudyRecommendationEngine.Candidate(1L, "数组入门", "TEXT", List.of("数组", "简单")),
                new StudyRecommendationEngine.Candidate(2L, "数组遍历", "TEXT", List.of("数组", "简单")),
                new StudyRecommendationEngine.Candidate(3L, "图论", "TEXT", List.of("图论", "简单")));
        var attempts = List.of(new StudyRecommendationEngine.Attempt(1L, "Wrong Answer"));

        var result = StudyRecommendationEngine.rank(questions, attempts, 3);

        assertEquals(2L, result.get(0).questionId());
        assertEquals("数组", result.get(0).focusTag());
        assertFalse(result.get(0).reason().isBlank());
    }

    @Test
    void excludesAcceptedQuestionsAndExplainsColdStart() {
        var questions = List.of(
                new StudyRecommendationEngine.Candidate(1L, "已通过", "TEXT", List.of("简单")),
                new StudyRecommendationEngine.Candidate(2L, "入门题", "TEXT", List.of("入门", "数组")));
        var attempts = List.of(new StudyRecommendationEngine.Attempt(1L, "Accepted"));

        var result = StudyRecommendationEngine.rank(questions, attempts, 3);

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).questionId());
        assertFalse(result.get(0).reason().isBlank());
    }
}
