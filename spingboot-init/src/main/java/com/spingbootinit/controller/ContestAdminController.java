package com.spingbootinit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.result.Result;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.context.UserContext.IsAdminRoleString;
import com.spingbootinit.context.UserContext.UserContext;
import com.spingbootinit.model.dto.contest.ContestAdminAddRequest;
import com.spingbootinit.model.dto.contest.ContestAdminBatchDeleteRequest;
import com.spingbootinit.model.dto.contest.ContestAdminPageRequest;
import com.spingbootinit.model.dto.contest.ContestAdminUpdateRequest;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.model.vo.contest.ContestAdminListItemVO;
import com.spingbootinit.service.ContestService;
import com.spingbootinit.service.UserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 赛事管理（仅管理员）
 */
@RestController
@RequestMapping("/contest/admin")
public class ContestAdminController {

    @Resource
    private ContestService contestService;

    @Resource
    private UserService userService;

    @Resource
    private IsAdminRoleString isAdminRole;

    private void assertAdmin() {
        Long uid = UserContext.getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN_ERROR);
        }
        User user = userService.getById(uid);
        if (user == null || !isAdminRole.isAdminRoleString(user.getUserRole())) {
            throw new BusinessException(ResultCode.NO_AUTH_ERROR.getCode(), "需要管理员权限");
        }
    }

    @PostMapping("/page")
    public Result<Page<ContestAdminListItemVO>> page(@RequestBody ContestAdminPageRequest request) {
        assertAdmin();
        return Result.success(contestService.pageAdminContests(request));
    }


    @PostMapping("/add")
    public Result<String> add(@Valid @RequestBody ContestAdminAddRequest request) {
        assertAdmin();
        return Result.success(String.valueOf(contestService.adminAddContest(request)));
    }

    @PostMapping("/update")
    public Result<Boolean> update(@Valid @RequestBody ContestAdminUpdateRequest request) {
        assertAdmin();
        contestService.adminUpdateContest(request);
        return Result.success(true);
    }

    @PostMapping("/delete/batch")
    public Result<Boolean> deleteBatch(@Valid @RequestBody ContestAdminBatchDeleteRequest request) {
        assertAdmin();
        contestService.adminDeleteContests(request);
        return Result.success(true);
    }
}
