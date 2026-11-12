package com.spingbootinit.service.impl;

import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.context.UserContext.UserContext;
import com.spingbootinit.judo.codesandbox.CodeSandbox;
import com.spingbootinit.judo.ImageJudgeInputService;
import com.spingbootinit.judo.codesandbox.CodeSandboxFactory;
import com.spingbootinit.judo.codesandbox.CodeSandboxProxy;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeRequest;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeResponse;
import com.spingbootinit.judo.strategy.ObjectCountJudgeStrategy;
import com.spingbootinit.judo.strategy.ImageOutputJudgeStrategy;
import com.spingbootinit.model.dto.question.JudgeCase;
import com.spingbootinit.model.dto.question.QuestionRunRequest;
import com.spingbootinit.model.dto.questionsubmit.JudgeInfo;
import com.spingbootinit.model.entity.Question;
import com.spingbootinit.model.enums.JudgeInfoMessageEnum;
import com.spingbootinit.model.vo.questionvo.QuestionRunCaseVO;
import com.spingbootinit.model.vo.questionvo.QuestionRunVO;
import com.spingbootinit.service.QuestionRunService;
import com.spingbootinit.service.QuestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 调用与正式判题相同的沙箱链路，仅用题目用例前若干条作为「运行」展示（与 LeetCode Run 类似）
 */
@Service
@Slf4j
public class QuestionRunServiceImpl implements QuestionRunService {

    private static final int SAMPLE_LIMIT = 3;

    /** Java：类似 System.out.println(3) 或 println("3")，不含读入 */
    private static final Pattern JAVA_LITERAL_PRINT = Pattern.compile(
            "System\\.out\\.print(?:ln)?\\s*\\(\\s*(?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[0-9]+(?:\\.[0-9]+)?)\\s*\\)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern CPP_LITERAL_OUT = Pattern.compile(
            "cout\\s*<<\\s*(?:\"[^\"]*\"|'[^']*'|[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PY_LITERAL_PRINT = Pattern.compile(
            "print\\s*\\(\\s*(?:\"[^\"]*\"|'[^']*'|[0-9]+(?:\\.[0-9]+)?)\\s*\\)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final List<String> SANDBOX_TERMINAL_MESSAGES = List.of(
            JudgeInfoMessageEnum.COMPILE_ERROR.getValue(),
            JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(),
            JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue(),
            JudgeInfoMessageEnum.RUNTIME_ERROR.getValue(),
            JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED.getValue(),
            JudgeInfoMessageEnum.DANGEROUS_OPERATION.getValue(),
            JudgeInfoMessageEnum.SYSTEM_ERROR.getValue()
    );

    @Resource
    private QuestionService questionService;

    @Resource
    private CodeSandboxFactory codeSandboxFactory;

    @Resource
    private ImageJudgeInputService imageJudgeInputService;

    @Value("${codesandbox.type:example}")
    private String defaultJudgeStrategy;

    @Override
    public QuestionRunVO runSample(QuestionRunRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.PARAMS_ERROR);
        }
        String qidRaw = StringUtils.trimToEmpty(request.getQuestionId());
        String language = StringUtils.trimToEmpty(request.getLanguage());
        String code = StringUtils.trimToEmpty(request.getCode());
        if (StringUtils.isBlank(qidRaw) || StringUtils.isBlank(language) || StringUtils.isBlank(code)) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "请完善题目、语言和代码");
        }
        final long questionId;
        try {
            questionId = Long.parseLong(qidRaw);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "题目 id 格式无效");
        }
        if (questionId <= 0) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "题目 id 无效");
        }

        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND_ERROR.getCode(), "题目不存在");
        }

        List<JudgeCase> all = question.getJudgeCase();
        if (all == null || all.isEmpty()) {
            throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "题目暂无测试用例，无法运行");
        }

        List<JudgeCase> markedSamples = all.stream().filter(c -> Boolean.TRUE.equals(c.getSample())).toList();
        // 运行按钮只能使用公开样例，绝不能把隐藏图片、标准答案通过运行结果泄露给学生。
        List<JudgeCase> source = markedSamples.isEmpty() ? all.subList(0, 1) : markedSamples;
        int n = Math.min(SAMPLE_LIMIT, source.size());
        List<JudgeCase> samples = new ArrayList<>(source.subList(0, n));
        List<String> inputList = samples.stream().map(JudgeCase::getInput).toList();
        List<String> imageBase64List = imageJudgeInputService.prepare(question, inputList, language);

        ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                .code(code)
                .language(language)
                .userId(UserContext.getCurrentUserId())
                .inputList(inputList)
                .imageBase64List(imageBase64List)
                .build();

        CodeSandbox codeSandbox = codeSandboxFactory.newInstance(defaultJudgeStrategy);
        codeSandbox = new CodeSandboxProxy(codeSandbox);

        log.info("[运行代码] questionId={}, language={}, 样例数={}", questionId, language, inputList.size());
        ExecuteCodeResponse res = codeSandbox.executeCode(executeCodeRequest);

        JudgeInfo ji = res.getJudgeInfo();
        boolean terminal = ji != null && SANDBOX_TERMINAL_MESSAGES.contains(ji.getMessage());
        if (!terminal && res.getCode() != null && res.getCode() != 0) {
            terminal = true;
        }

        if (terminal) {
            return QuestionRunVO.builder()
                    .sandboxCode(res.getCode())
                    .sandboxMessage(res.getMessage())
                    .judgeInfo(res.getJudgeInfo())
                    .terminalError(true)
                    .cases(List.of())
                    .allSamplePassed(false)
                    .hints(List.of())
                    .suspectedShortcut(false)
                    .build();
        }

        List<String> outputs = res.getOutputList();
        if (outputs == null) {
            outputs = List.of();
        }

        String warning = null;
        if (outputs.size() != samples.size()) {
            log.warn("[运行代码] questionId={} 样例组数={} 与沙箱 outputList 条数={} 不一致", questionId, samples.size(), outputs.size());
            warning = String.format(
                    "沙箱返回 %d 条输出，当前使用了 %d 组样例输入；条数不一致时「你的输出」可能错位，请检查沙箱是否按「每组 stdin 对应一行/一条输出」返回。",
                    outputs.size(),
                    samples.size());
        }

        List<QuestionRunCaseVO> cases = new ArrayList<>();
        boolean allPass = true;
        for (int i = 0; i < samples.size(); i++) {
            JudgeCase jc = samples.get(i);
            String actual = i < outputs.size() && outputs.get(i) != null ? outputs.get(i) : "";
            boolean passed;
            if ("IMAGE_OBJECT_COUNT".equalsIgnoreCase(question.getQuestionType())) {
                int tolerance = question.getCountTolerance() == null ? 0 : question.getCountTolerance();
                passed = ObjectCountJudgeStrategy.outputsMatch(jc.getOutput(), actual, tolerance);
            } else if (question.getQuestionType() != null && question.getQuestionType().toUpperCase().startsWith("IMAGE_")) {
                int tolerance = question.getCountTolerance() == null ? 0 : question.getCountTolerance();
                passed = ImageOutputJudgeStrategy.outputsMatch(question.getQuestionType(), jc.getOutput(), actual, tolerance);
            } else {
                passed = Objects.equals(normalize(jc.getOutput()), normalize(actual));
            }
            if (!passed) {
                allPass = false;
            }
            cases.add(QuestionRunCaseVO.builder()
                    .index(i + 1)
                    .input(jc.getInput())
                    .expectedOutput(jc.getOutput())
                    .actualOutput(actual)
                    .passed(passed)
                    .build());
        }

        boolean suspectedShortcut = false;
        List<String> hints = buildRunHints(warning, allPass, samples);
        if (allPass) {
            boolean literalOnly = looksLikeLiteralStdoutOnly(code, language);
            boolean stdinIgnored = sampleInputsNonEmpty(samples) && likelyNoStdinUsage(code, language);
            suspectedShortcut = literalOnly || stdinIgnored;
            if (literalOnly) {
                hints.add("检测到代码可能只打印常量或未根据输入计算结果。「运行」只比对标准输出文本是否与样例预期一致，不会验证算法是否正确；请以「提交代码」隐藏用例为准。");
            }
            if (stdinIgnored && !literalOnly) {
                hints.add("未检测到有效的标准输入读取（如 Java 的 new Scanner(System.in)、C++ 的 cin、Python 的 input）。若题目需要根据输入求值，请补充读入；否则可能只是输出碰巧与样例一致。");
            }
        }

        return QuestionRunVO.builder()
                .sandboxCode(res.getCode())
                .sandboxMessage(res.getMessage())
                .judgeInfo(res.getJudgeInfo())
                .terminalError(false)
                .cases(cases)
                .allSamplePassed(allPass)
                .warning(warning)
                .hints(hints)
                .suspectedShortcut(suspectedShortcut)
                .build();
    }

    private static List<String> buildRunHints(String warning, boolean allPass, List<JudgeCase> samples) {
        List<String> hints = new ArrayList<>();
        if (StringUtils.isNotBlank(warning)) {
            hints.add(warning);
        }
        if (samples.size() == 1) {
            hints.add("本次运行仅包含题目中的第 1 组样例；即使显示通过，其余隐藏用例仍可能错误，务必点击「提交代码」查看完整评测。");
        } else if (samples.size() >= 2 && allPass) {
            boolean diverseExpected = samples.stream().map(JudgeCase::getOutput).distinct().count() > 1;
            if (!diverseExpected) {
                hints.add("当前展示的若干组样例「预期输出」相同：仅靠运行无法排除「硬编码该输出」的情况；提交后将使用更多用例判定。");
            }
        }
        return hints;
    }

    private static boolean sampleInputsNonEmpty(List<JudgeCase> samples) {
        return samples.stream().anyMatch(j -> StringUtils.isNotBlank(j.getInput()));
    }

    /**
     * 粗略判断代码是否在读标准输入（避免仅 import Scanner 误判为已读入）
     */
    private static boolean likelyNoStdinUsage(String code, String lang) {
        if (StringUtils.isBlank(code)) {
            return true;
        }
        String lc = lang.toLowerCase(Locale.ROOT);
        if (lc.equals("java")) {
            boolean reads = Pattern.compile("new\\s+Scanner\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(code).find()
                    || Pattern.compile("\\.(?:nextInt|nextLong|nextDouble|nextLine|next)\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(code).find()
                    || code.contains("System.in")
                    || Pattern.compile("BufferedReader", Pattern.CASE_INSENSITIVE).matcher(code).find();
            return !reads;
        }
        if (lc.equals("cpp") || lc.contains("c++")) {
            boolean reads = code.contains("cin") || code.contains("scanf") || code.contains("getline");
            return !reads;
        }
        if (lc.equals("python")) {
            boolean reads = Pattern.compile("\\binput\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(code).find()
                    || code.contains("sys.stdin") || code.contains("stdin");
            return !reads;
        }
        return false;
    }

    private static boolean looksLikeLiteralStdoutOnly(String code, String lang) {
        if (StringUtils.isBlank(code)) {
            return false;
        }
        String stripped = stripRoughComments(code);
        String lc = lang.toLowerCase(Locale.ROOT);
        if (lc.equals("java")) {
            return JAVA_LITERAL_PRINT.matcher(stripped).find();
        }
        if (lc.equals("cpp") || lc.contains("c++")) {
            return CPP_LITERAL_OUT.matcher(stripped).find();
        }
        if (lc.equals("python")) {
            return PY_LITERAL_PRINT.matcher(stripped).find();
        }
        return false;
    }

    /** 去掉行注释与块注释，便于匹配 print 常量（粗略即可） */
    private static String stripRoughComments(String code) {
        String s = code.replaceAll("//[^\r\n]*", "");
        s = s.replaceAll("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", "");
        return s;
    }

    /** 与 {@link com.spingbootinit.judo.strategy.JavaLanguageJudgeStrategy} 中判题比对一致 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String normalized = s.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i].replaceAll("\\s+", ""));
        }
        return sb.toString().trim();
    }
}
