/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ContestAdminAddRequest } from '../models/ContestAdminAddRequest';
import type { ContestAdminBatchDeleteRequest } from '../models/ContestAdminBatchDeleteRequest';
import type { ContestAdminPageRequest } from '../models/ContestAdminPageRequest';
import type { ContestAdminUpdateRequest } from '../models/ContestAdminUpdateRequest';
import type { ResultBoolean } from '../models/ResultBoolean';
import type { ResultPageContestAdminListItemVO } from '../models/ResultPageContestAdminListItemVO';
import type { ResultString } from '../models/ResultString';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class ContestAdminControllerService {
    /**
     * @param requestBody
     * @returns ResultBoolean OK
     * @throws ApiError
     */
    public static update(
        requestBody: ContestAdminUpdateRequest,
    ): CancelablePromise<ResultBoolean> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/contest/admin/update',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * @param requestBody
     * @returns ResultPageContestAdminListItemVO OK
     * @throws ApiError
     */
    public static page(
        requestBody: ContestAdminPageRequest,
    ): CancelablePromise<ResultPageContestAdminListItemVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/contest/admin/page',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * @param requestBody
     * @returns ResultBoolean OK
     * @throws ApiError
     */
    public static deleteBatch(
        requestBody: ContestAdminBatchDeleteRequest,
    ): CancelablePromise<ResultBoolean> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/contest/admin/delete/batch',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * @param requestBody
     * @returns ResultString OK
     * @throws ApiError
     */
    public static add(
        requestBody: ContestAdminAddRequest,
    ): CancelablePromise<ResultString> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/contest/admin/add',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
}
