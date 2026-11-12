package com.spingbootinit.judo.strategy;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.spingbootinit.model.dto.question.JudgeCase;
import com.spingbootinit.model.dto.question.JudgeConfig;
import com.spingbootinit.model.dto.questionsubmit.JudgeInfo;
import com.spingbootinit.model.entity.Question;
import com.spingbootinit.model.enums.JudgeInfoMessageEnum;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import java.util.TreeSet;

/**
 * 图像目标计数题判题。学生每个用例输出一个 JSON 对象，例如
 * {@code {"car":3,"person":2}}，判题器按类别比较数量并支持允许误差。
 */
public class ObjectCountJudgeStrategy implements JudgeStrategy {

    /** 图像推理的实际沙箱上限，单位分别为 KB 和 ms。 */
    private static final long IMAGE_MEMORY_LIMIT_KB = 1536L * 1024L;
    private static final long IMAGE_TIME_LIMIT_MS = 60_000L;

    @Override
    public JudgeInfo doJudge(JudgeContext context) {
        JudgeInfo result = baseResult(context);
        List<String> actualOutputs = context.getOutputList();
        List<JudgeCase> cases = context.getJudgeCaseList();
        if (actualOutputs == null || cases == null || actualOutputs.size() != cases.size()) {
            return wrong(result, "输出组数与测试用例数不一致");
        }

        int tolerance = context.getQuestion().getCountTolerance() == null
                ? 0 : context.getQuestion().getCountTolerance();
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Integer> expected;
            Map<String, Integer> actual;
            try {
                expected = parseCounts(cases.get(i).getOutput());
                actual = parseCounts(actualOutputs.get(i));
            } catch (IllegalArgumentException e) {
                return wrong(result, "第 " + (i + 1) + " 组输出不是合法的目标计数 JSON：" + e.getMessage());
            }

            Set<String> labels = new TreeSet<>();
            labels.addAll(expected.keySet());
            labels.addAll(actual.keySet());
            for (String label : labels) {
                int expectedCount = expected.getOrDefault(label, 0);
                int actualCount = actual.getOrDefault(label, 0);
                if (Math.abs(expectedCount - actualCount) > tolerance) {
                    return wrong(result, String.format(
                            "第 %d 组类别 %s 数量不匹配：期望 %d，实际 %d，允许误差 %d",
                            i + 1, label, expectedCount, actualCount, tolerance));
                }
            }
        }

        JudgeConfig config = context.getQuestion().getJudgeConfig();
        long configuredMemory = config == null || config.getMemoryLimit() == null ? 0L : config.getMemoryLimit();
        long configuredTime = config == null || config.getTimeLimit() == null ? 0L : config.getTimeLimit();
        long memoryLimit = Math.max(configuredMemory, IMAGE_MEMORY_LIMIT_KB);
        long timeLimit = Math.max(configuredTime, IMAGE_TIME_LIMIT_MS);
        if (result.getMemory() != null && result.getMemory() > memoryLimit) {
            result.setMessage(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue());
            return result;
        }
        if (result.getTime() != null && result.getTime() > timeLimit) {
            result.setMessage(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue());
            return result;
        }
        result.setMessage(JudgeInfoMessageEnum.ACCEPTED.getValue());
        result.setDetail("所有目标类别与数量均在允许误差内");
        return result;
    }

    private static JudgeInfo baseResult(JudgeContext context) {
        if (context == null || context.getQuestion() == null || context.getJudgeInfo() == null) {
            throw new IllegalArgumentException("判题上下文不完整");
        }
        JudgeInfo result = new JudgeInfo();
        result.setMemory(context.getJudgeInfo().getMemory());
        result.setTime(context.getJudgeInfo().getTime());
        return result;
    }

    private static JudgeInfo wrong(JudgeInfo result, String detail) {
        result.setMessage(JudgeInfoMessageEnum.WRONG_ANSWER.getValue());
        result.setDetail(detail);
        return result;
    }

    private static Map<String, Integer> parseCounts(String raw) {
        try {
            JSONObject json = JSONUtil.parseObj(raw == null ? "" : raw.trim());
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String key : json.keySet()) {
                Object value = json.get(key);
                if (key == null || key.isBlank() || !(value instanceof Number number)) {
                    throw new IllegalArgumentException("类别名必须非空，数量必须是非负整数");
                }
                int count;
                try {
                    count = new BigDecimal(number.toString()).intValueExact();
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new IllegalArgumentException("数量必须是非负整数且不能超过整数范围");
                }
                if (count < 0 || counts.putIfAbsent(key.trim().toLowerCase(), count) != null) {
                    throw new IllegalArgumentException("数量必须非负，类别名不能重复");
                }
            }
            return counts;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("期望格式为 {\"car\":3,\"person\":2}");
        }
    }

    /** 供「运行样例」与正式判题共用相同的计数比较语义。 */
    public static boolean outputsMatch(String expectedRaw, String actualRaw, int tolerance) {
        try {
            Map<String, Integer> expected = parseCounts(expectedRaw);
            Map<String, Integer> actual = parseCounts(actualRaw);
            Set<String> labels = new TreeSet<>();
            labels.addAll(expected.keySet());
            labels.addAll(actual.keySet());
            for (String label : labels) {
                if (Math.abs(expected.getOrDefault(label, 0) - actual.getOrDefault(label, 0)) > tolerance) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 任一类别的标准数量范围超过两倍误差时，不存在可通过所有用例的固定输出。 */
    public static boolean hasNoSharedCountOutput(List<String> expectedOutputs, int tolerance) {
        if (expectedOutputs == null || expectedOutputs.size() < 2 || tolerance < 0) return false;
        try {
            List<Map<String, Integer>> counts = new ArrayList<>();
            Set<String> labels = new HashSet<>();
            for (String output : expectedOutputs) {
                Map<String, Integer> parsed = parseCounts(output);
                counts.add(parsed);
                labels.addAll(parsed.keySet());
            }
            for (String label : labels) {
                int min = Integer.MAX_VALUE;
                int max = 0;
                for (Map<String, Integer> count : counts) {
                    int value = count.getOrDefault(label, 0);
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                if ((long) max - min > 2L * tolerance) return true;
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
