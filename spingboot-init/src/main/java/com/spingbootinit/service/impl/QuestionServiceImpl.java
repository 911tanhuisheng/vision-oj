package com.spingbootinit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.exception.ThrowUtils;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.context.UserContext.IsAdminRoleString;
import com.spingbootinit.mapper.QuestionMapper;
import com.spingbootinit.model.dto.question.*;
import com.spingbootinit.model.entity.Question;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.model.vo.questionvo.QuestionAdminListItemVO;
import com.spingbootinit.model.vo.questionvo.QuestionPublicDetailVO;
import com.spingbootinit.service.QuestionService;
import com.spingbootinit.service.UserService;
import com.spingbootinit.utils.MinioUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements QuestionService {

    private static final String TYPE_TEXT = "TEXT";
    private static final String TYPE_IMAGE_OBJECT_COUNT = "IMAGE_OBJECT_COUNT";
    private static final Set<String> IMAGE_TYPES = Set.of(
            TYPE_IMAGE_OBJECT_COUNT, "IMAGE_CLASSIFICATION", "IMAGE_OBJECT_DETECTION", "IMAGE_OCR", "IMAGE_ANALYSIS");
    private static final Set<String> VISION_MODEL_KEYS = Set.of(
            "YOLO_GENERAL", "YOLO_HELMET", "EASYOCR_ZH_EN", "OPENCV_ANALYSIS");

    @Resource
    private UserService userService;

    /**
     * 判断是否是管理员
     */
    @Resource
    private IsAdminRoleString isAdminRole;
    @Resource
    private MinioUtils minioUtils;

    /**
     * 校验题目的参数
     * @param question
     * @return
     */
    @Override
    public void validQuestion(Question question, boolean add) {
        if (question == null) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "题目信息不能为空");
        }
        String title = StringUtils.trimToEmpty(question.getTitle());
        String content = StringUtils.trimToEmpty(question.getContent());
        String answer = StringUtils.trimToEmpty(question.getAnswer());
        List<JudgeCase> judgeCases = question.getJudgeCase();
        JudgeConfig judgeConfig = question.getJudgeConfig();
        String questionType = StringUtils.defaultIfBlank(question.getQuestionType(), TYPE_TEXT).trim().toUpperCase(Locale.ROOT);
        question.setQuestionType(questionType);
        if (!TYPE_TEXT.equals(questionType) && !IMAGE_TYPES.contains(questionType)) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "不支持的题目类型");
        }
        if (IMAGE_TYPES.contains(questionType)) {
            if (StringUtils.isBlank(question.getImageUrl())) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "图像类题目必须配置题图 URL");
            }
            int tolerance = question.getCountTolerance() == null ? 0 : question.getCountTolerance();
            if (tolerance < 0 || tolerance > 100) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "计数允许误差必须在 0~100 之间");
            }
            question.setCountTolerance(tolerance);
            String modelKey = StringUtils.defaultIfBlank(question.getVisionModelKey(), "YOLO_GENERAL")
                    .trim().toUpperCase(Locale.ROOT);
            if ("IMAGE_OCR".equals(questionType)) modelKey = "EASYOCR_ZH_EN";
            if ("IMAGE_ANALYSIS".equals(questionType)) modelKey = "OPENCV_ANALYSIS";
            if (!VISION_MODEL_KEYS.contains(modelKey)) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "不支持的图像识别模型");
            }
            if (Set.of(TYPE_IMAGE_OBJECT_COUNT, "IMAGE_CLASSIFICATION", "IMAGE_OBJECT_DETECTION").contains(questionType)
                    && !Set.of("YOLO_GENERAL", "YOLO_HELMET").contains(modelKey)) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "目标检测类题目只能使用 YOLO 模型");
            }
            question.setVisionModelKey(modelKey);
            String generationSource = StringUtils.defaultIfBlank(question.getGenerationSource(), "UPLOAD");
            if (!Set.of("UPLOAD", "LOCAL_COMFYUI", "DASHSCOPE").contains(generationSource)) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "不支持的题图来源");
            }
            question.setGenerationSource(generationSource);
            if ("UPLOAD".equals(generationSource)) question.setGenerationModel(null);
            else if (StringUtils.isBlank(question.getGenerationModel()) || question.getGenerationModel().length() > 128) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "请填写有效的生图模型");
            }
        } else {
            question.setCountTolerance(0);
            question.setVisionModelKey(null);
            question.setGenerationSource(null);
            question.setGenerationModel(null);
        }

        if (StringUtils.isBlank(title) || title.length() > 255) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "标题不能为空且不能超过255个字符");
        }
        if (content.length() < 20) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "题面内容过短，至少20个字符");
        }
        if (answer.length() < 2) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "答案内容过短");
        }

        if (judgeCases == null || judgeCases.isEmpty()) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "判题用例不能为空");
        }
        for (int i = 0; i < judgeCases.size(); i++) {
            JudgeCase c = judgeCases.get(i);
            if (c == null || StringUtils.isBlank(c.getInput()) || StringUtils.isBlank(c.getOutput())) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "第 " + (i + 1) + " 组判题用例不完整");
            }
            if (TYPE_IMAGE_OBJECT_COUNT.equals(questionType)) {
                validateObjectCountJson(c.getOutput(), i + 1);
            } else if ("IMAGE_OBJECT_DETECTION".equals(questionType)) {
                validateDetectionJson(c.getOutput(), i + 1);
            } else if ("IMAGE_ANALYSIS".equals(questionType)) {
                validateAnalysisJson(c.getOutput(), i + 1);
            }
        }
        if (IMAGE_TYPES.contains(questionType)) {
            long uniqueImages = judgeCases.stream().map(JudgeCase::getInput).filter(Objects::nonNull)
                    .map(String::trim).distinct().count();
            long publicCases = judgeCases.stream().filter(c -> Boolean.TRUE.equals(c.getSample())).count();
            long hiddenCases = judgeCases.size() - publicCases;
            if (uniqueImages < 2 || publicCases < 1 || hiddenCases < 1) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(),
                        "图像题至少需要 1 个公开样例和 1 个不同图片的隐藏用例");
            }
            for (JudgeCase c : judgeCases) {
                if (!minioUtils.isTrustedObjectUrl(c.getInput())) {
                    throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "图像题测试图片必须保存到本系统 MinIO");
                }
            }
            if (!minioUtils.isTrustedObjectUrl(question.getImageUrl())) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "题图必须保存到本系统 MinIO");
            }
        }

        if (judgeConfig == null
                || judgeConfig.getTimeLimit() == null
                || judgeConfig.getMemoryLimit() == null
                || judgeConfig.getStackLimit() == null) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "判题配置不完整");
        }
        Long timeLimit = judgeConfig.getTimeLimit();
        Long memoryLimit = judgeConfig.getMemoryLimit();
        Long stackLimit = judgeConfig.getStackLimit();
        long maxTime = IMAGE_TYPES.contains(questionType) ? 60_000L : 10_000L;
        long maxMemory = IMAGE_TYPES.contains(questionType) ? 1_572_864L : 1_048_576L;
        if (timeLimit <= 0 || timeLimit > maxTime) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "时间限制必须在 1~" + maxTime + " ms 之间");
        }
        if (memoryLimit <= 0 || memoryLimit > maxMemory) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "内存限制必须在 1~" + maxMemory + " KB 之间");
        }
        if (stackLimit <= 0 || stackLimit > 262144) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "栈限制必须在 1~262144 KB 之间");
        }

        if (add) {
            LambdaQueryWrapper<Question> qw = new LambdaQueryWrapper<>();
            qw.eq(Question::getTitle, title);
            long sameTitleCount = this.count(qw);
            if (sameTitleCount > 0) {
                throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "同名题目已存在，请更换标题");
            }
        }
    }

    private void validateObjectCountJson(String raw, int caseNo) {
        if (!com.spingbootinit.judo.strategy.ObjectCountJudgeStrategy.outputsMatch(raw, raw, 0)) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(),
                    "第 " + caseNo + " 组标准输出必须是目标计数 JSON，例如 {\"car\":3,\"person\":2}");
        }
    }

    private void validateDetectionJson(String raw, int caseNo) {
        try {
            cn.hutool.json.JSONArray array = cn.hutool.json.JSONUtil.parseArray(raw);
            for (Object item : array) {
                cn.hutool.json.JSONObject box = cn.hutool.json.JSONUtil.parseObj(item);
                if (StringUtils.isBlank(box.getStr("label")) || box.get("x1") == null || box.get("y1") == null
                        || box.get("x2") == null || box.get("y2") == null) throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(),
                    "第 " + caseNo + " 组检测标准输出必须是包含 label/x1/y1/x2/y2 的 JSON 数组");
        }
    }

    private void validateAnalysisJson(String raw, int caseNo) {
        try {
            if (cn.hutool.json.JSONUtil.parseObj(raw).isEmpty()) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAMS_ERROR.getCode(), "第 " + caseNo + " 组图像属性标准输出必须是 JSON 对象");
        }
    }

    /**
     * 创建题目
     * @param questionAddRequest
     * @param currentUserId
     * @return
     */
    @Override
    public long createQuestion(QuestionAddRequest questionAddRequest, Long currentUserId) {
        if (questionAddRequest == null) {
            throw new BusinessException(ResultCode.PARAMS_ERROR);
        }
        User currentUser = userService.getById(currentUserId);
        if (currentUser == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN_ERROR);
        }
        if (!isAdminRole.isAdminRoleString(currentUser.getUserRole())) {
            throw new BusinessException(ResultCode.NO_AUTH_ERROR);
        }
        Question question = new Question();

        BeanUtils.copyProperties(questionAddRequest, question);
        this.validQuestion(question, true);
        question.setUserId(currentUserId);
        question.setFavourNum(0);
        question.setThumbNum(0);
        boolean result = this.save(question);
        ThrowUtils.throwIf(!result, ResultCode.OPERATION_ERROR);
        long newQuestionId = question.getId();
        return newQuestionId;
    }


    /**
     * 分页查询所有题目
     * @param queryRequest
     * @return
     */
    @Override
    public Page<QuestionAdminListItemVO> pageSelect(QuestionQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new QuestionQueryRequest();
        }
        long current = queryRequest.getCurrent() <= 0 ? 1 : queryRequest.getCurrent();
        long pageSize = queryRequest.getPageSize() <= 0 ? 10 : Math.min(queryRequest.getPageSize(), 50);

        LambdaQueryWrapper<Question> qw = new LambdaQueryWrapper<>();
        if (queryRequest.getUserNickname() != null && !queryRequest.getUserNickname().isBlank()) {
            String nicknameKeyword = queryRequest.getUserNickname().trim();
            List<Long> matchedUserIds = userService.lambdaQuery()
                    .select(User::getId)
                    .and(wrapper -> wrapper.like(User::getNickname, nicknameKeyword)
                            .or()
                            .like(User::getUsername, nicknameKeyword))
                    .list()
                    .stream()
                    .map(User::getId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (matchedUserIds.isEmpty()) {
                return new Page<>(current, pageSize, 0);
            }
            qw.in(Question::getUserId, matchedUserIds);
        }
        if (queryRequest.getId() != null && queryRequest.getId() > 0) {
            qw.eq(Question::getId, queryRequest.getId());
        }
        if (queryRequest.getUserId() != null) {
            qw.eq(Question::getUserId, queryRequest.getUserId());
        }
        if (queryRequest.getTitle() != null && !queryRequest.getTitle().isBlank()) {
            qw.like(Question::getTitle, queryRequest.getTitle().trim());
        }
        if (queryRequest.getQuestionType() != null && !queryRequest.getQuestionType().isBlank()) {
            qw.eq(Question::getQuestionType, queryRequest.getQuestionType().trim().toUpperCase(Locale.ROOT));
        }
        if (queryRequest.getContent() != null && !queryRequest.getContent().isBlank()) {
            qw.like(Question::getContent, queryRequest.getContent().trim());
        }
        if (queryRequest.getTags() != null && !queryRequest.getTags().isEmpty()) {
            queryRequest.getTags().stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::trim)
                    .forEach(tag -> qw.apply("JSON_CONTAINS(tags, {0})", "\"" + tag + "\""));
        }
        qw.orderByDesc(Question::getCreateTime);
        Page<Question> page = this.page(new Page<>(current, pageSize), qw);
        List<Question> records = page.getRecords();
        List<Long> userIds = records.stream()
                .map(Question::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> nicknameMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(userIds).stream().collect(Collectors.toMap(
                User::getId,
                u -> (u.getNickname() != null && !u.getNickname().isBlank()) ? u.getNickname() : u.getUsername(),
                (a, b) -> a
        ));

        List<QuestionAdminListItemVO> voRecords = records.stream().map(question -> {
            QuestionAdminListItemVO vo = new QuestionAdminListItemVO();
            BeanUtils.copyProperties(question, vo);
            vo.setUserNickname(nicknameMap.getOrDefault(question.getUserId(), "-"));
            return vo;
        }).toList();

        Page<QuestionAdminListItemVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(voRecords);
        return voPage;
    }

    /**
     * 分页查询所有题目（公开）
     * @param queryRequest
     * @return
     */
    @Override
    public Page<QuestionAdminListItemVO> pageSelectPublic(QuestionQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new QuestionQueryRequest();
        }
        long current = queryRequest.getCurrent() <= 0 ? 1 : queryRequest.getCurrent();
        long pageSize = queryRequest.getPageSize() <= 0 ? 10 : Math.min(queryRequest.getPageSize(), 50);

        LambdaQueryWrapper<Question> qw = new LambdaQueryWrapper<>();
        if (queryRequest.getTitle() != null && !queryRequest.getTitle().isBlank()) {
            qw.like(Question::getTitle, queryRequest.getTitle().trim());
        }
        if (queryRequest.getQuestionType() != null && !queryRequest.getQuestionType().isBlank()) {
            qw.eq(Question::getQuestionType, queryRequest.getQuestionType().trim().toUpperCase(Locale.ROOT));
        }
        if (queryRequest.getTags() != null && !queryRequest.getTags().isEmpty()) {
            queryRequest.getTags().stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::trim)
                    .forEach(tag -> qw.apply("JSON_CONTAINS(tags, {0})", "\"" + tag + "\""));
        }
        qw.orderByDesc(Question::getCreateTime);
        Page<Question> page = this.page(new Page<>(current, pageSize), qw);
        List<Question> records = page.getRecords();

        List<Long> userIds = records.stream()
                .map(Question::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> nicknameMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(userIds).stream().collect(Collectors.toMap(
                User::getId,
                u -> (u.getNickname() != null && !u.getNickname().isBlank()) ? u.getNickname() : u.getUsername(),
                (a, b) -> a
        ));

        List<QuestionAdminListItemVO> voRecords = records.stream().map(question -> {
            QuestionAdminListItemVO vo = new QuestionAdminListItemVO();
            BeanUtils.copyProperties(question, vo);
            // 公共题库列表绝不能泄露参考答案和完整（含隐藏）判题用例。
            vo.setAnswer(null);
            vo.setJudgeCase(null);
            vo.setUserNickname(nicknameMap.getOrDefault(question.getUserId(), "-"));
            return vo;
        }).toList();

        Page<QuestionAdminListItemVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(voRecords);
        return voPage;
    }

    /**
     * 获取题目详情（公开）
     * @param id
     * @return
     */
    @Override
    public QuestionPublicDetailVO getByIdPublicDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.PARAMS_ERROR);
        }
        Question question = this.getById(id);
        if (question == null) {
            throw new BusinessException(ResultCode.NOT_FOUND_ERROR);
        }
        QuestionPublicDetailVO vo = new QuestionPublicDetailVO();
        BeanUtils.copyProperties(question, vo);
        List<JudgeCase> judgeCases = question.getJudgeCase();
        if (judgeCases == null || judgeCases.isEmpty()) {
            vo.setSampleJudgeCase(Collections.emptyList());
        } else {
            boolean imageQuestion = IMAGE_TYPES.contains(StringUtils.upperCase(question.getQuestionType()));
            List<JudgeCase> explicitSamples = judgeCases.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getSample())).toList();
            if (imageQuestion) {
                // 兼容旧数据：没有 sample 标记时仅公开第一组，绝不返回隐藏用例。
                vo.setSampleJudgeCase(explicitSamples.isEmpty() ? judgeCases.stream().limit(1).toList() : explicitSamples);
            } else {
                vo.setSampleJudgeCase(explicitSamples.isEmpty() ? judgeCases.stream().limit(3).toList() : explicitSamples);
            }
        }
        User owner = userService.getById(question.getUserId());
        if (owner != null) {
            String nickname = (owner.getNickname() != null && !owner.getNickname().isBlank()) ? owner.getNickname() : owner.getUsername();
            vo.setUserNickname(nickname);
        } else {
            vo.setUserNickname("-");
        }
        return vo;
    }

    /**
     * 更新题目
     */
    @Override
    public void updateByIdQuestion(QuestionUpdateRequest  request) {
        Question oldQuestion = this.getById(request.getId());
        if (oldQuestion == null) {
            throw new BusinessException(ResultCode.NOT_FOUND_ERROR);
        }
        // 仅管理员可更新
        Question question = new Question();
        BeanUtils.copyProperties(request, question);
        this.validQuestion(question, false);
        boolean updated = this.updateById(question);
        ThrowUtils.throwIf(!updated, ResultCode.OPERATION_ERROR);
    }

    /**
     * 删除题目
     */
    @Override
    public void removeBatchByIdsQuestion(QuestionBatchDeleteRequest request) {
        // 仅管理员可删除
        Set<Long> ids = request.getIds().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            throw new BusinessException(ResultCode.PARAMS_ERROR);
        }
        boolean deleted = this.removeByIds(ids);
        ThrowUtils.throwIf(!deleted, ResultCode.OPERATION_ERROR);
    }


}
