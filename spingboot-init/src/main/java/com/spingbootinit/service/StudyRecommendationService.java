package com.spingbootinit.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spingbootinit.model.entity.Question;
import com.spingbootinit.model.entity.QuestionSubmit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 根据本人真实提交和公开题目标签推荐练习，不读取代码或隐藏用例。 */
@Service
@RequiredArgsConstructor
public class StudyRecommendationService {
    private final QuestionService questionService;
    private final QuestionSubmitService questionSubmitService;

    public List<StudyRecommendationEngine.Recommendation> recommend(long userId) {
        List<Question> questions = questionService.list(new LambdaQueryWrapper<Question>()
                .select(Question::getId, Question::getTitle, Question::getQuestionType, Question::getTags)
                .orderByDesc(Question::getCreateTime).last("LIMIT 150"));
        List<QuestionSubmit> submissions = questionSubmitService.list(new LambdaQueryWrapper<QuestionSubmit>()
                .select(QuestionSubmit::getQuestionId, QuestionSubmit::getJudgeInfo)
                .eq(QuestionSubmit::getUserId, userId)
                .orderByDesc(QuestionSubmit::getCreateTime).last("LIMIT 80"));
        List<StudyRecommendationEngine.Candidate> candidates = questions.stream()
                .map(q -> new StudyRecommendationEngine.Candidate(q.getId(), q.getTitle(), q.getQuestionType(), q.getTags()))
                .toList();
        List<StudyRecommendationEngine.Attempt> attempts = submissions.stream()
                .map(s -> new StudyRecommendationEngine.Attempt(s.getQuestionId(), verdict(s.getJudgeInfo())))
                .toList();
        return StudyRecommendationEngine.rank(candidates, attempts, 3);
    }

    private String verdict(String judgeInfo) {
        try {
            return JSONUtil.parseObj(judgeInfo).getStr("message");
        } catch (Exception ignored) {
            return null;
        }
    }
}
