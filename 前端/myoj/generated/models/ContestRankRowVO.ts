/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ContestRankCellVO } from './ContestRankCellVO';
export type ContestRankRowVO = {
    rankOrder?: number;
    userId?: string;
    userName?: string;
    solvedCount?: number;
    totalPenalty?: number;
    totalScore?: number;
    totalTime?: number;
    totalMemory?: number;
    cells?: Array<ContestRankCellVO>;
};

