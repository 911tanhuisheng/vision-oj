package com.spingbootinit.judo.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOutputJudgeStrategyTest {
    @Test
    void classificationIgnoresCaseAndQuotes() {
        assertTrue(ImageOutputJudgeStrategy.outputsMatch("IMAGE_CLASSIFICATION", "car", "\"CAR\"", 0));
        assertFalse(ImageOutputJudgeStrategy.outputsMatch("IMAGE_CLASSIFICATION", "car", "bus", 0));
    }

    @Test
    void ocrUsesNormalizedEditDistance() {
        assertTrue(ImageOutputJudgeStrategy.outputsMatch("IMAGE_OCR", "Hello，世界", "hello 世界!", 0));
        assertTrue(ImageOutputJudgeStrategy.outputsMatch("IMAGE_OCR", "abcdef", "abcxef", 1));
        assertFalse(ImageOutputJudgeStrategy.outputsMatch("IMAGE_OCR", "abcdef", "abxxxx", 1));
    }

    @Test
    void detectionMatchesByLabelAndIou() {
        String expected = "[{\"label\":\"car\",\"x1\":0.1,\"y1\":0.1,\"x2\":0.6,\"y2\":0.6}]";
        String close = "[{\"label\":\"car\",\"x1\":0.12,\"y1\":0.12,\"x2\":0.62,\"y2\":0.62}]";
        assertTrue(ImageOutputJudgeStrategy.outputsMatch("IMAGE_OBJECT_DETECTION", expected, close, 50));
        assertFalse(ImageOutputJudgeStrategy.outputsMatch("IMAGE_OBJECT_DETECTION", expected, close.replace("car", "bus"), 50));
        assertFalse(ImageOutputJudgeStrategy.outputsMatch("IMAGE_OBJECT_DETECTION", expected,
                "[{\"label\":\"car\",\"x1\":-0.1,\"y1\":0.1,\"x2\":0.6,\"y2\":0.6}]", 50));
        assertFalse(ImageOutputJudgeStrategy.outputsMatch("IMAGE_OBJECT_DETECTION", expected,
                "[{\"label\":\"\",\"x1\":0.1,\"y1\":0.1,\"x2\":0.6,\"y2\":0.6}]", 50));
    }

    @Test
    void analysisSupportsNumericTolerance() {
        assertTrue(ImageOutputJudgeStrategy.outputsMatch("IMAGE_ANALYSIS", "{\"width\":100,\"mean_gray\":120.5}", "{\"mean_gray\":121.2,\"width\":100}", 1));
        assertFalse(ImageOutputJudgeStrategy.outputsMatch("IMAGE_ANALYSIS", "{\"width\":100}", "{\"width\":103}", 1));
    }
}
