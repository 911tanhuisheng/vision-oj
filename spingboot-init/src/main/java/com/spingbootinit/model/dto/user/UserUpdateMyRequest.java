package com.spingbootinit.model.dto.user;

import lombok.Data;
import java.util.List;

/**
 * 更新当前登录用户资料（字段均可选，只更新传入的字段）
 */
@Data
public class UserUpdateMyRequest {

    private String nickname;

    private String phone;

    private String avatar;

    private String introduction;

    private Location locationCodes;

    private List<String> website;

    /** 0-未知，1-男，2-女 */
    private Integer sex;

    private String country;

    /** yyyy-MM-dd；空字符串表示清空，null 表示不修改 */
    private String birthday;
}
