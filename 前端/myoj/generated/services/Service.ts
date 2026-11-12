/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { AnnouncementPublishRequest } from '../models/AnnouncementPublishRequest';
import type { AnnouncementReadUpToRequest } from '../models/AnnouncementReadUpToRequest';
import type { BlogCommentAddRequest } from '../models/BlogCommentAddRequest';
import type { BlogCommentDeleteRequest } from '../models/BlogCommentDeleteRequest';
import type { BlogCommentLikeToggleRequest } from '../models/BlogCommentLikeToggleRequest';
import type { BlogCommentPageRequest } from '../models/BlogCommentPageRequest';
import type { BlogPostAddRequest } from '../models/BlogPostAddRequest';
import type { BlogPostDeleteRequest } from '../models/BlogPostDeleteRequest';
import type { BlogPostLikeToggleRequest } from '../models/BlogPostLikeToggleRequest';
import type { BlogPostPageRequest } from '../models/BlogPostPageRequest';
import type { BlogPostUpdateRequest } from '../models/BlogPostUpdateRequest';
import type { DeleteMyAccountRequest } from '../models/DeleteMyAccountRequest';
import type { LoginRequest } from '../models/LoginRequest';
import type { NotificationMarkReadRequest } from '../models/NotificationMarkReadRequest';
import type { QuestionAddRequest } from '../models/QuestionAddRequest';
import type { QuestionBatchDeleteRequest } from '../models/QuestionBatchDeleteRequest';
import type { QuestionQueryRequest } from '../models/QuestionQueryRequest';
import type { QuestionRunRequest } from '../models/QuestionRunRequest';
import type { QuestionUpdateRequest } from '../models/QuestionUpdateRequest';
import type { RefreshTokenRequest } from '../models/RefreshTokenRequest';
import type { RegisterRequest } from '../models/RegisterRequest';
import type { ResultBlogCommentLikeToggleVO } from '../models/ResultBlogCommentLikeToggleVO';
import type { ResultBlogCommentPageVO } from '../models/ResultBlogCommentPageVO';
import type { ResultBlogCommentVO } from '../models/ResultBlogCommentVO';
import type { ResultBlogLikeToggleVO } from '../models/ResultBlogLikeToggleVO';
import type { ResultBlogPostDetailVO } from '../models/ResultBlogPostDetailVO';
import type { ResultBoolean } from '../models/ResultBoolean';
import type { ResultListSiteAnnouncementItemVO } from '../models/ResultListSiteAnnouncementItemVO';
import type { ResultLoginVO } from '../models/ResultLoginVO';
import type { ResultNotificationSummaryVO } from '../models/ResultNotificationSummaryVO';
import type { ResultPageBlogPostListItemVO } from '../models/ResultPageBlogPostListItemVO';
import type { ResultPageInAppNotificationItemVO } from '../models/ResultPageInAppNotificationItemVO';
import type { ResultPageQuestionAdminListItemVO } from '../models/ResultPageQuestionAdminListItemVO';
import type { ResultPageUserLeaderboardRowVO } from '../models/ResultPageUserLeaderboardRowVO';
import type { ResultQuestionPublicDetailVO } from '../models/ResultQuestionPublicDetailVO';
import type { ResultQuestionRunVO } from '../models/ResultQuestionRunVO';
import type { ResultString } from '../models/ResultString';
import type { ResultTokenRefreshVO } from '../models/ResultTokenRefreshVO';
import type { ResultUserAdminPageVO } from '../models/ResultUserAdminPageVO';
import type { ResultUserCheckInCalendarVO } from '../models/ResultUserCheckInCalendarVO';
import type { ResultUserCheckInStatusVO } from '../models/ResultUserCheckInStatusVO';
import type { ResultUserProfileVO } from '../models/ResultUserProfileVO';
import type { ResultUserSessionStatusVO } from '../models/ResultUserSessionStatusVO';
import type { ResultVoid } from '../models/ResultVoid';
import type { SseEmitter } from '../models/SseEmitter';
import type { UpdatePasswordRequest } from '../models/UpdatePasswordRequest';
import type { UserAdminClearAiAssistBanRequest } from '../models/UserAdminClearAiAssistBanRequest';
import type { UserAdminClearCommentBanRequest } from '../models/UserAdminClearCommentBanRequest';
import type { UserAdminSetStatusRequest } from '../models/UserAdminSetStatusRequest';
import type { UserUpdateMyRequest } from '../models/UserUpdateMyRequest';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class Service {
    /**
     * 修改密码
     * 修改密码
     * @param requestBody
     * @returns ResultVoid 修改成功
     * @throws ApiError
     */
    public static updatePassword(
        requestBody: UpdatePasswordRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/update/password',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 更新我的资料
     * 更新昵称、邮箱、手机号、头像等（传入的字段才会更新）
     * @param requestBody
     * @returns ResultVoid 更新成功
     * @throws ApiError
     */
    public static updateMy(
        requestBody: UserUpdateMyRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/update/my',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 用户注册
     * 用户注册接口
     * @param requestBody
     * @returns ResultVoid 注册成功
     * @throws ApiError
     */
    public static register(
        requestBody: RegisterRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/register',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 刷新访问令牌
     * accessToken 过期时用 refreshToken 无感续期
     * @param requestBody
     * @returns ResultTokenRefreshVO 刷新成功
     * @throws ApiError
     */
    public static refresh(
        requestBody: RefreshTokenRequest,
    ): CancelablePromise<ResultTokenRefreshVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/refresh',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 用户登录
     * 用户登录接口
     * @param requestBody
     * @returns ResultLoginVO 登录成功
     * @throws ApiError
     */
    public static login(
        requestBody: LoginRequest,
    ): CancelablePromise<ResultLoginVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/login',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 每日签到
     * 每日首次签到 +100 积分
     * @returns ResultUserCheckInStatusVO 签到成功
     * @throws ApiError
     */
    public static checkIn(): CancelablePromise<ResultUserCheckInStatusVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/check-in',
        });
    }
    /**
     * 管理员设置用户状态
     * 仅管理员；不可禁用自己
     * @param requestBody
     * @returns ResultVoid 操作成功
     * @throws ApiError
     */
    public static adminSetUserStatus(
        requestBody: UserAdminSetStatusRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/admin/status',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 管理员解除违禁评论限制
     * 仅管理员；仅对 ban_type=profanity 生效
     * @param requestBody
     * @returns ResultVoid 操作成功
     * @throws ApiError
     */
    public static adminClearProfanityCommentBan(
        requestBody: UserAdminClearCommentBanRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/admin/clear-comment-ban',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 管理员解除 AI 学习助手违禁限制
     * 仅管理员；仅对 ban_type=profanity_ai 生效
     * @param requestBody
     * @returns ResultVoid 操作成功
     * @throws ApiError
     */
    public static adminClearProfanityAiAssistBan(
        requestBody: UserAdminClearAiAssistBanRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/admin/clear-ai-assist-ban',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 注销当前账号
     * 校验密码后逻辑删除并脱敏，注销后 token 仍建议前端清空
     * @param requestBody
     * @returns ResultVoid 注销成功
     * @throws ApiError
     */
    public static closeMyAccount(
        requestBody: DeleteMyAccountRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/user/account/close',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 运行代码（样例）
     * 调用沙箱执行当前代码与题目公开样例（最多 3 组），不入库、不计榜
     * @param requestBody
     * @returns ResultQuestionRunVO OK
     * @throws ApiError
     */
    public static runQuestionSample(
        requestBody: QuestionRunRequest,
    ): CancelablePromise<ResultQuestionRunVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/question/run',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 公开分页查询题目
     * 题库分页列表（无需管理员）
     * @param requestBody
     * @returns ResultPageQuestionAdminListItemVO OK
     * @throws ApiError
     */
    public static pageQuestions(
        requestBody: QuestionQueryRequest,
    ): CancelablePromise<ResultPageQuestionAdminListItemVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/question/page',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 管理员更新题目
     * 按 id 更新题目
     * @param requestBody
     * @returns ResultString OK
     * @throws ApiError
     */
    public static adminUpdateQuestion(
        requestBody: QuestionUpdateRequest,
    ): CancelablePromise<ResultString> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/question/admin/update',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 管理员分页查询题目
     * 支持按标题、内容、标签、出题人筛选
     * @param requestBody
     * @returns ResultPageQuestionAdminListItemVO OK
     * @throws ApiError
     */
    public static adminPageQuestions(
        requestBody: QuestionQueryRequest,
    ): CancelablePromise<ResultPageQuestionAdminListItemVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/question/admin/page',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 管理员批量删除题目
     * 支持单个与批量删除
     * @param requestBody
     * @returns ResultBoolean OK
     * @throws ApiError
     */
    public static adminBatchDeleteQuestions(
        requestBody: QuestionBatchDeleteRequest,
    ): CancelablePromise<ResultBoolean> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/question/admin/delete/batch',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 创建题目
     * 创建题目
     * @param requestBody
     * @returns ResultString OK
     * @throws ApiError
     */
    public static addQuestion(
        requestBody: QuestionAddRequest,
    ): CancelablePromise<ResultString> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/question/add',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 标记已读
     * @param requestBody
     * @returns ResultVoid OK
     * @throws ApiError
     */
    public static markRead(
        requestBody: NotificationMarkReadRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/notification/mark-read',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 全部标记已读
     * @returns ResultVoid OK
     * @throws ApiError
     */
    public static markAllRead(): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/notification/mark-all-read',
        });
    }
    /**
     * 公告已读到指定 id（游标）
     * @param requestBody
     * @returns ResultVoid OK
     * @throws ApiError
     */
    public static readAnnouncementUpTo(
        requestBody: AnnouncementReadUpToRequest,
    ): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/notification/announcement/read-up-to',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 文件上传
     * 上传用户头像
     * @param requestBody
     * @returns ResultString 上传成功
     * @throws ApiError
     */
    public static upload(
        requestBody?: {
            image: Blob;
        },
    ): CancelablePromise<ResultString> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/file/upload',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 更新文章
     * @param requestBody
     * @returns ResultBoolean OK
     * @throws ApiError
     */
    public static update1(
        requestBody: BlogPostUpdateRequest,
    ): CancelablePromise<ResultBoolean> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/post/update',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 公开文章分页
     * @param requestBody
     * @returns ResultPageBlogPostListItemVO OK
     * @throws ApiError
     */
    public static pagePublic(
        requestBody: BlogPostPageRequest,
    ): CancelablePromise<ResultPageBlogPostListItemVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/post/page',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 我的文章分页
     * @param requestBody
     * @returns ResultPageBlogPostListItemVO OK
     * @throws ApiError
     */
    public static pageMine(
        requestBody: BlogPostPageRequest,
    ): CancelablePromise<ResultPageBlogPostListItemVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/post/my/page',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 点赞/取消点赞
     * @param requestBody
     * @returns ResultBlogLikeToggleVO OK
     * @throws ApiError
     */
    public static toggleLike(
        requestBody: BlogPostLikeToggleRequest,
    ): CancelablePromise<ResultBlogLikeToggleVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/post/like/toggle',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 删除文章
     * @param requestBody
     * @returns ResultBoolean OK
     * @throws ApiError
     */
    public static delete(
        requestBody: BlogPostDeleteRequest,
    ): CancelablePromise<ResultBoolean> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/post/delete',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 新建文章
     * @param requestBody
     * @returns ResultString OK
     * @throws ApiError
     */
    public static add1(
        requestBody: BlogPostAddRequest,
    ): CancelablePromise<ResultString> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/post/add',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 评论分页（含热评置顶区）；带 Token 时返回是否已点赞
     * @param requestBody
     * @param authorization
     * @returns ResultBlogCommentPageVO OK
     * @throws ApiError
     */
    public static page1(
        requestBody: BlogCommentPageRequest,
        authorization?: string,
    ): CancelablePromise<ResultBlogCommentPageVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/comment/page',
            headers: {
                'Authorization': authorization,
            },
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 评论点赞开关
     * @param requestBody
     * @returns ResultBlogCommentLikeToggleVO OK
     * @throws ApiError
     */
    public static toggleLike1(
        requestBody: BlogCommentLikeToggleRequest,
    ): CancelablePromise<ResultBlogCommentLikeToggleVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/comment/like/toggle',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 删除我的评论
     * @param requestBody
     * @returns ResultBoolean OK
     * @throws ApiError
     */
    public static delete1(
        requestBody: BlogCommentDeleteRequest,
    ): CancelablePromise<ResultBoolean> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/comment/delete',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 发表评论（一级或回复一级）
     * @param requestBody
     * @returns ResultBlogCommentVO OK
     * @throws ApiError
     */
    public static add2(
        requestBody: BlogCommentAddRequest,
    ): CancelablePromise<ResultBlogCommentVO> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/blog/comment/add',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 发布公告（管理员）
     * @param requestBody
     * @returns ResultString OK
     * @throws ApiError
     */
    public static adminPublish(
        requestBody: AnnouncementPublishRequest,
    ): CancelablePromise<ResultString> {
        return __request(OpenAPI, {
            method: 'POST',
            url: '/announcement/admin/publish',
            body: requestBody,
            mediaType: 'application/json',
        });
    }
    /**
     * 当前会话账号状态
     * 需登录；禁用账号可调用以同步状态
     * @returns ResultUserSessionStatusVO 成功
     * @throws ApiError
     */
    public static sessionStatus(): CancelablePromise<ResultUserSessionStatusVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/user/session/status',
        });
    }
    /**
     * 账号状态变更推送（SSE）
     * 需登录；禁用账号可连接
     * @returns SseEmitter OK
     * @throws ApiError
     */
    public static sessionStatusEvents(): CancelablePromise<SseEmitter> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/user/session/events',
        });
    }
    /**
     * 用户排行榜分页
     * 做题 AC 数与积分排行，无需登录；dimension 可选 composite|points|submissions
     * @param current
     * @param pageSize
     * @param dimension
     * @returns ResultPageUserLeaderboardRowVO 成功
     * @throws ApiError
     */
    public static userLeaderboardPage(
        current: number = 1,
        pageSize: number = 20,
        dimension: string = 'composite',
    ): CancelablePromise<ResultPageUserLeaderboardRowVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/user/leaderboard/page',
            query: {
                'current': current,
                'pageSize': pageSize,
                'dimension': dimension,
            },
        });
    }
    /**
     * 根据id查询用户
     * 根据id查询用户信息
     * @param id
     * @returns ResultUserProfileVO 查询成功
     * @throws ApiError
     */
    public static get(
        id: string,
    ): CancelablePromise<ResultUserProfileVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/user/get/{id}',
            path: {
                'id': id,
            },
        });
    }
    /**
     * @returns ResultVoid OK
     * @throws ApiError
     */
    public static getLoginWrongPath(): CancelablePromise<ResultVoid> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/user/get/login',
        });
    }
    /**
     * 签到状态
     * 当前积分、累计签到次数、今日是否已签到
     * @returns ResultUserCheckInStatusVO 成功
     * @throws ApiError
     */
    public static checkInStatus(): CancelablePromise<ResultUserCheckInStatusVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/user/check-in/status',
        });
    }
    /**
     * 学习打卡日历
     * 指定年份的已签到日期列表，供热力图展示
     * @param year
     * @returns ResultUserCheckInCalendarVO 成功
     * @throws ApiError
     */
    public static checkInCalendar(
        year?: number,
    ): CancelablePromise<ResultUserCheckInCalendarVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/user/check-in/calendar',
            query: {
                'year': year,
            },
        });
    }
    /**
     * 管理员分页用户列表
     * 仅管理员可访问
     * @param current
     * @param size
     * @param keyword
     * @returns ResultUserAdminPageVO 查询成功
     * @throws ApiError
     */
    public static adminPageUsers(
        current: number = 1,
        size: number = 10,
        keyword?: string,
    ): CancelablePromise<ResultUserAdminPageVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/user/admin/page',
            query: {
                'current': current,
                'size': size,
                'keyword': keyword,
            },
        });
    }
    /**
     * 公开获取题目详情
     * 用于题库进入作答页
     * @param id
     * @returns ResultQuestionPublicDetailVO OK
     * @throws ApiError
     */
    public static getPublicQuestionDetail(
        id: string,
    ): CancelablePromise<ResultQuestionPublicDetailVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/question/public/get/{id}',
            path: {
                'id': id,
            },
        });
    }
    /**
     * 未读汇总
     * @returns ResultNotificationSummaryVO OK
     * @throws ApiError
     */
    public static summary(): CancelablePromise<ResultNotificationSummaryVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/notification/summary',
        });
    }
    /**
     * 我的站内通知分页
     * @param current
     * @param pageSize
     * @returns ResultPageInAppNotificationItemVO OK
     * @throws ApiError
     */
    public static page2(
        current: number = 1,
        pageSize: number = 20,
    ): CancelablePromise<ResultPageInAppNotificationItemVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/notification/page',
            query: {
                'current': current,
                'pageSize': pageSize,
            },
        });
    }
    /**
     * 获取验证码
     * 获取验证码
     * @returns any OK
     * @throws ApiError
     */
    public static getCaptcha(): CancelablePromise<any> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/captcha',
        });
    }
    /**
     * 公开文章详情（已发布）；带 Token 时返回是否已点赞
     * @param id
     * @param authorization
     * @returns ResultBlogPostDetailVO OK
     * @throws ApiError
     */
    public static getPublic(
        id: string,
        authorization?: string,
    ): CancelablePromise<ResultBlogPostDetailVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/blog/post/public/get',
            headers: {
                'Authorization': authorization,
            },
            query: {
                'id': id,
            },
        });
    }
    /**
     * 我的文章详情（含草稿）
     * @param id
     * @returns ResultBlogPostDetailVO OK
     * @throws ApiError
     */
    public static getMine(
        id: string,
    ): CancelablePromise<ResultBlogPostDetailVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/blog/post/mine/get',
            query: {
                'id': id,
            },
        });
    }
    /**
     * 最近公告（无需登录）
     * @param limit
     * @returns ResultListSiteAnnouncementItemVO OK
     * @throws ApiError
     */
    public static recent(
        limit: number = 10,
    ): CancelablePromise<ResultListSiteAnnouncementItemVO> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/announcement/public/recent',
            query: {
                'limit': limit,
            },
        });
    }
}
