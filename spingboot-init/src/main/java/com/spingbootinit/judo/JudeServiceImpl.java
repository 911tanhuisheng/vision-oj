package com.spingbootinit.judo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.judo.codesandbox.CodeSandbox;
import com.spingbootinit.judo.codesandbox.CodeSandboxFactory;
import com.spingbootinit.judo.codesandbox.CodeSandboxProxy;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeRequest;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeResponse;
import com.spingbootinit.judo.strategy.JudgeContext;
import com.spingbootinit.model.dto.question.JudgeCase;
import com.spingbootinit.model.dto.questionsubmit.JudgeInfo;
import com.spingbootinit.model.entity.Question;
import com.spingbootinit.model.entity.QuestionSubmit;
import com.spingbootinit.model.enums.JudgeInfoMessageEnum;
import com.spingbootinit.model.enums.QuestionSubmitStatusEnum;
import com.spingbootinit.service.ContestRankService;
import com.spingbootinit.service.QuestionService;
import com.spingbootinit.service.QuestionSubmitService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class JudeServiceImpl implements JudgeService {

    /**
     * 沙箱返回的错误信息
     */
    private static final List<String> SANDBOX_TERMINAL_MESSAGES = List.of(
            JudgeInfoMessageEnum.COMPILE_ERROR.getValue(),// 编译错误
            JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(), // 时间超限
            JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue(), // 内存超限
            JudgeInfoMessageEnum.RUNTIME_ERROR.getValue(), // 运行时错误
            JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED.getValue(), // 输出超限
            JudgeInfoMessageEnum.DANGEROUS_OPERATION.getValue(), // 危险操作
            JudgeInfoMessageEnum.SYSTEM_ERROR.getValue() // 系统错误
    );

    @Resource
    @Lazy
    private QuestionSubmitService questionSubmitService;
    @Resource
    private QuestionService questionService;
    @Value("${codesandbox.type:example}")
    private String defaultJudgeStrategy;
    @Resource
    private JudgeManager judgeManager;
    @Resource
    private CodeSandboxFactory codeSandboxFactory;
    @Resource
    private ImageJudgeInputService imageJudgeInputService;

    @Resource
    @Lazy
    private ContestRankService contestRankService;

    /**
     * 执行判题（支持指定沙箱类型）
     * @param questionSubmitId 题目提交记录ID
     * @return 更新后的题目提交记录
     */
    @Override
    public QuestionSubmit doJudge(long questionSubmitId) {
        // 1. 查询提交记录和题目信息
        QuestionSubmit questionSubmit = questionSubmitService.getById(questionSubmitId);
        if(questionSubmit == null){
            log.error("[执行判题] 提交记录不存在，提交ID：{}", questionSubmitId);
            throw new BusinessException(ResultCode.NOT_FOUND_ERROR.getCode(), "提交记录不存在");
        }

        Long questionId = questionSubmit.getQuestionId();
        Question question = questionService.getById(questionId);
        if(question == null){
            log.error("[执行判题] 题目不存在，问题ID：{}", questionId);
            throw  new BusinessException(ResultCode.NOT_FOUND_ERROR.getCode(), "题目不存在");

        }

        // 2. 乐观锁更新状态： 只有waiting 状态才能被更新为Running , 防止重复判题
        LambdaUpdateWrapper<QuestionSubmit> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(QuestionSubmit::getId, questionSubmitId)
                .eq(QuestionSubmit::getStatus, QuestionSubmitStatusEnum.WAITING.getValue())
                .set(QuestionSubmit::getStatus, QuestionSubmitStatusEnum.RUNNING.getValue());
        boolean update = questionSubmitService.update(updateWrapper);


        if(!update){
            log.warn("判题并发冲突，提交已被其他线程处理，id: {}", questionSubmitId);
            throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "提交已被处理，请勿重复提交");
        }

        // 重新获取更新后的提交记录
        questionSubmit = questionSubmitService.getById(questionSubmitId);


        try {
            // 3. 调用代码沙箱执行
            String language = questionSubmit.getLanguage();
            String code = questionSubmit.getCode();

            // 解析题目的测试用例
            List<JudgeCase> judgeCase = question.getJudgeCase();
            if (judgeCase == null){
                throw new BusinessException(ResultCode.OPERATION_ERROR.getCode(), "题目测试用例为空");
            }
            List<String> inputList = judgeCase.stream().map(JudgeCase::getInput).toList();
            List<String> imageBase64List = imageJudgeInputService.prepare(question, inputList, language);

            // 构建沙箱请求
            ExecuteCodeRequest executeCodeRequest = ExecuteCodeRequest.builder()
                    .code(code)
                    .language(language)
                    .userId(questionSubmit.getUserId())
                    .inputList(inputList)
                    .imageBase64List(imageBase64List)
                    .build();

            // 创建沙箱实例（从 Spring 容器取 Bean，保证 RestTemplate 等依赖已注入）
            CodeSandbox codeSandbox = codeSandboxFactory.newInstance(defaultJudgeStrategy);
            codeSandbox = new CodeSandboxProxy(codeSandbox); // 代理增强（可选，记录调用日志）

            log.info("开始调用代码沙箱，提交id: {}, 语言: {}, 用例数: {}", questionSubmitId, language, inputList.size());

            ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);

            log.info("沙箱调用完成，提交id: {}, 输出数量: {}", questionSubmitId,
                    executeCodeResponse.getOutputList() == null ? 0 : executeCodeResponse.getOutputList().size());

            JudgeInfo sandboxJudgeInfo = executeCodeResponse.getJudgeInfo();

            if (isSandboxTerminalResult(sandboxJudgeInfo)) {
                updateSubmitResult(questionSubmitId, QuestionSubmitStatusEnum.FAILED.getValue(), sandboxJudgeInfo);
                scheduleContestRankAfterCommit(questionSubmitId);
                return questionSubmitService.getById(questionSubmitId);
            }
            if (executeCodeResponse.getCode() != null && executeCodeResponse.getCode() != 0) {
                log.error("沙箱执行失败，提交id: {}, 错误码: {}, 错误信息: {}",
                        questionSubmitId, executeCodeResponse.getCode(), executeCodeResponse.getMessage());
                updateSubmitResult(questionSubmitId, QuestionSubmitStatusEnum.FAILED.getValue(),
                        buildSystemErrorJudgeInfo(executeCodeResponse.getMessage()));
                scheduleContestRankAfterCommit(questionSubmitId);
                return questionSubmitService.getById(questionSubmitId);
            }

            // 5. 判题核心逻辑：比对输出结果，计算得分等
            JudgeContext judgeContext = new JudgeContext();
            judgeContext.setJudgeInfo(sandboxJudgeInfo);
            judgeContext.setInputList(inputList);
            judgeContext.setOutputList(executeCodeResponse.getOutputList());
            judgeContext.setJudgeCaseList(judgeCase);
            judgeContext.setQuestion(question);
            judgeContext.setQuestionSubmit(questionSubmit);

            JudgeInfo finalJudgeInfo = judgeManager.doJudge(judgeContext);

            // 6. 根据判题结果决定最终状态（全部通过 -> SUCCEED，否则 FAILED）
            Integer finalStatus = isAccepted(finalJudgeInfo)
                    ? QuestionSubmitStatusEnum.SUCCEED.getValue()
                    : QuestionSubmitStatusEnum.FAILED.getValue();
            // 更新最终判题结果和状态
            updateSubmitResult(questionSubmitId, finalStatus, finalJudgeInfo);
            scheduleContestRankAfterCommit(questionSubmitId);

            // 7. 如果成功，更新题目的通过数
            if(Objects.equals(finalStatus, QuestionSubmitStatusEnum.SUCCEED.getValue())){
                LambdaUpdateWrapper<Question> updateWrapper1 = new LambdaUpdateWrapper<>();
                updateWrapper1.eq(Question::getId, questionId)
                        .setSql("accepted_num = COALESCE(accepted_num, 0) + 1");
                questionService.update(updateWrapper1);
            }

            log.info("判题完成，提交id: {}, 最终状态: {}, 详细信息: {}", questionSubmitId, finalStatus, finalJudgeInfo);

            // 返回最新的提交记录
            return questionSubmitService.getById(questionSubmitId);
        } catch (Exception e) {
            log.error("判题过程发生异常，提交id: {}", questionSubmitId, e);
            // 判题方法不包裹长事务，失败状态会独立持久化，避免提交永久停留在等待/判题中。
            updateSubmitStatus(questionSubmitId, QuestionSubmitStatusEnum.FAILED.getValue(),
                    "系统异常: " + e.getMessage());
            scheduleContestRankAfterCommit(questionSubmitId);
            return questionSubmitService.getById(questionSubmitId);
        }
    }

    /**
     * 更新提交记录的最终判题结果（包含判题详情）
     */
    private void updateSubmitResult(Long submitId, Integer status, JudgeInfo judgeInfo) {
        QuestionSubmit updateEntity = new QuestionSubmit();
        updateEntity.setId(submitId);
        updateEntity.setStatus(status);

        if (judgeInfo != null) {
            updateEntity.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
        }
        boolean result = questionSubmitService.updateById(updateEntity);
        if (!result) {
            log.error("更新判题结果失败，提交id: {}, 状态: {}", submitId, status);
            throw new BusinessException(ResultCode.SYSTEM_ERROR.getCode(), "更新判题结果失败");
        }
    }

    /**
     * 更新提交记录状态（不更新判题详情）
     */
    private void updateSubmitStatus(long questionSubmitId, Integer status, String errorMessage) {

        QuestionSubmit updateEntity = new QuestionSubmit();
        updateEntity.setId(questionSubmitId);
        updateEntity.setStatus(status);
        // 可选：将错误信息存入 judgeInfo 字段或单独的错误字段
        if (StrUtil.isNotBlank(errorMessage)) {
            JudgeInfo errorInfo = new JudgeInfo();
            errorInfo.setMessage(errorMessage);
            updateEntity.setJudgeInfo(JSONUtil.toJsonStr(errorInfo));
        }
        boolean result = questionSubmitService.updateById(updateEntity);
        if (!result) {
            log.error("更新提交状态失败，提交id: {}, 状态: {}", questionSubmitId, status);
            // 此处不抛异常，避免掩盖原始异常，仅记录日志
        }
    }

    /**
     * 竞赛榜更新在独立事务中读提交表；必须在判题事务提交后再执行，否则读不到最新状态。
     */
    private void scheduleContestRankAfterCommit(long questionSubmitId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifyContestRankSafe(questionSubmitId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyContestRankSafe(questionSubmitId);
            }
        });
    }

    private void notifyContestRankSafe(long questionSubmitId) {
        try {
            contestRankService.onJudgeFinished(questionSubmitId);
        } catch (Exception e) {
            log.warn("竞赛排行榜更新失败（不影响判题结果），submitId={}", questionSubmitId, e);
        }
    }

    private boolean isAccepted(JudgeInfo judgeInfo) {
        return judgeInfo != null && Objects.equals(judgeInfo.getMessage(), JudgeInfoMessageEnum.ACCEPTED.getValue());
    }

    private boolean isSandboxTerminalResult(JudgeInfo judgeInfo) {
        return judgeInfo != null && SANDBOX_TERMINAL_MESSAGES.contains(judgeInfo.getMessage());
    }

    private JudgeInfo buildSystemErrorJudgeInfo(String message) {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
        return judgeInfo;
    }
}
