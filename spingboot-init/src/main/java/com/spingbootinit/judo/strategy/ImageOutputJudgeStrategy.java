package com.spingbootinit.judo.strategy;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.spingbootinit.model.dto.question.JudgeCase;
import com.spingbootinit.model.dto.question.JudgeConfig;
import com.spingbootinit.model.dto.questionsubmit.JudgeInfo;
import com.spingbootinit.model.enums.JudgeInfoMessageEnum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 多模态图像题判题：分类、检测框、OCR 与图像属性分析。 */
public class ImageOutputJudgeStrategy implements JudgeStrategy {
    public static final String CLASSIFICATION = "IMAGE_CLASSIFICATION";
    public static final String DETECTION = "IMAGE_OBJECT_DETECTION";
    public static final String OCR = "IMAGE_OCR";
    public static final String ANALYSIS = "IMAGE_ANALYSIS";
    private static final long IMAGE_MEMORY_LIMIT_KB = 1536L * 1024L;
    private static final long IMAGE_TIME_LIMIT_MS = 60_000L;

    @Override
    public JudgeInfo doJudge(JudgeContext context) {
        JudgeInfo result = baseResult(context);
        List<String> outputs = context.getOutputList();
        List<JudgeCase> cases = context.getJudgeCaseList();
        if (outputs == null || cases == null || outputs.size() != cases.size()) {
            return wrong(result, "输出组数与测试用例数不一致");
        }
        String type = context.getQuestion().getQuestionType();
        int tolerance = context.getQuestion().getCountTolerance() == null ? 0 : context.getQuestion().getCountTolerance();
        for (int i = 0; i < cases.size(); i++) {
            Match match = match(type, cases.get(i).getOutput(), outputs.get(i), tolerance);
            if (!match.passed()) return wrong(result, "第 " + (i + 1) + " 组：" + match.detail());
        }
        JudgeConfig config = context.getQuestion().getJudgeConfig();
        long memoryLimit = Math.max(config == null || config.getMemoryLimit() == null ? 0 : config.getMemoryLimit(), IMAGE_MEMORY_LIMIT_KB);
        long timeLimit = Math.max(config == null || config.getTimeLimit() == null ? 0 : config.getTimeLimit(), IMAGE_TIME_LIMIT_MS);
        if (result.getMemory() != null && result.getMemory() > memoryLimit) {
            result.setMessage(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue()); return result;
        }
        if (result.getTime() != null && result.getTime() > timeLimit) {
            result.setMessage(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue()); return result;
        }
        result.setMessage(JudgeInfoMessageEnum.ACCEPTED.getValue());
        result.setDetail("所有图像题输出均满足判题规则");
        return result;
    }

    public static boolean outputsMatch(String type, String expected, String actual, int tolerance) {
        return match(type, expected, actual, tolerance).passed();
    }

    private static Match match(String type, String expected, String actual, int tolerance) {
        try {
            if (CLASSIFICATION.equalsIgnoreCase(type)) {
                boolean ok = normalizeText(expected).equals(normalizeText(actual));
                return new Match(ok, ok ? "分类正确" : "分类标签不匹配");
            }
            if (OCR.equalsIgnoreCase(type)) {
                String e = normalizeOcr(expected), a = normalizeOcr(actual);
                int distance = levenshtein(e, a);
                boolean ok = distance <= tolerance;
                return new Match(ok, "OCR 编辑距离 " + distance + "，允许 " + tolerance);
            }
            if (DETECTION.equalsIgnoreCase(type)) return matchDetections(expected, actual, tolerance <= 0 ? 50 : tolerance);
            if (ANALYSIS.equalsIgnoreCase(type)) return matchNumericJson(expected, actual, tolerance);
            return new Match(false, "不支持的图像题型");
        } catch (Exception e) {
            return new Match(false, "输出格式错误：" + e.getMessage());
        }
    }

    private static Match matchDetections(String expectedRaw, String actualRaw, int iouPercent) {
        List<Box> expected = boxes(expectedRaw), actual = boxes(actualRaw);
        boolean[] used = new boolean[actual.size()];
        double threshold = Math.max(0.1, Math.min(0.95, iouPercent / 100.0));
        for (Box e : expected) {
            int best = -1; double bestIou = 0;
            for (int i = 0; i < actual.size(); i++) {
                if (!used[i] && e.label.equals(actual.get(i).label)) {
                    double score = iou(e, actual.get(i));
                    if (score > bestIou) { bestIou = score; best = i; }
                }
            }
            if (best < 0 || bestIou < threshold) return new Match(false, "目标 " + e.label + " 缺失或 IoU 低于 " + iouPercent + "%");
            used[best] = true;
        }
        if (expected.size() != actual.size()) return new Match(false, "检测框数量不一致");
        return new Match(true, "检测框匹配");
    }

    private static Match matchNumericJson(String expectedRaw, String actualRaw, int tolerance) {
        JSONObject e = JSONUtil.parseObj(expectedRaw), a = JSONUtil.parseObj(actualRaw);
        if (!e.keySet().equals(a.keySet())) return new Match(false, "JSON 字段不一致");
        for (String key : e.keySet()) {
            Object ev = e.get(key), av = a.get(key);
            if (ev instanceof Number en && av instanceof Number an) {
                if (Math.abs(en.doubleValue() - an.doubleValue()) > tolerance) return new Match(false, key + " 数值超出允许误差 " + tolerance);
            } else if (!String.valueOf(ev).equalsIgnoreCase(String.valueOf(av))) return new Match(false, key + " 内容不匹配");
        }
        return new Match(true, "图像属性匹配");
    }

    private static List<Box> boxes(String raw) {
        JSONArray array = JSONUtil.parseArray(raw);
        List<Box> boxes = new ArrayList<>();
        for (Object item : array) {
            JSONObject o = JSONUtil.parseObj(item);
            String label = o.getStr("label", "").trim().toLowerCase(Locale.ROOT);
            Double x1 = o.getDouble("x1"), y1 = o.getDouble("y1"), x2 = o.getDouble("x2"), y2 = o.getDouble("y2");
            if (label.isBlank() || x1 == null || y1 == null || x2 == null || y2 == null
                    || !Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(x2) || !Double.isFinite(y2)
                    || x1 < 0 || y1 < 0 || x2 > 1 || y2 > 1 || x1 >= x2 || y1 >= y2) {
                throw new IllegalArgumentException("检测框需要有效类别和 0～1 范围内的 x1< x2、y1< y2 坐标");
            }
            boxes.add(new Box(label, x1, y1, x2, y2));
        }
        return boxes;
    }

    private static double iou(Box a, Box b) {
        double x1 = Math.max(a.x1, b.x1), y1 = Math.max(a.y1, b.y1), x2 = Math.min(a.x2, b.x2), y2 = Math.min(a.y2, b.y2);
        double intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double union = area(a) + area(b) - intersection;
        return union <= 0 ? 0 : intersection / union;
    }
    private static double area(Box b) { return Math.max(0, b.x2 - b.x1) * Math.max(0, b.y2 - b.y1); }
    private static String normalizeText(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("^[\"']|[\"']$", ""); }
    private static String normalizeOcr(String s) { return normalizeText(s).replaceAll("[\\s\\p{P}]", ""); }
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1]; for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) { int[] cur = new int[b.length() + 1]; cur[0] = i; for (int j = 1; j <= b.length(); j++) cur[j] = Math.min(Math.min(cur[j-1]+1, prev[j]+1), prev[j-1] + (a.charAt(i-1)==b.charAt(j-1)?0:1)); prev = cur; }
        return prev[b.length()];
    }
    private static JudgeInfo baseResult(JudgeContext c) { JudgeInfo r = new JudgeInfo(); r.setMemory(c.getJudgeInfo().getMemory()); r.setTime(c.getJudgeInfo().getTime()); return r; }
    private static JudgeInfo wrong(JudgeInfo r, String d) { r.setMessage(JudgeInfoMessageEnum.WRONG_ANSWER.getValue()); r.setDetail(d); return r; }
    private record Match(boolean passed, String detail) {}
    private record Box(String label, double x1, double y1, double x2, double y2) {}
}
