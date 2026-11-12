package com.spingbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.Date;

/**
 * 用户表
 * &#064;TableName  user
 */
@SuperBuilder      // ← 关键：支持继承的 Builder
@NoArgsConstructor // ← 必须：MyBatis-Plus 需要无参构造
@TableName(value = "user", autoResultMap = true)
@Data
public class User extends BaseEntity{
    /**
     * 用户ID，主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 用户名，唯一
     */
    private String username;

    /**
     * 加密后的密码（BCrypt+Pepper）
     */
    private String password;

    /**
     * 邮箱，唯一（用于登录）
     */
    private String email;

    /**
     * 手机号，唯一（用于登录）
     */
    private String phone;

    /**
     * 头像URL
     */
    private String avatar;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 性别：0-未知，1-男，2-女
     */
    private Integer sex;

    /**
     * 生日
     */
    private Date birthday;

    /**
     * 所在地
     */
    private String locationCodes;
    /**
     * 国家
     */
    private String country;

    /**
     * 个人介绍
     */
    private String introduction;

    /**
     * 个人网站
     */
    private String website;

    /**
     * 用户角色：user-普通用户，admin-管理员
     */
    private String userRole;

    /**
     * 账号状态：0-禁用，1-正常
     */
    private Integer status;

    /**
     * 自动解禁时间（违禁评论封禁等）；管理员永久禁用一般为 null
     */
    private Date banUntil;

    /**
     * 封禁原因说明
     */
    private String banReason;

    /**
     * 封禁类型：admin-管理员禁用，profanity-违禁评论自动封禁
     */
    private String banType;

    /**
     * 违禁评论限制解禁时间（与 AI 助手限制独立，互不影响）
     */
    private Date commentBanUntil;

    /**
     * 违禁评论限制原因
     */
    private String commentBanReason;

    /**
     * AI 学习助手违禁限制解禁时间
     */
    private Date aiBanUntil;

    /**
     * AI 学习助手违禁限制原因
     */
    private String aiBanReason;

    /**
     * 连续登录失败次数（可选）
     */
    private Integer loginFailCount;

    /**
     * 最后登录时间
     */
    private Date lastLoginTime;

    /**
     * 最后登录IP
     */
    private String lastLoginIp;

    /** 最近一次成功登录的浏览器设备类别、系统和浏览器（均为客户端自报，非硬件检测）。 */
    private String lastLoginDevice;
    private String lastLoginOs;
    private String lastLoginBrowser;
    private String lastLoginDeviceDetails;

    /**
     * 用户积分（签到等操作累加；对应表字段 points，默认 0）
     */
    private Integer points;

    /**
     * 累计成功签到次数（每自然日首次签到 +1；对应表字段 check_in_count）
     */
    private Integer checkInCount;

    /**
     * 最后一次签到的「自然日」日期，不含时分秒。
     * <p>
     * 与签到业务里使用的时区一致（当前为 Asia/Shanghai），
     * 用于判断「今天是否已经签过」：若等于今天的 LocalDate，则今日不可再签。
     * </p>
     */
    private LocalDate lastCheckInDate;

    /**
     * 已读至该 id（含）的全站公告；用于未读公告计数
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastReadAnnouncementId;

}
