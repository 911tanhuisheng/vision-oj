/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { QuestionSubmitAddRequest } from '../models/QuestionSubmitAddRequest';
import type { ResultMySubmitItemVO } from '../models/ResultMySubmitItemVO';
import type { ResultPageMySubmitItemVO } from '../models/ResultPageMySubmitItemVO';
import type { ResultQuestionSubmitCreatedVO } from '../models/ResultQuestionSubmitCreatedVO';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class QuestionSubmitControllerService {
    /**
     * @param requestBody
     * @returns ResultQuestionSubmitCreatedVO OK
     * @throws ApiError
     */
    public static doQuestionSubmit(
        requestBody: QuestionSubmitAddRequest,
    ): CancelablePromise<ResultQuestionSubmitCreatedVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/question_submit/',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * @param id
     * @returns ResultMySubmitItemVO OK
     * @throws ApiError
     */
    public static getSubmitStatus(
        id: string,
    ): CancelablePromise<ResultMySubmitItemVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/question_submit/status/{id}',
            path: {
                'id': id,
            },
        });
    }
    /**
     * @param questionId
     * @param submitNo
     * @returns ResultMySubmitItemVO OK
     * @throws ApiError
     */
    public static getSubmitStatusBySubmitNo(
        questionId: string,
        submitNo: number,
    ): CancelablePromise<ResultMySubmitItemVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/question_submit/status/my',
            query: {
                'questionId': questionId,
                'submitNo': submitNo,
            },
        });
    }
    /**
     * @param submitNo
     * @param current
     * @param pageSize
     * @returns ResultPageMySubmitItemVO OK
     * @throws ApiError
     */
    public static pageMyAllQuestionSubmits(
        submitNo?: number,
        current: number = 1,
        pageSize: number = 10,
    ): CancelablePromise<ResultPageMySubmitItemVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/question_submit/my/page/all',
            query: {
                'submitNo': submitNo,
                'current': current,
                'pageSize': pageSize,
            },
        });
    }
    /**
     * @param current
     * @param pageSize
     * @param userId
     * @param questionId
     * @param submitNo
     * @returns ResultPageMySubmitItemVO OK
     * @throws ApiError
     */
    public static pageAllQuestionSubmitsForAdmin(
        current: number = 1,
        pageSize: number = 10,
        userId?: string,
        questionId?: string,
        submitNo?: number,
    ): CancelablePromise<ResultPageMySubmitItemVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/question_submit/admin/page',
            query: {
                'current': current,
                'pageSize': pageSize,
                'userId': userId,
                'questionId': questionId,
                'submitNo': submitNo,
            },
        });
    }
}
