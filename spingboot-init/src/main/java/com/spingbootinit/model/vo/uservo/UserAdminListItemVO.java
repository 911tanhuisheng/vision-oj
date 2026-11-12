package com.spingbootinit.model.vo.uservo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.Date;

/**
 * 管理员分页查询用户列表的单条记录（不含密码）。
 * <p>
 * points / checkInCount / lastCheckInDate 与「用户自助签到」共用 user 表字段，便于后台审计与运营查看。
 * </p>
 */
@Data
public class UserAdminListItemVO {

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(type = "string", description = "用户 ID，作为十进制字符串返回，避免前端整数精度丢失")
    private Long id;

    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String userRole;
    /** 账号状态：0-禁用，1-正常 */
    private Integer status;
    /** 自动解禁时间（profanity 封禁） */
    private Date banUntil;
    private String banReason;
    /** admin / profanity（历史字段，新封禁请用 commentBan* / aiBan*） */
    private String banType;
    private Date commentBanUntil;
    private String commentBanReason;
    private Date aiBanUntil;
    private String aiBanReason;
    private String lastLoginIp;
    /** IP 数据库估算的归属地，不代表用户实时位置。 */
    private String lastLoginRegion;
    private String lastLoginDevice;
    private String lastLoginOs;
    private String lastLoginBrowser;
    private String lastLoginDeviceDetails;
    private Date lastLoginTime;
    private Integer loginFailCount;
    /** 当前积分 */
    private Integer points;
    /** 累计签到天数 */
    private Integer checkInCount;
    /** 最后一次签到日期（自然日，上海时区） */
    private LocalDate lastCheckInDate;
    /** 是否异常（禁用或连续登录失败过多等） */
    private Boolean abnormal;
    /** 异常说明或「正常」 */
    private String abnormalReason;
}
