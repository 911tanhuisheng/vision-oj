package com.spingbootinit.judo.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectCountJudgeStrategyTest {
    @Test
    void rejectsFractionalAndOverflowCounts() {
        assertFalse(ObjectCountJudgeStrategy.outputsMatch("{\"bus\":1}", "{\"bus\":1.9}", 0));
        assertFalse(ObjectCountJudgeStrategy.outputsMatch("{\"bus\":1}", "{\"bus\":4294967297}", 0));
    }

    @Test
    void acceptsDifferentJsonOrderAndWhitespace() {
        assertTrue(ObjectCountJudgeStrategy.outputsMatch(
                "{\"car\":3,\"person\":2}",
                "{ \"person\" : 2, \"car\" : 3 }",
                0));
    }

    @Test
    void appliesPerClassTolerance() {
        assertTrue(ObjectCountJudgeStrategy.outputsMatch(
                "{\"car\":3,\"person\":2}",
                "{\"car\":4,\"person\":1}",
                1));
        assertFalse(ObjectCountJudgeStrategy.outputsMatch(
                "{\"car\":3,\"person\":2}",
                "{\"car\":5,\"person\":2}",
                1));
    }

    @Test
    void rejectsMalformedOrUnexpectedLabels() {
        assertFalse(ObjectCountJudgeStrategy.outputsMatch("{\"car\":3}", "not-json", 0));
        assertFalse(ObjectCountJudgeStrategy.outputsMatch("{\"car\":3}", "{\"car\":3,\"dog\":1}", 0));
    }
}
