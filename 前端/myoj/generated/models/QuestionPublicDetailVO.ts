/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { JudgeCase } from './JudgeCase';
import type { JudgeConfig } from './JudgeConfig';
export type QuestionPublicDetailVO = {
    id?: string;
    title?: string;
    content?: string;
    questionType?: string;
    imageUrl?: string;
    visionModelKey?: string;
    countTolerance?: number;
    tags?: Array<string>;
    submitNum?: number;
    acceptedNum?: number;
    judgeConfig?: JudgeConfig;
    sampleJudgeCase?: Array<JudgeCase>;
    userId?: string;
    userNickname?: string;
    createTime?: string;
    updateTime?: string;
};

