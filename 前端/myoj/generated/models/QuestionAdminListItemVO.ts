/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { JudgeCase } from './JudgeCase';
import type { JudgeConfig } from './JudgeConfig';
export type QuestionAdminListItemVO = {
    id?: string;
    title?: string;
    content?: string;
    questionType?: string;
    imageUrl?: string;
    visionModelKey?: string;
    countTolerance?: number;
    tags?: Array<string>;
    answer?: string;
    submitNum?: number;
    acceptedNum?: number;
    judgeCase?: Array<JudgeCase>;
    judgeConfig?: JudgeConfig;
    thumbNum?: number;
    favourNum?: number;
    userId?: string;
    userNickname?: string;
    createTime?: string;
    updateTime?: string;
};

