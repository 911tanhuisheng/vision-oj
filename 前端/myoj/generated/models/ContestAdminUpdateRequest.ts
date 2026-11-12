/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ContestQuestionUpsertItem } from './ContestQuestionUpsertItem';
export type ContestAdminUpdateRequest = {
    id: string;
    title: string;
    description?: string;
    startTime: string;
    endTime: string;
    questions?: Array<ContestQuestionUpsertItem>;
};

