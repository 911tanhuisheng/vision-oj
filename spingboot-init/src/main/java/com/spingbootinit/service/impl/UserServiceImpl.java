package com.spingbootinit.service.impl;


import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spingbootinit.common.constant.RedisKeyConstants;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.common.security.PasswordProcessor;
import com.spingbootinit.mapper.UserCheckInLogMapper;
import com.spingbootinit.mapper.UserMapper;
import com.spingbootinit.model.dto.user.*;
import com.spingbootinit.config.ModerationProperties;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.model.entity.UserCheckInLog;
import com.spingbootinit.model.entity.UserInAppNotification;
import com.spingbootinit.model.vo.uservo.LoginVO;
import com.spingbootinit.model.vo.uservo.TokenRefreshVO;
import com.spingbootinit.model.vo.uservo.UserAdminListItemVO;
import com.spingbootinit.model.vo.uservo.UserAdminPageVO;
import com.spingbootinit.model.vo.uservo.UserCheckInCalendarVO;
import com.spingbootinit.model.vo.uservo.UserCheckInStatusVO;
import com.spingbootinit.model.vo.uservo.UserLeaderboardRowVO;
import com.spingbootinit.model.vo.uservo.UserSessionStatusVO;
import com.spingbootinit.service.InAppNotificationService;
import com.spingbootinit.service.IpRegionService;
import com.spingbootinit.service.UserService;
import com.spingbootinit.service.UserStatusNotifyService;
import com.spingbootinit.utils.ClientIpUtils;
import com.spingbootinit.utils.LoginDeviceInfo;
import com.spingbootinit.utils.JsonUtils;
import com.spingbootinit.utils.JwtUtils;
import com.spingbootinit.utils.RedisUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用户业务实现类。
 * <p>
 * 如果说 Controller 负责“接住请求”，那么 ServiceImpl 负责“真正把事情做完”。
 * 这里通常会包含：
 * <ul>
 *   <li>业务规则判断</li>
 *   <li>数据库查询与更新</li>
 *   <li>必要的事务控制</li>
 *   <li>返回给上层的业务结果组装</li>
 * </ul>
 * 本类继承了 MyBatis-Plus 的 {@code ServiceImpl}，因此可以直接使用 {@code getById}、{@code save}、{@code update} 等通用方法。
 * </p>
 *
 * @author yaoyaoyan
 * @description 针对表【user(用户表)】的数据库操作Service实现
 * @createDate 2026-03-25 23:32:31
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    public static final String BAN_TYPE_ADMIN = "admin";
    public static final String BAN_TYPE_PROFANITY = "profanity";
    public static final String BAN_TYPE_PROFANITY_AI = "profanity_ai";

    /** 每次成功签到增加的积分数（业务常量，与产品约定） */
    private static final int CHECK_IN_REWARD_POINTS = 100;
    /**
     * 「今日」的日历边界：用固定时区算 LocalDate，避免服务器系统时区与业务不一致。
     * 与数据库 last_check_in_date（DATE）比较时，同一套时区下的「今天」才一致。
     */
    private static final ZoneId CHECK_IN_ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private PasswordProcessor passwordProcessor; // 密码加解密组件：这里主要用于加密存储与密码匹配校验

    @Resource
    private IpRegionService ipRegionService;

    @Resource
    private JwtUtils jwtUtils; // JWT 工具：负责生成登录 token

    @Resource
    private RedisUtil redisUtil; // Redis 工具：这里主要用于读取验证码

    @Resource
    private JsonUtils jsonUtils; // JSON 辅助工具：这里主要做个人网站数组的清洗与规范化

    @Resource
    private ModerationProperties moderationProperties;

    @Resource
    private InAppNotificationService inAppNotificationService;

    @Resource
    private UserStatusNotifyService userStatusNotifyService;

    @Resource
    private UserCheckInLogMapper userCheckInLogMapper;

    /**
     * 获取用户基本信息。
     * <p>
     * 缓存用户信息，避免重复查询数据库。
     * </p>
     *
     * @param userId 用户 ID
     * @return 用户信息
     */
    @Override
    public User getByIdWithCache(Long userId) {
        return this.getById(userId);
    }

    /**
     * 用户注册。
     * <p>
     * 这是一个典型的“写入前先做业务校验”的例子：
     * <ol>
     *   <li>判空</li>
     *   <li>检查用户名是否已存在</li>
     *   <li>检查邮箱是否已被注册</li>
     *   <li>确认两次密码输入一致</li>
     *   <li>对密码加密后再落库</li>
     * </ol>
     * 注意：密码绝不能明文存数据库，所以这里必须经过 passwordProcessor.encode 处理。
     * </p>
     *
     * @param registerRequest 注册请求
     */
    @Override
    public void register(RegisterRequest registerRequest) {
        if (registerRequest == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST); // 请求体本身为空，属于最外层参数错误
        }
        // 先按用户名查重，避免重复注册同名账号
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, registerRequest.getUsername());
        if (this.count(queryWrapper) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户已存在");
        }
        // 邮箱选填；不填时统一存 NULL，不能拿 NULL 去查重，否则第二位未填邮箱的用户无法注册。
        String email = registerRequest.getEmail() == null ? null : registerRequest.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        if (email != null && !email.isEmpty()) {
            queryWrapper.clear();
            queryWrapper.eq(User::getEmail, email);
            if (this.count(queryWrapper) > 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "邮箱已被注册");
            }
        }

        // 确认密码校验
        if (!registerRequest.getPassword().equals(registerRequest.getConfirmPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "两次输入的密码不一致");
        }
        // 创建用户实体。
        // 这里只给出注册阶段最基本的字段，其它资料可在后续“个人资料编辑”里补充。
        User newUser = User.builder()
                .username(registerRequest.getUsername())
                .password(passwordProcessor.encode(registerRequest.getPassword()))
                .email(email == null || email.isEmpty() ? null : email)
                .userRole("user")
                .status(1)
                .build();
        // 写入数据库，成功后用户就真正创建完成了
        this.save(newUser);
    }

    /**
     * 用户登录。
     * <p>
     * 核心流程：
     * <ol>
     *   <li>根据用户名查用户</li>
     *   <li>比对密码是否正确</li>
     *   <li>检查账号是否被禁用</li>
     *   <li>校验验证码</li>
     *   <li>记录最近登录信息</li>
     *   <li>签发 JWT token 返回前端</li>
     * </ol>
     * </p>
     *
     * @param loginRequest 登录请求
     */
    @Override
    public LoginVO login(LoginRequest loginRequest, HttpServletRequest request) {
        // 用户名和邮箱共用一个登录框。用户名不允许 @，因此可明确区分两种查找方式。
        String account = loginRequest.getUsername().trim();
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        if (account.contains("@")) {
            queryWrapper.eq(User::getEmail, account);
        } else {
            queryWrapper.eq(User::getUsername, account);
        }
        User user = this.getOne(queryWrapper);
        // 同时判断“用户不存在”与“密码不匹配”，统一返回用户名或密码错误，
        // 这样可以减少向外暴露系统内部信息。
        if (user == null || !passwordProcessor.matches(loginRequest.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户名或密码错误");
        }
        // 禁用账号允许登录，但仅可使用资料/账户等基础能力；业务功能由 JwtInterceptor 拦截
        // 验证码校验
        validateCaptcha(loginRequest.getCode(), request);
        // 登录成功：记录本次客户端 IP 与登录时间（IP 来自 TCP 连接或代理头，即用户访问来源）
        String clientIp = ClientIpUtils.resolve(request);
        LoginDeviceInfo deviceInfo = LoginDeviceInfo.fromUserAgent(request.getHeader("User-Agent"));
        LambdaUpdateWrapper<User> loginUw = new LambdaUpdateWrapper<>();
        loginUw.eq(User::getId, user.getId())
                .set(User::getLastLoginIp, clientIp)
                .set(User::getLastLoginDevice, deviceInfo.device())
                .set(User::getLastLoginOs, deviceInfo.os())
                .set(User::getLastLoginBrowser, deviceInfo.browser())
                .set(User::getLastLoginDeviceDetails, safeDeviceDetails(loginRequest.getDeviceDetails()))
                .set(User::getLastLoginTime, new Date())
                .set(User::getLoginFailCount, 0);
        this.update(loginUw);
        String sessionId = rotateLoginSession(user.getId());
        userStatusNotifyService.bumpStatusRevision(user.getId());
        return buildLoginVO(user, sessionId);
    }

    @Override
    public TokenRefreshVO refreshTokens(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken().trim();
        if (!jwtUtils.validateRefreshToken(refreshToken)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "登录已过期，请重新登录");
        }
        Long userId = jwtUtils.getUserIdFromToken(refreshToken);
        String jti = jwtUtils.getJtiFromToken(refreshToken);
        if (userId == null || jti == null || jti.isEmpty()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "refreshToken 无效");
        }
        assertTokenSessionActive(userId, refreshToken);
        String redisKey = RedisKeyConstants.refreshTokenKey(userId, jti);
        if (!Boolean.TRUE.equals(redisUtil.hasKey(redisKey))) {
            throw new BusinessException(ResultCode.LOGIN_ELSEWHERE.getCode(), ResultCode.LOGIN_ELSEWHERE.getMessage());
        }
        User user = this.getById(userId);
        if (user == null) {
            redisUtil.delete(redisKey);
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在");
        }
        String sessionId = jwtUtils.getSessionIdFromToken(refreshToken);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = rotateLoginSession(userId);
        }
        redisUtil.delete(redisKey);
        return issueTokenPair(user, sessionId);
    }

    @Override
    public void assertTokenSessionActive(Long userId, String token) {
        if (userId == null || token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        String activeSid = getActiveSessionId(userId);
        if (activeSid == null || activeSid.isBlank()) {
            return;
        }
        String tokenSid = jwtUtils.getSessionIdFromToken(token);
        if (tokenSid == null || tokenSid.isBlank() || !activeSid.equals(tokenSid)) {
            throw new BusinessException(ResultCode.LOGIN_ELSEWHERE.getCode(), ResultCode.LOGIN_ELSEWHERE.getMessage());
        }
    }

    private String getActiveSessionId(Long userId) {
        if (userId == null) {
            return null;
        }
        Object raw = redisUtil.get(RedisKeyConstants.activeSessionKey(userId));
        return raw == null ? null : String.valueOf(raw).trim();
    }

    /**
     * 新登录：作废该账号所有 refresh、绑定新的唯一会话 id。
     */
    private String rotateLoginSession(Long userId) {
        redisUtil.deleteKeysByPrefix(RedisKeyConstants.refreshTokenPattern(userId));
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        redisUtil.set(
                RedisKeyConstants.activeSessionKey(userId),
                sessionId,
                jwtUtils.getRefreshTtlSeconds(),
                TimeUnit.SECONDS);
        return sessionId;
    }

    private LoginVO buildLoginVO(User user, String sessionId) {
        TokenRefreshVO pair = issueTokenPair(user, sessionId);
        LoginVO loginVO = new LoginVO();
        loginVO.setAccessToken(pair.getAccessToken());
        loginVO.setRefreshToken(pair.getRefreshToken());
        loginVO.setToken(pair.getToken());
        loginVO.setUserRole(user.getUserRole());
        loginVO.setUsername(user.getUsername());
        loginVO.setId(user.getId());
        loginVO.setAvatar(user.getAvatar());
        loginVO.setStatus(user.getStatus());
        return loginVO;
    }

  /**
   * 签发 access + refresh，并将 refresh 的 jti 写入 Redis（支持轮换与作废）。
   */
    private TokenRefreshVO issueTokenPair(User user, String sessionId) {
        String sid = sessionId == null || sessionId.isBlank()
                ? rotateLoginSession(user.getId())
                : sessionId.trim();
        redisUtil.set(
                RedisKeyConstants.activeSessionKey(user.getId()),
                sid,
                jwtUtils.getRefreshTtlSeconds(),
                TimeUnit.SECONDS);
        String access = jwtUtils.generateAccessToken(user.getId(), user.getUsername(), sid);
        JwtUtils.IssuedRefreshToken issued = jwtUtils.generateRefreshToken(user.getId(), user.getUsername(), sid);
        String refreshKey = RedisKeyConstants.refreshTokenKey(user.getId(), issued.jti());
        redisUtil.set(refreshKey, "1", jwtUtils.getRefreshTtlSeconds(), TimeUnit.SECONDS);
        TokenRefreshVO vo = new TokenRefreshVO();
        vo.setAccessToken(access);
        vo.setRefreshToken(issued.token());
        vo.setToken(access);
        return vo;
    }


    /**
     * 验证码校验。
     * <p>
     * 思路很简单：
     * 先从 Redis 取出服务端之前生成的验证码，再与用户输入做不区分大小写的比对。
     * 校验成功后立即删除，是为了避免同一个验证码被重复使用。
     * </p>
     *
     * @param inputCode 用户输入的验证码
     * @param request   请求
     */
    public void validateCaptcha(String inputCode, HttpServletRequest request) {
        // 验证码通常与客户端 IP 绑定，这样不同用户之间不会串码
        String clientIp = ClientIpUtils.resolve(request);
        String codeKey = RedisKeyConstants.captchaCodeKey(clientIp);
        Object redisCode = redisUtil.get(codeKey);
        if (redisCode == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已过期，请刷新后重试");
        }
        if (inputCode == null || inputCode.trim().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "请输入验证码");
        }
        if (!redisCode.toString().equalsIgnoreCase(inputCode.trim())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码错误");
        }
        // 一次性验证码：校验通过后立刻删除，防止重复使用
        redisUtil.delete(codeKey);
    }

    /**
     * 更新当前登录用户资料。
     * <p>
     * 这里采用“局部更新”思路：
     * 只有前端传了的字段才 set 到 UpdateWrapper 中，未传的字段保持原值不变。
     * 这比整对象覆盖更新更安全，也更适合资料编辑场景。
     * </p>
     *
     * @param userId 当前登录用户 id
     * @param request 更新资料请求
     */
    @Override
    public void updateMyProfile(Long userId, UserUpdateMyRequest request) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "请求参数错误");
        }

        // 通过 LambdaUpdateWrapper 构造 update 语句，eq 表示 where id = 当前用户
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId);

        // 下面这些 if 的含义都是：只有当前字段不是 null，才更新对应列。
        // 这样前端可以只改自己想改的一小部分资料。
        if (request.getNickname() != null) uw.set(User::getNickname, request.getNickname().trim());
        if (request.getAvatar() != null) uw.set(User::getAvatar, request.getAvatar().trim());
        if (request.getIntroduction() != null) uw.set(User::getIntroduction, request.getIntroduction());
        if (request.getCountry() != null) uw.set(User::getCountry, request.getCountry().trim());
        if (request.getSex() != null) uw.set(User::getSex, request.getSex());
        if (request.getBirthday() != null) {
            String birthday = request.getBirthday().trim();
            try {
                uw.set(User::getBirthday, birthday.isEmpty() ? null
                        : java.sql.Date.valueOf(LocalDate.parse(birthday)));
            } catch (DateTimeParseException ex) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "生日格式错误，请使用年月日");
            }
        }
        // website 在 Java 里是 List<String> 语义，但数据库当前存的是 JSON 字符串，
        // 所以这里先把数组清洗干净，再序列化成 JSON 文本写入。
        if (request.getWebsite() != null) {
            uw.set(User::getWebsite, JSONUtil.toJsonStr(jsonUtils.normalizeWebsiteList(request.getWebsite())));
        }
        // 省市区编码同样按 JSON 字符串落库，便于前端级联组件直接回显
        if (request.getLocationCodes() != null) {
            Location loc = request.getLocationCodes();
            boolean empty = (loc.getProvince() == null || loc.getProvince().isBlank())
                    && (loc.getCity() == null || loc.getCity().isBlank())
                    && (loc.getDistrict() == null || loc.getDistrict().isBlank());
            uw.set(User::getLocationCodes, empty ? null : JSONUtil.toJsonStr(loc));
        }

        // email/phone：库表对二者建了唯一索引；空字符串 '' 在 MySQL 里仍是“一个值”，多行不能同为 ''。
        // 用户清空输入框时前端常传 ""，应落库为 NULL，避免 Duplicate entry '' for key 'uk_email' / uk_phone。
//        if (request.getEmail() != null) {
//            String email = request.getEmail().trim();
//            uw.set(User::getEmail, email.isEmpty() ? null : email);
//        }
        if (request.getPhone() != null) {
            String phone = request.getPhone().trim();
            uw.set(User::getPhone, phone.isEmpty() ? null : phone);
        }

        boolean ok = this.update(uw);
        if (!ok) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "更新失败");
        }
    }

    /**
     * 修改当前登录用户密码。
     * <p>
     * 基本原则是：
     * 先校验旧密码，再写入新密码的加密结果。
     * 这样可以防止别人拿到登录态后，直接无验证地修改密码。
     * </p>
     *
     * @param userId 当前登录用户 id
     * @param request 修改密码请求
     */
    @Override
    public void updatePassword(Long userId, UpdatePasswordRequest request) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "请求参数错误");
        }
        // 必须先核对旧密码，确认是账号本人在操作
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "账号不存在");
        }
        if (request.getOldPassword() == null || !passwordProcessor.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "旧密码错误");
        }
        String nextPassword = request.getNewPassword();
        if (nextPassword == null || !nextPassword.matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,20}$")) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "新密码须为6～20位字母和数字组合");
        }
        if (!nextPassword.equals(request.getConfirmNewPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "两次输入的新密码不一致");
        }
        if (passwordProcessor.matches(nextPassword, user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "新密码不能与旧密码相同");
        }
        // 新密码入库前同样要加密，不能明文保存
        user.setPassword(passwordProcessor.encode(request.getNewPassword()));
        boolean ok = this.updateById(user);
        if (!ok) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "修改密码失败");
        }
        rotateLoginSession(userId);

    }

    /**
     * 管理员分页查询用户列表。
     * <p>
     * 这里做了三件事：
     * <ol>
     *   <li>限制分页参数范围，避免前端传过大的 size</li>
     *   <li>按 id 倒序查用户</li>
     *   <li>把数据库实体 User 转成更适合前端表格展示的 VO</li>
     * </ol>
     * </p>
     */
    @Override
    public UserAdminPageVO pageUsersForAdmin(long current, long size, String keyword) {
        // 对分页参数做兜底，避免出现第 0 页、负数页、超大页大小等情况
        if (current < 1) {
            current = 1;
        }
        if (size < 1) {
            size = 10;
        }
        if (size > 100) {
            size = 100;
        }
        Page<User> page = new Page<>(current, size);
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        if (org.springframework.util.StringUtils.hasText(keyword)) {
            q.like(User::getUsername, keyword.trim());
        }
        // 后台通常希望新用户排在前面，所以按 id 倒序
        q.orderByDesc(User::getId);
        Page<User> result = this.page(page, q);
        UserAdminPageVO vo = new UserAdminPageVO();
        vo.setTotal(result.getTotal());
        vo.setCurrent(result.getCurrent());
        vo.setSize(result.getSize());
        // 把数据库实体映射成前端表格专用 VO，避免把不该暴露的字段直接返回
        List<UserAdminListItemVO> list = new ArrayList<>();
        for (User u : result.getRecords()) {
            UserAdminListItemVO item = new UserAdminListItemVO();
            item.setId(u.getId());
            item.setUsername(u.getUsername());
            item.setNickname(u.getNickname());
            item.setEmail(u.getEmail());
            item.setPhone(u.getPhone());
            item.setUserRole(u.getUserRole());
            item.setStatus(u.getStatus());
            item.setBanUntil(u.getBanUntil());
            item.setBanReason(u.getBanReason());
            item.setBanType(u.getBanType());
            item.setCommentBanUntil(resolveCommentBanUntil(u));
            item.setCommentBanReason(resolveCommentBanReason(u));
            item.setAiBanUntil(resolveAiBanUntil(u));
            item.setAiBanReason(resolveAiBanReason(u));
            item.setLastLoginIp(u.getLastLoginIp());
            item.setLastLoginRegion(ipRegionService.estimate(u.getLastLoginIp()));
            item.setLastLoginDevice(u.getLastLoginDevice());
            item.setLastLoginOs(u.getLastLoginOs());
            item.setLastLoginBrowser(u.getLastLoginBrowser());
            item.setLastLoginDeviceDetails(u.getLastLoginDeviceDetails());
            item.setLastLoginTime(u.getLastLoginTime());
            item.setLoginFailCount(u.getLoginFailCount());
            // 以下为签到/积分字段，供管理后台用户列表展示（与自助签到使用同一套 User 表列）
            item.setPoints(u.getPoints());
            item.setCheckInCount(u.getCheckInCount());
            item.setLastCheckInDate(u.getLastCheckInDate());
            boolean abnormal = false;
            StringBuilder reason = new StringBuilder();
            if (isProfanityCommentBanActive(u)) {
                abnormal = true;
                reason.append("违禁评论限制");
                String cReason = resolveCommentBanReason(u);
                if (cReason != null && !cReason.isBlank()) {
                    reason.append("：").append(cReason.trim());
                }
                Date cUntil = resolveCommentBanUntil(u);
                if (cUntil != null) {
                    reason.append("（解禁 ").append(formatBanUntilText(cUntil)).append("）");
                }
            }
            if (isProfanityAiAssistBanActive(u)) {
                abnormal = true;
                if (reason.length() > 0) {
                    reason.append("；");
                }
                reason.append("AI 助手限制");
                String aReason = resolveAiBanReason(u);
                if (aReason != null && !aReason.isBlank()) {
                    reason.append("：").append(aReason.trim());
                }
                Date aUntil = resolveAiBanUntil(u);
                if (aUntil != null) {
                    reason.append("（解禁 ").append(formatBanUntilText(aUntil)).append("）");
                }
            }
            if (!abnormal && u.getStatus() != null && u.getStatus() == 0) {
                abnormal = true;
                reason.append("管理员禁用");
                if (u.getBanReason() != null && !u.getBanReason().isBlank()) {
                    reason.append("：").append(u.getBanReason().trim());
                }
                if (u.getBanUntil() != null) {
                    reason.append("（解禁 ").append(formatBanUntilText(u.getBanUntil())).append("）");
                } else {
                    reason.append("（永久，需手动启用）");
                }
            }
            if (u.getLoginFailCount() != null && u.getLoginFailCount() >= 5) {
                abnormal = true;
                if (reason.length() > 0) {
                    reason.append("；");
                }
                reason.append("连续登录失败次数过多");
            }
            item.setAbnormal(abnormal);
            item.setAbnormalReason(abnormal ? reason.toString() : "正常");
            list.add(item);
        }
        vo.setRecords(list);
        return vo;
    }

    /**
     * 管理员修改用户状态。
     * <p>
     * 这是一个很典型的后台管理操作：
     * 先校验参数和业务规则，再更新目标用户状态。
     * 例如这里专门禁止“管理员把自己禁用”，否则可能把自己锁在系统外。
     * </p>
     */
    @Override
    public void setUserStatusByAdmin(Long operatorUserId, UserAdminSetStatusRequest request) {
        if (operatorUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (request == null || request.getUserId() == null || request.getStatus() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "参数错误");
        }
        if (request.getStatus() != 0 && request.getStatus() != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "状态取值非法");
        }
        if (request.getStatus() == 0 && request.getUserId().equals(operatorUserId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能禁用当前登录账号");
        }
        // 目标用户必须真实存在，不能直接 update 一个不存在的 id
        User target = this.getById(request.getUserId());
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, request.getUserId()).set(User::getStatus, request.getStatus());
        if (request.getStatus() == 0) {
            uw.set(User::getBanType, BAN_TYPE_ADMIN);
            Integer banHoursReq = request.getBanHours();
            if (banHoursReq != null && banHoursReq > 0) {
                int hours = Math.min(banHoursReq, moderationProperties.resolvedAdminBanMaxHours());
                hours = Math.max(1, hours);
                Date banUntil = addHours(new Date(), hours);
                uw.set(User::getBanReason, "管理员禁用 " + hours + " 小时")
                        .set(User::getBanUntil, banUntil);
            } else {
                uw.set(User::getBanReason, "管理员永久禁用")
                        .set(User::getBanUntil, null);
            }
        } else {
            uw.set(User::getBanType, null)
                    .set(User::getBanReason, null)
                    .set(User::getBanUntil, null);
        }
        boolean ok = this.update(uw);
        if (!ok) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "更新状态失败");
        }
        if (request.getStatus() == 0) {
            revokeSessionsOnDisable(request.getUserId());
            notifyAdminBan(request.getUserId(), request.getBanHours());
        } else {
            clearDisableMarkerOnEnable(request.getUserId());
        }
        userStatusNotifyService.bumpStatusRevision(request.getUserId());
    }

    private void notifyAdminBan(Long userId, Integer banHoursReq) {
        if (userId == null) {
            return;
        }
        if (banHoursReq == null || banHoursReq <= 0) {
            inAppNotificationService.send(
                    userId,
                    UserInAppNotification.TYPE_ACCOUNT_ADMIN_BAN,
                    "账号已被管理员禁用",
                    "您的账号已被管理员永久禁用，暂不可使用做题、博客、签到等功能。如有疑问请联系管理员。",
                    "notifications",
                    null,
                    "ADMIN_BAN_PERM:" + userId + ":" + System.currentTimeMillis());
            return;
        }
        int hours = Math.min(banHoursReq, moderationProperties.resolvedAdminBanMaxHours());
        hours = Math.max(1, hours);
        User u = this.getById(userId);
        String until = u != null && u.getBanUntil() != null ? formatBanUntilText(u.getBanUntil()) : "";
        inAppNotificationService.send(
                userId,
                UserInAppNotification.TYPE_ACCOUNT_ADMIN_BAN,
                "账号已被管理员限时禁用",
                "您的账号已被管理员禁用 " + hours + " 小时" + (until.isEmpty() ? "" : "（至 " + until + "）")
                        + "，到期后将自动恢复。期间暂不可使用做题、博客、签到等功能。",
                "notifications",
                null,
                "ADMIN_BAN:" + userId + ":" + System.currentTimeMillis());
    }

    @Override
    public void revokeSessionsOnDisable(Long userId) {
        if (userId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String disabledKey = RedisKeyConstants.disabledAtKey(userId);
        redisUtil.set(disabledKey, String.valueOf(now), jwtUtils.getRefreshTtlSeconds(), TimeUnit.SECONDS);
        long removed = redisUtil.deleteKeysByPrefix(RedisKeyConstants.refreshTokenPattern(userId));
        redisUtil.delete(RedisKeyConstants.activeSessionKey(userId));
        log.info("用户 {} 已被禁用：作废 refresh {} 个，disabledAt={}", userId, removed, now);
    }

    @Override
    public void clearDisableMarkerOnEnable(Long userId) {
        if (userId == null) {
            return;
        }
        redisUtil.delete(RedisKeyConstants.disabledAtKey(userId));
        log.info("用户 {} 已解禁，清除 disabledAt 标记", userId);
    }

    @Override
    public Long getDisabledAtMillis(Long userId) {
        if (userId == null) {
            return null;
        }
        Object raw = redisUtil.get(RedisKeyConstants.disabledAtKey(userId));
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean isDisabledUserAllowedPath(String servletPath) {
        if (servletPath == null || servletPath.isEmpty()) {
            return false;
        }
        if (servletPath.startsWith("/user/get/")) {
            return true;
        }
        if ("/user/session/status".equals(servletPath)) {
            return true;
        }
        if ("/user/session/events".equals(servletPath)) {
            return true;
        }
        if (servletPath.startsWith("/user/update/")) {
            return true;
        }
        if (servletPath.startsWith("/user/account/")) {
            return true;
        }
        return servletPath.startsWith("/notification/");
    }

    @Override
    public UserSessionStatusVO getSessionStatus(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        user = refreshExpiredBanState(user);
        UserSessionStatusVO vo = new UserSessionStatusVO();
        vo.setUserId(userId);
        vo.setStatus(user.getStatus());
        vo.setBanType(user.getBanType());
        vo.setBanReason(user.getBanReason());
        vo.setBanUntil(user.getBanUntil());
        vo.setCommentBanned(isProfanityCommentBanActive(user));
        vo.setCommentBanUntil(resolveCommentBanUntil(user));
        vo.setCommentBanReason(resolveCommentBanReason(user));
        vo.setAiAssistBanned(isProfanityAiAssistBanActive(user));
        vo.setAiBanUntil(resolveAiBanUntil(user));
        vo.setAiBanReason(resolveAiBanReason(user));
        vo.setStatusRevision(userStatusNotifyService.getStatusRevision(userId));
        return vo;
    }

    @Override
    public void assertCanPostComment(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        if (shouldAutoReleaseProfanityBan(user)) {
            releaseProfanityBan(userId, true);
            return;
        }
        if (isProfanityCommentBanActive(user)) {
            throw new BusinessException(
                    ResultCode.COMMENT_PROFANITY_BANNED.getCode(),
                    profanityCommentBanMessage(user));
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(
                    ResultCode.ACCOUNT_DISABLED.getCode(),
                    ResultCode.ACCOUNT_DISABLED.getMessage());
        }
    }

    @Override
    public boolean isProfanityCommentBanActive(User user) {
        return isBanUntilActive(resolveCommentBanUntil(user));
    }

    @Override
    public String profanityCommentBanMessage(User user) {
        if (!isProfanityCommentBanActive(user)) {
            return ResultCode.COMMENT_PROFANITY_BANNED.getMessage();
        }
        return "评论功能已限制至 " + formatBanUntilText(resolveCommentBanUntil(user)) + "，到期后自动恢复。请文明发言。";
    }

    /**
     * 独立事务提交：在 {@code addComment} 中检测到违禁词后会抛业务异常，
     * 若与本方法同事务，封禁写入会随之外层回滚，管理员端将看不到封禁原因。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void banForProfanityComment(Long userId, String matchedWord) {
        if (userId == null) {
            return;
        }
        User user = this.getById(userId);
        if (user == null) {
            return;
        }
        if (isAdminRoleString(user.getUserRole())) {
            throw new BusinessException(
                    ResultCode.COMMENT_PROFANITY_BANNED.getCode(),
                    "评论含违禁内容，管理员账号不会被自动封禁");
        }
        migrateLegacyProfanityFieldsIfNeeded(userId);
        int hours = moderationProperties.resolvedCommentBanHours();
        Date banUntil = addHours(new Date(), hours);
        String reason = "发布违禁评论（匹配词：" + (matchedWord == null ? "—" : matchedWord) + "），限制 "
                + hours + " 小时";
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId)
                .set(User::getCommentBanUntil, banUntil)
                .set(User::getCommentBanReason, reason);
        this.update(uw);
        String title = "评论功能因违禁内容被限制";
        String body = "您发布的评论含有不当内容，评论功能已限制 " + hours + " 小时（至 "
                + formatBanUntilText(banUntil) + "）。做题、博客浏览等其他功能不受影响。到期后自动恢复，请文明发言。";
        inAppNotificationService.send(
                userId,
                UserInAppNotification.TYPE_ACCOUNT_PROFANITY_BAN,
                title,
                body,
                "notifications",
                null,
                "PROFANITY_BAN:" + userId + ":" + System.currentTimeMillis());
        log.warn("用户 {} 因违禁评论限制评论 {} 小时，匹配词={}", userId, hours, matchedWord);
        userStatusNotifyService.bumpStatusRevision(userId);
    }

    @Override
    public void assertCanUseAiAssist(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        if (shouldAutoReleaseProfanityAiBan(user)) {
            releaseProfanityAiBan(userId, true);
            return;
        }
        if (isProfanityAiAssistBanActive(user)) {
            throw new BusinessException(
                    ResultCode.AI_ASSIST_PROFANITY_BANNED.getCode(),
                    profanityAiAssistBanMessage(user));
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(
                    ResultCode.ACCOUNT_DISABLED.getCode(),
                    ResultCode.ACCOUNT_DISABLED.getMessage());
        }
    }

    @Override
    public boolean isProfanityAiAssistBanActive(User user) {
        return isBanUntilActive(resolveAiBanUntil(user));
    }

    @Override
    public String profanityAiAssistBanMessage(User user) {
        if (!isProfanityAiAssistBanActive(user)) {
            return ResultCode.AI_ASSIST_PROFANITY_BANNED.getMessage();
        }
        return "学习助手已限制至 " + formatBanUntilText(resolveAiBanUntil(user)) + "，到期后自动恢复。请文明使用 AI 助手。";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void banForProfanityAiAssist(Long userId, String matchedWord) {
        if (userId == null) {
            return;
        }
        User user = this.getById(userId);
        if (user == null) {
            return;
        }
        if (isAdminRoleString(user.getUserRole())) {
            throw new BusinessException(
                    ResultCode.AI_ASSIST_PROFANITY_BANNED.getCode(),
                    "消息含违禁内容，管理员账号不会被自动封禁");
        }
        migrateLegacyProfanityFieldsIfNeeded(userId);
        int hours = moderationProperties.resolvedCommentBanHours();
        Date banUntil = addHours(new Date(), hours);
        String reason = "向 AI 助手发送违禁内容（匹配词：" + (matchedWord == null ? "—" : matchedWord) + "），限制 "
                + hours + " 小时";
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId)
                .set(User::getAiBanUntil, banUntil)
                .set(User::getAiBanReason, reason);
        this.update(uw);
        String title = "学习助手因违禁内容被限制";
        String body = "您向 AI 学习助手发送了不当内容，学习助手功能已限制 " + hours + " 小时（至 "
                + formatBanUntilText(banUntil) + "）。做题、评论、博客浏览等其他功能不受影响。到期后自动恢复，请文明使用。"
                + "如需提前解除请联系管理员。";
        inAppNotificationService.send(
                userId,
                UserInAppNotification.TYPE_ACCOUNT_PROFANITY_BAN,
                title,
                body,
                "notifications",
                null,
                "PROFANITY_AI_BAN:" + userId + ":" + System.currentTimeMillis());
        log.warn("用户 {} 因 AI 助手违禁内容限制学习助手 {} 小时，匹配词={}", userId, hours, matchedWord);
        userStatusNotifyService.bumpStatusRevision(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearProfanityCommentBanByAdmin(Long operatorUserId, Long userId) {
        if (operatorUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "参数错误");
        }
        User target = this.getById(userId);
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        if (!isProfanityCommentBanActive(target)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "该用户当前无违禁评论限制");
        }
        releaseProfanityBan(userId, true);
        userStatusNotifyService.bumpStatusRevision(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearProfanityAiAssistBanByAdmin(Long operatorUserId, Long userId) {
        if (operatorUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "参数错误");
        }
        User target = this.getById(userId);
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        if (!isProfanityAiAssistBanActive(target)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "该用户当前无 AI 助手限制");
        }
        releaseProfanityAiBan(userId, true);
        userStatusNotifyService.bumpStatusRevision(userId);
    }

    /**
     * 定时解禁已到期的「违禁评论」封禁账号，并发送站内通知。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseExpiredProfanityBans() {
        Date now = new Date();
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        q.and(w -> w.isNotNull(User::getCommentBanUntil).le(User::getCommentBanUntil, now)
                .or(w2 -> w2.eq(User::getBanType, BAN_TYPE_PROFANITY)
                        .isNotNull(User::getBanUntil)
                        .le(User::getBanUntil, now)));
        List<User> expired = this.list(q);
        for (User u : expired) {
            if (u.getId() != null) {
                releaseProfanityBan(u.getId(), true);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseExpiredProfanityAiBans() {
        Date now = new Date();
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        q.and(w -> w.isNotNull(User::getAiBanUntil).le(User::getAiBanUntil, now)
                .or(w2 -> w2.eq(User::getBanType, BAN_TYPE_PROFANITY_AI)
                        .isNotNull(User::getBanUntil)
                        .le(User::getBanUntil, now)));
        List<User> expired = this.list(q);
        for (User u : expired) {
            if (u.getId() != null) {
                releaseProfanityAiBan(u.getId(), true);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseExpiredAdminBans() {
        LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
        q.eq(User::getStatus, 0)
                .eq(User::getBanType, BAN_TYPE_ADMIN)
                .isNotNull(User::getBanUntil)
                .le(User::getBanUntil, new Date());
        List<User> expired = this.list(q);
        for (User u : expired) {
            if (u.getId() != null) {
                releaseAdminBan(u.getId(), true);
            }
        }
    }

    @Override
    public User refreshExpiredBanState(User user) {
        if (user == null || user.getId() == null) {
            return user;
        }
        Long userId = user.getId();
        if (shouldAutoReleaseProfanityBan(user)) {
            releaseProfanityBan(userId, true);
            return this.getById(userId);
        }
        if (shouldAutoReleaseProfanityAiBan(user)) {
            releaseProfanityAiBan(userId, true);
            return this.getById(userId);
        }
        if (shouldAutoReleaseAdminBan(user)) {
            releaseAdminBan(userId, true);
            return this.getById(userId);
        }
        return user;
    }

    private boolean shouldAutoReleaseAdminBan(User user) {
        if (user == null || user.getStatus() == null || user.getStatus() != 0) {
            return false;
        }
        if (!BAN_TYPE_ADMIN.equals(user.getBanType())) {
            return false;
        }
        Date until = user.getBanUntil();
        return until != null && !until.after(new Date());
    }

    private void releaseAdminBan(Long userId, boolean notify) {
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId)
                .eq(User::getBanType, BAN_TYPE_ADMIN)
                .set(User::getStatus, 1)
                .set(User::getBanType, null)
                .set(User::getBanReason, null)
                .set(User::getBanUntil, null);
        boolean ok = this.update(uw);
        if (!ok) {
            return;
        }
        clearDisableMarkerOnEnable(userId);
        if (notify) {
            inAppNotificationService.send(
                    userId,
                    UserInAppNotification.TYPE_ACCOUNT_ADMIN_UNBAN,
                    "账号限制已到期",
                    "管理员设置的禁用期已结束，账号功能已恢复。请遵守平台规范。",
                    "notifications",
                    null,
                    "ADMIN_UNBAN:" + userId + ":" + System.currentTimeMillis());
        }
        log.info("用户 {} 管理员限时禁用已到期并自动解禁", userId);
        userStatusNotifyService.bumpStatusRevision(userId);
    }

    private boolean shouldAutoReleaseProfanityBan(User user) {
        Date until = resolveCommentBanUntil(user);
        return until != null && !until.after(new Date());
    }

    private void releaseProfanityBan(Long userId, boolean notify) {
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId)
                .set(User::getCommentBanUntil, null)
                .set(User::getCommentBanReason, null);
        User current = this.getById(userId);
        if (current != null && BAN_TYPE_PROFANITY.equals(current.getBanType())) {
            uw.set(User::getBanType, null)
                    .set(User::getBanReason, null)
                    .set(User::getBanUntil, null);
        }
        boolean ok = this.update(uw);
        if (!ok) {
            return;
        }
        if (notify) {
            inAppNotificationService.send(
                    userId,
                    UserInAppNotification.TYPE_ACCOUNT_PROFANITY_UNBAN,
                    "违禁评论限制已到期",
                    "您的评论限制期已结束，可以正常发表评论。请遵守社区规范，勿再发布不良评论；再次违规可能再次被限制。",
                    "notifications",
                    null,
                    "PROFANITY_UNBAN:" + userId + ":" + System.currentTimeMillis());
        }
        log.info("用户 {} 违禁评论封禁已到期并自动解禁", userId);
        userStatusNotifyService.bumpStatusRevision(userId);
    }

    private boolean shouldAutoReleaseProfanityAiBan(User user) {
        Date until = resolveAiBanUntil(user);
        return until != null && !until.after(new Date());
    }

    private void releaseProfanityAiBan(Long userId, boolean notify) {
        User current = this.getById(userId);
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId)
                .set(User::getAiBanUntil, null)
                .set(User::getAiBanReason, null);
        if (current != null && BAN_TYPE_PROFANITY_AI.equals(current.getBanType())) {
            uw.set(User::getBanType, null)
                    .set(User::getBanReason, null)
                    .set(User::getBanUntil, null);
        }
        boolean ok = this.update(uw);
        if (!ok) {
            return;
        }
        if (notify) {
            inAppNotificationService.send(
                    userId,
                    UserInAppNotification.TYPE_ACCOUNT_PROFANITY_UNBAN,
                    "学习助手限制已到期",
                    "您的 AI 学习助手限制期已结束，可以正常使用。请文明提问，勿再发送不良内容；再次违规可能再次被限制。",
                    "notifications",
                    null,
                    "PROFANITY_AI_UNBAN:" + userId + ":" + System.currentTimeMillis());
        }
        log.info("用户 {} AI 助手违禁限制已解除", userId);
        userStatusNotifyService.bumpStatusRevision(userId);
    }

    private static boolean isBanUntilActive(Date until) {
        return until != null && until.after(new Date());
    }

    private static Date resolveCommentBanUntil(User user) {
        if (user == null) {
            return null;
        }
        if (user.getCommentBanUntil() != null) {
            return user.getCommentBanUntil();
        }
        if (BAN_TYPE_PROFANITY.equals(user.getBanType())) {
            return user.getBanUntil();
        }
        return null;
    }

    private static String resolveCommentBanReason(User user) {
        if (user == null) {
            return null;
        }
        if (user.getCommentBanReason() != null && !user.getCommentBanReason().isBlank()) {
            return user.getCommentBanReason();
        }
        if (BAN_TYPE_PROFANITY.equals(user.getBanType())) {
            return user.getBanReason();
        }
        return null;
    }

    private static Date resolveAiBanUntil(User user) {
        if (user == null) {
            return null;
        }
        if (user.getAiBanUntil() != null) {
            return user.getAiBanUntil();
        }
        if (BAN_TYPE_PROFANITY_AI.equals(user.getBanType())) {
            return user.getBanUntil();
        }
        return null;
    }

    private static String resolveAiBanReason(User user) {
        if (user == null) {
            return null;
        }
        if (user.getAiBanReason() != null && !user.getAiBanReason().isBlank()) {
            return user.getAiBanReason();
        }
        if (BAN_TYPE_PROFANITY_AI.equals(user.getBanType())) {
            return user.getBanReason();
        }
        return null;
    }

    /**
     * 将旧版 ban_type/ban_until 迁移到分字段，避免评论封禁覆盖 AI 封禁。
     */
    private void migrateLegacyProfanityFieldsIfNeeded(Long userId) {
        User u = this.getById(userId);
        if (u == null) {
            return;
        }
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId);
        boolean changed = false;
        if (BAN_TYPE_PROFANITY.equals(u.getBanType()) && u.getBanUntil() != null && u.getCommentBanUntil() == null) {
            uw.set(User::getCommentBanUntil, u.getBanUntil());
            if (u.getBanReason() != null) {
                uw.set(User::getCommentBanReason, u.getBanReason());
            }
            changed = true;
        }
        if (BAN_TYPE_PROFANITY_AI.equals(u.getBanType()) && u.getBanUntil() != null && u.getAiBanUntil() == null) {
            uw.set(User::getAiBanUntil, u.getBanUntil());
            if (u.getBanReason() != null) {
                uw.set(User::getAiBanReason, u.getBanReason());
            }
            changed = true;
        }
        if (BAN_TYPE_PROFANITY.equals(u.getBanType()) || BAN_TYPE_PROFANITY_AI.equals(u.getBanType())) {
            uw.set(User::getBanType, null)
                    .set(User::getBanReason, null)
                    .set(User::getBanUntil, null);
            changed = true;
        }
        if (changed) {
            this.update(uw);
        }
    }

    private static Date addHours(Date from, int hours) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        cal.add(Calendar.HOUR_OF_DAY, hours);
        return cal.getTime();
    }

    private static String formatBanUntilText(Date banUntil) {
        if (banUntil == null) {
            return "";
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(banUntil);
    }

    /**
     * 当前用户自助注销账号。
     * <p>
     * 这里不是简单删除一行数据，而是先把用户名、邮箱、手机号、密码等字段做脱敏/替换，
     * 再执行删除逻辑。这样做通常是为了：
     * <ul>
     *   <li>释放唯一索引占用</li>
     *   <li>减少敏感数据残留</li>
     *   <li>符合项目的“逻辑删除 + 脱敏”策略</li>
     * </ul>
     * </p>
     */
    @Override
    public void deleteMyAccount(Long userId, DeleteMyAccountRequest request) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        if (request == null || request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "请输入登录密码以确认注销");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        // 管理员账号通常承担系统管理职责，因此这里不允许直接自助注销
        if (isAdminRoleString(user.getUserRole())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "管理员账号无法自助注销，请先移交权限后再操作");
        }
        if (!passwordProcessor.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "密码错误");
        }
        // 先生成一组不会与正常用户冲突的占位值，用来覆盖原有敏感字段
        long ts = System.currentTimeMillis();
        String scrubUsername = "del_" + userId + "_" + ts;
        if (scrubUsername.length() > 50) {
            scrubUsername = scrubUsername.substring(0, 50);
        }
        String scrubEmail = "c" + userId + "@account-closed.invalid";
        if (scrubEmail.length() > 100) {
            scrubEmail = scrubEmail.substring(0, 100);
        }
        String scrubPhone = "x" + userId;
        if (scrubPhone.length() > 20) {
            scrubPhone = scrubPhone.substring(0, 20);
        }
        LambdaUpdateWrapper<User> scrub = new LambdaUpdateWrapper<>();
        // 先把敏感字段改成占位值，再把状态置为禁用
        scrub.eq(User::getId, userId)
                .set(User::getUsername, scrubUsername)
                .set(User::getPassword, passwordProcessor.encode(UUID.randomUUID().toString()))
                .set(User::getEmail, scrubEmail)
                .set(User::getPhone, scrubPhone)
                .set(User::getStatus, 0);
        if (!this.update(scrub)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "注销失败，请稍后重试");
        }
        if (!this.removeById(userId)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "注销失败，请稍后重试");
        }
    }

    /**
     * 判断一个角色字符串是否属于管理员。
     * <p>
     * 项目里角色来源可能不完全统一，有的地方是 admin，有的地方可能是“管理员”，
     * 所以这里做了一个宽松匹配，方便复用。
     * </p>
     */
    private static boolean isAdminRoleString(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String r = role.trim();
        return "admin".equalsIgnoreCase(r) || "管理员".equals(r) || r.toUpperCase().contains("ADMIN");
    }

    /**
     * 查询当前用户的签到展示状态。
     * <p>
     * 这是一个纯读取方法，不修改数据库。
     * 它的作用是把数据库里的积分、累计签到次数、最后签到日，
     * 转成前端更容易直接展示的结构。
     * </p>
     */
    @Override
    public UserCheckInStatusVO getCheckInStatus(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        LocalDate today = LocalDate.now(CHECK_IN_ZONE);
        // 上次签到日期为空，说明从未签到；不为空则拿它和“今天”比较
        LocalDate last = user.getLastCheckInDate();
        UserCheckInStatusVO vo = new UserCheckInStatusVO();
        // 从未签到过 last 为 null，或 last 不是今天 → 未签
        vo.setCheckedToday(last != null && last.equals(today));
        vo.setCheckInCount(user.getCheckInCount() == null ? 0 : user.getCheckInCount());
        vo.setPoints(user.getPoints() == null ? 0 : user.getPoints());
        return vo;
    }

    /**
     * 每日签到核心逻辑：
     * <ol>
     *   <li>再次校验「今天是否已签」，防并发或绕过前端的重复提交</li>
     *   <li>内存中算出新的积分、次数，用 UpdateWrapper 一次性写回（避免先读后写竞态时丢更新，此处仍以单行更新为主）</li>
     *   <li>加 @Transactional：与积分相关的写操作在同一事务中提交或回滚</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCheckInStatusVO checkInToday(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }

        LocalDate today = LocalDate.now(CHECK_IN_ZONE);
        // 如果 last 和 today 相等，说明今天已经签过，直接拒绝重复加分
        LocalDate last = user.getLastCheckInDate();
        if (last != null && last.equals(today)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "今日已签到");
        }
        // 把可能为 null 的积分、累计签到次数按 0 处理，避免空指针，也符合业务直觉
        int points = user.getPoints() == null ? 0 : user.getPoints();
        int count = user.getCheckInCount() == null ? 0 : user.getCheckInCount();
        int newPoints = points + CHECK_IN_REWARD_POINTS;
        int newCount = count + 1;
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        // 把签到后的最新状态一次性写回数据库
        uw.eq(User::getId, userId)
                .set(User::getPoints, newPoints)
                .set(User::getCheckInCount, newCount)
                .set(User::getLastCheckInDate, today);
        if (!this.update(uw)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "签到失败，请稍后重试");
        }
        recordCheckInLog(userId, today);
        UserCheckInStatusVO vo = new UserCheckInStatusVO();
        vo.setCheckedToday(true);
        vo.setCheckInCount(newCount);
        vo.setPoints(newPoints);
        return vo;
    }

    @Override
    public UserCheckInCalendarVO getCheckInCalendar(Long userId, Integer year) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
        int y = year == null ? LocalDate.now(CHECK_IN_ZONE).getYear() : year;
        if (y < 2000 || y > 2100) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "年份无效");
        }
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        LocalDate start = LocalDate.of(y, 1, 1);
        LocalDate end = LocalDate.of(y, 12, 31);
        List<LocalDate> dates = userCheckInLogMapper.selectDatesBetween(userId, start, end);
        List<String> checked = new ArrayList<>();
        for (LocalDate d : dates) {
            if (d != null) {
                checked.add(d.toString());
            }
        }
        UserCheckInStatusVO status = getCheckInStatus(userId);
        UserCheckInCalendarVO vo = new UserCheckInCalendarVO();
        vo.setYear(y);
        vo.setYearCheckInDays(checked.size());
        vo.setCheckedDates(checked);
        vo.setPoints(status.getPoints());
        vo.setCheckInCount(status.getCheckInCount());
        vo.setCheckedToday(status.isCheckedToday());
        return vo;
    }

    private void recordCheckInLog(Long userId, LocalDate checkInDate) {
        UserCheckInLog row = new UserCheckInLog();
        row.setUserId(userId);
        row.setCheckInDate(checkInDate);
        row.setPointsAwarded(CHECK_IN_REWARD_POINTS);
        row.setCreateTime(new Date());
        try {
            userCheckInLogMapper.insert(row);
        } catch (Exception e) {
            log.debug("签到日志已存在或写入跳过 userId={} date={}", userId, checkInDate);
        }
    }

    @Override
    public Page<UserLeaderboardRowVO> pageUserLeaderboard(long current, long pageSize, String dimension) {
        long pageNo = current <= 0 ? 1 : current;
        long size = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        long total = baseMapper.countLeaderboardUsers();
        long offset = (pageNo - 1) * size;
        String dim = dimension == null ? "" : dimension.trim().toLowerCase();
        List<UserLeaderboardRowVO> rows = switch (dim) {
            case "points" -> baseMapper.selectUserLeaderboardByPoints(offset, size);
            case "submissions" -> baseMapper.selectUserLeaderboardBySubmissions(offset, size);
            default -> baseMapper.selectUserLeaderboard(offset, size);
        };
        int rankBase = (int) offset;
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setRankOrder(rankBase + i + 1);
        }
        Page<UserLeaderboardRowVO> page = new Page<>(pageNo, size, total);
        page.setRecords(rows);
        return page;
    }

    @Override
    public void updateLastReadAnnouncementUpTo(long userId, long announcementId) {
        if (userId <= 0 || announcementId <= 0) {
            return;
        }
        User u = this.getById(userId);
        if (u == null) {
            return;
        }
        long cur = u.getLastReadAnnouncementId() == null ? 0L : u.getLastReadAnnouncementId();
        if (announcementId <= cur) {
            return;
        }
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId).set(User::getLastReadAnnouncementId, announcementId);
        this.update(uw);
    }

    private static String safeDeviceDetails(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.replaceAll("[^\\p{L}\\p{N} .×;：；~/-]", "").trim();
        return cleaned.isEmpty() ? null : cleaned.substring(0, Math.min(cleaned.length(), 160));
    }
}




