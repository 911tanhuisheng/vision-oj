package com.spingbootinit.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.result.Result;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.context.UserContext.IsAdminRoleString;
import com.spingbootinit.context.UserContext.UserContext;
import com.spingbootinit.model.dto.user.*;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.model.vo.uservo.LoginVO;
import com.spingbootinit.model.vo.uservo.TokenRefreshVO;
import com.spingbootinit.model.vo.uservo.UserAdminPageVO;
import com.spingbootinit.model.vo.uservo.UserCheckInCalendarVO;
import com.spingbootinit.model.vo.uservo.UserCheckInStatusVO;
import com.spingbootinit.model.vo.uservo.UserLeaderboardRowVO;
import com.spingbootinit.model.vo.uservo.UserProfileVO;
import com.spingbootinit.model.vo.uservo.UserSessionStatusVO;
import com.spingbootinit.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.spingbootinit.service.UserStatusNotifyService;

import java.util.Collections;

/**
 * 用户相关控制器。
 * <p>
 * 这一层的职责很明确：
 * <ul>
 *   <li>接收前端 HTTP 请求</li>
 *   <li>做最外层的登录态/权限判断</li>
 *   <li>调用 Service 处理真正的业务逻辑</li>
 *   <li>把结果统一包装成 Result 返回给前端</li>
 * </ul>
 * 一般来说，Controller 不应该写太重的业务逻辑，复杂规则尽量放到 Service。
 * </p>
 */
@RestController
@Slf4j
@RequestMapping("/user")
@Tag(name = "用户管理", description = "用户登录、注册接口")
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private com.spingbootinit.service.PasswordRecoveryService passwordRecoveryService;

    @Resource
    private com.spingbootinit.service.EmailBindingService emailBindingService;

    @Resource
    private com.spingbootinit.service.AiModelSettingsService aiModelSettingsService;

    /**
     * 判断是否是管理员
     */
    @Resource
    private IsAdminRoleString isAdminRole;

    @Resource
    private UserStatusNotifyService userStatusNotifyService;

    /**
     * 用户注册。
     * <p>
     * 流程：
     * <ol>
     *   <li>接收前端提交的注册信息</li>
     *   <li>交给 Service 做用户名、邮箱、密码等业务校验</li>
     *   <li>注册成功后仅返回成功消息，不直接登录</li>
     * </ol>
     * 这里不在 Controller 重复做字段校验，因为 @Valid + DTO 注解 + Service 业务校验已经足够。
     * </p>
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "用户注册接口")
    @ApiResponse(responseCode = "200", description = "注册成功")
    public Result<Void> register(@Valid @RequestBody RegisterRequest registerRequest){
        userService.register(registerRequest);
        return Result.success("注册成功！",null);
    }

    /**
     * 用户登录。
     * <p>
     * 登录成功后，Service 会完成这些事情：
     * <ul>
     *   <li>校验用户名和密码</li>
     *   <li>校验验证码</li>
     *   <li>记录最近登录 IP、登录时间</li>
     *   <li>生成 JWT token 返回给前端</li>
     * </ul>
     * 前端后续通常会把 token 放到请求头中，访问需要登录的接口。
     * </p>
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "用户登录接口")
    @ApiResponse(responseCode = "200", description = "登录成功")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request){
        LoginVO vo = userService.login(loginRequest,request);
        return Result.success("登录成功！",vo);
    }

    @PostMapping("/password/reset/code")
    @Operation(summary = "发送找回密码验证码", description = "无需登录，向已绑定邮箱发送一次性验证码")
    public Result<Void> sendPasswordResetCode(@Valid @RequestBody PasswordResetCodeRequest body, HttpServletRequest request) {
        return Result.success(passwordRecoveryService.sendCode(body.getEmail(), request), null);
    }

    @PostMapping("/password/reset")
    @Operation(summary = "通过邮箱验证码重置密码", description = "无需登录，验证码10分钟有效")
    public Result<Void> resetPassword(@Valid @RequestBody PasswordResetRequest body) {
        passwordRecoveryService.resetPassword(body.getEmail(), body.getCode(), body.getNewPassword());
        return Result.success("密码重置成功，请使用新密码登录", null);
    }

    /**
     * 使用 refreshToken 换取新的 accessToken（同时轮换 refreshToken）。
     * 无需携带 accessToken；refreshToken 放请求体。
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新访问令牌", description = "accessToken 过期时用 refreshToken 无感续期")
    @ApiResponse(responseCode = "200", description = "刷新成功")
    public Result<TokenRefreshVO> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.success("刷新成功", userService.refreshTokens(request));
    }

    /**
     * 全站用户排行榜（公开）：支持综合 / 积分 / 活跃（提交量）等维度，用于顶栏「排行榜」。
     */
    @GetMapping("/leaderboard/page")
    @Operation(summary = "用户排行榜分页", description = "做题 AC 数与积分排行，无需登录；dimension 可选 composite|points|submissions")
    @ApiResponse(responseCode = "200", description = "成功")
    public Result<Page<UserLeaderboardRowVO>> userLeaderboardPage(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "pageSize", defaultValue = "20") long pageSize,
            @RequestParam(value = "dimension", defaultValue = "composite") String dimension) {
        return Result.success(userService.pageUserLeaderboard(current, pageSize, dimension));
    }

    /**
     * 根据 id 查询当前登录用户自己的资料，用于资料页回显。
     * <p>
     * 这里特意限制“只能查自己”，原因是这个接口返回的是偏个人资料视角的数据，
     * 不是管理员查看别人资料的接口，所以要做 userId 一致性判断。
     * </p>
     */
    @GetMapping("/get/{id}")
    @Operation(summary = "根据id查询用户", description = "根据id查询用户信息")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<UserProfileVO> get(@PathVariable Long id) {

        // 从线程上下文中拿当前登录用户 id。
        // 这个值一般是在 JWT 拦截器解析 token 后提前放进去的。
        Long currentUserId = UserContext.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        // 仅允许查询自己，避免普通用户通过改 URL 直接查看别人的资料。
        // 如果以后要支持管理员查看他人资料，可以在这里额外加角色判断。
        if (!currentUserId.equals(id)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权限");
        }
        // 先查数据库实体 User，再手动组装成给前端展示的 VO。
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        UserProfileVO vo = new UserProfileVO();
        BeanUtil.copyProperties(user, vo, "locationCodes", "website");

        // 复制大部分同名字段；locationCodes、website 这两个字段数据库里存的是 JSON 字符串，
        // 与 VO 里的目标类型不同，因此排除后单独转换。
        String rawLocationCodes = user.getLocationCodes();
        if (StrUtil.isNotBlank(rawLocationCodes) && JSONUtil.isTypeJSON(rawLocationCodes)) {
            vo.setLocationCodes(JSONUtil.toBean(rawLocationCodes, Location.class));
        } else {
            vo.setLocationCodes(null);
        }

        // DB: website 是 JSON 字符串；VO: website 是 List<String>
        String rawWebsite = user.getWebsite();
        if (StrUtil.isNotBlank(rawWebsite) && JSONUtil.isTypeJSONArray(rawWebsite)) {
            vo.setWebsite(JSONUtil.toList(JSONUtil.parseArray(rawWebsite), String.class));
        } else {
            vo.setWebsite(Collections.emptyList());
        }

        vo.setStatus(user.getStatus());

        return Result.success("查询成功！", vo);
    }

    /**
     * 当前登录用户的账号状态（供前端轮询：管理员禁用/解禁后及时生效）。
     */
    @GetMapping("/session/status")
    @Operation(summary = "当前会话账号状态", description = "需登录；禁用账号可调用以同步状态")
    @ApiResponse(responseCode = "200", description = "成功")
    public Result<UserSessionStatusVO> sessionStatus() {
        Long currentUserId = UserContext.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        return Result.success(userService.getSessionStatus(currentUserId));
    }

    /**
     * 账号状态变更 SSE 推送（管理员禁用/解禁、评论限制等）。
     * EventSource 无法带 Authorization 头，可传 query {@code access_token}。
     */
    @GetMapping(value = "/session/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "账号状态变更推送（SSE）", description = "需登录；禁用账号可连接")
    public SseEmitter sessionStatusEvents() {
        Long currentUserId = UserContext.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        return userStatusNotifyService.subscribe(currentUserId);
    }

    /**
     * 签到状态（只读）：需带 JWT，路径在 WebMvcConfig 里已列入拦截器。
     * 典型用法：用户打开头像下拉时拉一次，用于展示积分、累计天数、是否可点「今日签到」。
     */
    @GetMapping("/check-in/status")
    @Operation(summary = "签到状态", description = "当前积分、累计签到次数、今日是否已签到")
    @ApiResponse(responseCode = "200", description = "成功")
    public Result<UserCheckInStatusVO> checkInStatus() {
        Long uid = UserContext.getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        return Result.success("查询成功", userService.getCheckInStatus(uid));
    }

    /**
     * 执行签到：同一天第二次请求会由 Service 抛「今日已签到」（业务码与 HTTP 状态以项目统一规范为准）。
     */
    @PostMapping("/check-in")
    @Operation(summary = "每日签到", description = "每日首次签到 +100 积分")
    @ApiResponse(responseCode = "200", description = "签到成功")
    public Result<UserCheckInStatusVO> checkIn() {
        Long uid = UserContext.getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        UserCheckInStatusVO vo = userService.checkInToday(uid);
        return Result.success("签到成功，+100 积分", vo);
    }

    @GetMapping("/check-in/calendar")
    @Operation(summary = "学习打卡日历", description = "指定年份的已签到日期列表，供热力图展示")
    @ApiResponse(responseCode = "200", description = "成功")
    public Result<UserCheckInCalendarVO> checkInCalendar(
            @RequestParam(value = "year", required = false) Integer year) {
        Long uid = UserContext.getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        return Result.success("查询成功", userService.getCheckInCalendar(uid, year));
    }

    /**
     * 更新当前登录用户自己的资料。
     * <p>
     * 这一层只负责：
     * <ul>
     *   <li>确认当前请求是已登录状态</li>
     *   <li>把当前登录用户 id 传给 Service，避免前端伪造要修改的用户 id</li>
     * </ul>
     * 真正更新哪些字段、如何写库，都在 Service 中处理。
     * </p>
     */
    @PostMapping(value = "/update/my")
    @Operation(summary = "更新我的资料", description = "更新昵称、邮箱、手机号、头像等（传入的字段才会更新）")
    @ApiResponse(responseCode = "200", description = "更新成功")
    public Result<Void> updateMy(@Valid @RequestBody UserUpdateMyRequest body) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        userService.updateMyProfile(userId, body);
        return Result.success("更新成功", null);
    }
    /**
     * 修改当前登录用户的密码。
     * <p>
     * 常见做法是前端只提交“旧密码 + 新密码”，
     * 后端根据当前登录态自动识别是谁在改密码，而不是让前端传 userId。
     * 这样更安全，也更符合“只能修改自己的密码”的业务语义。
     * </p>
     */
    @PostMapping("/update/password")
    @Operation(summary = "修改密码", description = "修改密码")
    @ApiResponse(responseCode = "200", description = "修改成功")
    public Result<Void> updatePassword(@Valid @RequestBody UpdatePasswordRequest request) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        userService.updatePassword(userId, request);
        return Result.success("修改成功", null);
    }

    @PostMapping("/account/email/code")
    @Operation(summary = "发送绑定邮箱验证码")
    public Result<Void> sendEmailBindingCode(@Valid @RequestBody EmailBindingRequest body) {
        emailBindingService.sendCode(UserContext.getCurrentUserId(), body.getEmail(), body.getPassword());
        return Result.success("验证码已发送至新邮箱，10分钟内有效", null);
    }

    @PostMapping("/account/email/bind")
    @Operation(summary = "验证并绑定邮箱")
    public Result<Void> bindEmail(@Valid @RequestBody EmailBindingRequest body) {
        emailBindingService.bind(UserContext.getCurrentUserId(), body.getEmail(), body.getPassword(), body.getCode());
        return Result.success("邮箱绑定成功，今后可用邮箱登录和找回密码", null);
    }

    /**
     * 当前用户自助注销账号。
     * <p>
     * 这里不是物理删除一行就结束，而是先做“脱敏/清洗唯一字段”，再执行逻辑删除，
     * 目的是避免用户名、邮箱、手机号这些唯一值长期占用，同时也减少敏感信息残留。
     * </p>
     */
    @PostMapping("/account/close")
    @Operation(summary = "注销当前账号", description = "校验密码后逻辑删除并脱敏，注销后 token 仍建议前端清空")
    @ApiResponse(responseCode = "200", description = "注销成功")
    public Result<Void> closeMyAccount(@Valid @RequestBody DeleteMyAccountRequest body) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        userService.deleteMyAccount(userId, body);
        aiModelSettingsService.deleteAllForClosedAccount(userId);
        return Result.success("账号已注销", null);
    }

    /**
     * 管理员：分页查询用户列表。
     * <p>
     * 这个接口的典型场景是后台用户管理页：
     * 前端传 current / size，后端返回当前页数据、总数、以及每条用户记录的展示字段。
     * 这里在进入 Service 之前先做两层保护：
     * <ul>
     *   <li>必须已登录</li>
     *   <li>必须是管理员角色</li>
     * </ul>
     * </p>
     */
    @GetMapping("/admin/page")
    @Operation(summary = "管理员分页用户列表", description = "仅管理员可访问")
    @ApiResponse(responseCode = "200", description = "查询成功")
    public Result<UserAdminPageVO> adminPageUsers(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        if (UserContext.getCurrentUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (!isAdminRole.isAdminRoleString(UserContext.getCurrentRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权限");
        }
        return Result.success("查询成功", userService.pageUsersForAdmin(current, size, keyword));
    }

    /**
     * 管理员修改用户状态。
     * <p>
     * 这里的 status 目前约定：
     * <ul>
     *   <li>0：禁用</li>
     *   <li>1：正常</li>
     * </ul>
     * 具体是否合法、是否允许禁用自己等规则，交给 Service 做最终校验。
     * </p>
     */
    @PostMapping("/admin/status")
    @Operation(summary = "管理员设置用户状态", description = "仅管理员；不可禁用自己")
    @ApiResponse(responseCode = "200", description = "操作成功")
    public Result<Void> adminSetUserStatus(@Valid @RequestBody UserAdminSetStatusRequest body) {
        Long uid = UserContext.getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (!isAdminRole.isAdminRoleString(UserContext.getCurrentRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权限");
        }
        userService.setUserStatusByAdmin(uid, body);
        if (body.getStatus() == 0) {
            Integer h = body.getBanHours();
            String msg = (h != null && h > 0) ? "已禁用该用户（" + h + " 小时后自动恢复）" : "已永久禁用该用户";
            return Result.success(msg, null);
        }
        return Result.success("已恢复该用户", null);
    }

    @PostMapping("/admin/clear-comment-ban")
    @Operation(summary = "管理员解除违禁评论限制", description = "仅管理员；仅对 ban_type=profanity 生效")
    @ApiResponse(responseCode = "200", description = "操作成功")
    public Result<Void> adminClearProfanityCommentBan(@Valid @RequestBody UserAdminClearCommentBanRequest body) {
        Long uid = UserContext.getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (!isAdminRole.isAdminRoleString(UserContext.getCurrentRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权限");
        }
        userService.clearProfanityCommentBanByAdmin(uid, body.getUserId());
        return Result.success("已解除评论限制", null);
    }

    @PostMapping("/admin/clear-ai-assist-ban")
    @Operation(summary = "管理员解除 AI 学习助手违禁限制", description = "仅管理员；仅对 ban_type=profanity_ai 生效")
    @ApiResponse(responseCode = "200", description = "操作成功")
    public Result<Void> adminClearProfanityAiAssistBan(@Valid @RequestBody UserAdminClearAiAssistBanRequest body) {
        Long uid = UserContext.getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (!isAdminRole.isAdminRoleString(UserContext.getCurrentRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权限");
        }
        userService.clearProfanityAiAssistBanByAdmin(uid, body.getUserId());
        return Result.success("已解除 AI 助手限制", null);
    }

    /**
     * 兼容前端错误路径：GET /user/get/login（避免抛 NoResourceFoundException）
     * 正确登录方式：POST /user/login
     */
    @GetMapping("/get/login")
    public Result<Void> getLoginWrongPath() {
        return Result.error(ResultCode.NOT_FOUND.getCode(), "请使用 POST /api/user/login 进行登录");
    }

}
