/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ContestJoinRequest } from '../models/ContestJoinRequest';
import type { ResultBoolean } from '../models/ResultBoolean';
import type { ResultContestDetailVO } from '../models/ResultContestDetailVO';
import type { ResultListContestListItemVO } from '../models/ResultListContestListItemVO';
import type { ResultListContestRankRowVO } from '../models/ResultListContestRankRowVO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ContestControllerService {
    /**
     * @param requestBody
     * @returns ResultBoolean OK
     * @throws ApiError
     */
    public static join(
        requestBody: ContestJoinRequest,
    ): CancelablePromise<ResultBoolean> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/contest/join',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * @param contestId
     * @returns ResultListContestRankRowVO OK
     * @throws ApiError
     */
    public static rank(
        contestId: string,
    ): CancelablePromise<ResultListContestRankRowVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/contest/rank',
            query: {
                'contestId': contestId,
            },
        });
    }
    /**
     * @returns ResultListContestListItemVO OK
     * @throws ApiError
     */
    public static listContests(): CancelablePromise<ResultListContestListItemVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/contest/list',
        });
    }
    /**
     * @param id
     * @returns ResultContestDetailVO OK
     * @throws ApiError
     */
    public static getContest(
        id: string,
    ): CancelablePromise<ResultContestDetailVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/contest/get',
            query: {
                'id': id,
            },
        });
    }
}
