package com.spingbootinit.service;

import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.context.UserContext.UserContext;
import com.spingbootinit.judo.ImageJudgeInputService;
import com.spingbootinit.judo.codesandbox.CodeSandboxFactory;
import com.spingbootinit.judo.codesandbox.CodeSandboxProxy;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeRequest;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeResponse;
import com.spingbootinit.judo.strategy.ObjectCountJudgeStrategy;
import com.spingbootinit.judo.strategy.ImageOutputJudgeStrategy;
import com.spingbootinit.model.dto.question.JudgeCase;
import com.spingbootinit.model.dto.question.QuestionAddRequest;
import com.spingbootinit.model.entity.Question;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 教师保存前的验题：检查数据，并在正式沙箱运行各类图像题参考代码。 */
@Service
@RequiredArgsConstructor
public class QuestionPreflightService {
    private static final Pattern PYTHON_BLOCK = Pattern.compile("```python\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private final QuestionService questionService;
    private final ImageJudgeInputService imageJudgeInputService;
    private final CodeSandboxFactory codeSandboxFactory;

    @Value("${codesandbox.type:remote}")
    private String sandboxType;

    public record Check(String name, boolean passed, String detail) {}
    public record PreflightResult(boolean ready, List<Check> checks) {}

    public PreflightResult check(QuestionAddRequest request) {
        List<Check> checks = new ArrayList<>();
        Question question = new Question();
        BeanUtils.copyProperties(request, question);
        try {
            questionService.validQuestion(question, false);
            checks.add(new Check("题目与用例格式", true, "题面、样例、隐藏用例和判题配置完整"));
        } catch (BusinessException e) {
            checks.add(new Check("题目与用例格式", false, e.getMessage()));
            return new PreflightResult(false, checks);
        }
        if (!question.getQuestionType().startsWith("IMAGE_")) {
            checks.add(new Check("参考程序", true, "传统文本题完成结构检查"));
            return new PreflightResult(true, checks);
        }
        List<JudgeCase> cases = question.getJudgeCase();
        if ("IMAGE_OBJECT_COUNT".equals(question.getQuestionType())) {
            boolean diverse = hasDifferentCountAnswer(cases, question.getCountTolerance() == null ? 0 : question.getCountTolerance());
            checks.add(new Check("隐藏用例区分度", diverse, diverse
                    ? "至少一组隐藏图片的答案与公开样例不同，固定输出无法通过全部用例"
                    : "所有图片的计数答案相同，固定输出也可能通过；请加入零目标或多目标图片"));
        }
        String code = pythonCode(question.getAnswer());
        if (code == null) {
            checks.add(new Check("参考程序", false, "请在参考答案中提供 ```python 代码块，供发布前实际运行"));
            return new PreflightResult(false, checks);
        }
        try {
            List<String> inputs = cases.stream().map(JudgeCase::getInput).toList();
            List<String> images = imageJudgeInputService.prepare(question, inputs, "python");
            ExecuteCodeResponse response = new CodeSandboxProxy(codeSandboxFactory.newInstance(sandboxType))
                    .executeCode(ExecuteCodeRequest.builder().code(code).language("python")
                            .userId(UserContext.getCurrentUserId())
                            .inputList(inputs).imageBase64List(images).build());
            List<String> outputs = response.getOutputList();
            if ((response.getCode() != null && response.getCode() != 0) || outputs == null || outputs.size() != cases.size()) {
                String reason = response.getMessage() == null ? "沙箱未返回完整结果" : response.getMessage();
                checks.add(new Check("参考程序实跑", false, "沙箱运行失败：" + reason));
            } else {
                int mismatch = -1;
                for (int i = 0; i < cases.size(); i++) {
                    if (!matchesReference(question.getQuestionType(), cases.get(i).getOutput(), outputs.get(i),
                            question.getCountTolerance() == null ? 0 : question.getCountTolerance())) {
                        mismatch = i + 1;
                        break;
                    }
                }
                checks.add(new Check("参考程序实跑", mismatch < 0,
                        mismatch < 0 ? "参考程序已在判题沙箱通过全部 " + cases.size() + " 组用例"
                                : "参考程序第 " + mismatch + " 组输出与标准答案不一致，请复核图片与标注"));
            }
        } catch (Exception e) {
            checks.add(new Check("参考程序实跑", false, "判题沙箱不可用或参考程序执行失败：" + safeReason(e)));
        }
        return new PreflightResult(checks.stream().allMatch(Check::passed), checks);
    }

    static boolean matchesReference(String type, String expected, String actual, int tolerance) {
        return "IMAGE_OBJECT_COUNT".equals(type)
                ? ObjectCountJudgeStrategy.outputsMatch(expected, actual, tolerance)
                : ImageOutputJudgeStrategy.outputsMatch(type, expected, actual, tolerance);
    }

    static boolean hasDifferentCountAnswer(List<JudgeCase> cases) {
        return hasDifferentCountAnswer(cases, 0);
    }

    static boolean hasDifferentCountAnswer(List<JudgeCase> cases, int tolerance) {
        if (cases == null || cases.size() < 2) return false;
        if (cases.stream().noneMatch(c -> Boolean.TRUE.equals(c.getSample()))
                || cases.stream().noneMatch(c -> !Boolean.TRUE.equals(c.getSample()))) return false;
        return ObjectCountJudgeStrategy.hasNoSharedCountOutput(
                cases.stream().map(JudgeCase::getOutput).toList(), tolerance);
    }

    static String pythonCode(String answer) {
        if (answer == null) return null;
        Matcher matcher = PYTHON_BLOCK.matcher(answer);
        if (!matcher.find()) return null;
        String code = matcher.group(1).trim();
        return code.isBlank() ? null : code;
    }

    private static String safeReason(Exception e) {
        if (e instanceof BusinessException && e.getMessage() != null) return e.getMessage();
        return e.getClass().getSimpleName();
    }
}
