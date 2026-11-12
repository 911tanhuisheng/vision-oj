package com.spingbootinit.service;

import com.spingbootinit.model.dto.question.JudgeCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuestionPreflightServiceTest {
    @Test
    void catchesHardcodedAnswerAcrossAllImageCases() {
        assertFalse(QuestionPreflightService.hasDifferentCountAnswer(List.of(
                testCase("{\"bus\":1}", true), testCase("{\"bus\":1}", false))));
        assertTrue(QuestionPreflightService.hasDifferentCountAnswer(List.of(
                testCase("{\"bus\":1}", true), testCase("{\"bus\":2}", false))));
        assertFalse(QuestionPreflightService.hasDifferentCountAnswer(List.of(
                testCase("{\"bus\":1}", true), testCase("{\"bus\":2}", false)), 1));
        assertFalse(QuestionPreflightService.hasDifferentCountAnswer(List.of(
                testCase("{\"bus\":1}", true), testCase("{\"bus\":3}", false)), 1));
        assertTrue(QuestionPreflightService.hasDifferentCountAnswer(List.of(
                testCase("{\"bus\":1}", true), testCase("{\"bus\":4}", false)), 1));
    }

    @Test
    void extractsOnlyFencedPythonReferenceCode() {
        assertEquals("print(1)", QuestionPreflightService.pythonCode("思路\n```python\nprint(1)\n```"));
        assertNull(QuestionPreflightService.pythonCode("只写了思路，没有代码"));
    }

    @Test
    void appliesEachImageJudgeRuleToReferenceProgramOutput() {
        assertTrue(QuestionPreflightService.matchesReference("IMAGE_OBJECT_COUNT", "{\"bus\":2}", "{\"bus\":2}", 0));
        assertTrue(QuestionPreflightService.matchesReference("IMAGE_CLASSIFICATION", "car", "\"CAR\"", 0));
        assertTrue(QuestionPreflightService.matchesReference("IMAGE_OCR", "abcdef", "abcxef", 1));
        assertFalse(QuestionPreflightService.matchesReference("IMAGE_OCR", "abcdef", "abcxef", 0));
        assertTrue(QuestionPreflightService.matchesReference("IMAGE_ANALYSIS", "{\"width\":100}", "{\"width\":101}", 1));
        assertTrue(QuestionPreflightService.matchesReference("IMAGE_OBJECT_DETECTION",
                "[{\"label\":\"car\",\"x1\":0.1,\"y1\":0.1,\"x2\":0.6,\"y2\":0.6}]",
                "[{\"label\":\"car\",\"x1\":0.1,\"y1\":0.1,\"x2\":0.6,\"y2\":0.6}]", 50));
    }

    private JudgeCase testCase(String output, boolean sample) {
        JudgeCase c = new JudgeCase();
        c.setInput("https://example.invalid/image.png");
        c.setOutput(output);
        c.setSample(sample);
        return c;
    }
}
