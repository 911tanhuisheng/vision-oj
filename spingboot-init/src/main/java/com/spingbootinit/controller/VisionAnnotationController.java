package com.spingbootinit.controller;

import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.common.result.Result;
import com.spingbootinit.common.result.ResultCode;
import com.spingbootinit.context.UserContext.IsAdminRoleString;
import com.spingbootinit.context.UserContext.UserContext;
import com.spingbootinit.model.dto.vision.VisionAnnotateRequest;
import com.spingbootinit.model.dto.vision.ImageGenerateRequest;
import com.spingbootinit.model.entity.User;
import com.spingbootinit.model.vo.vision.VisionAnnotationVO;
import com.spingbootinit.model.vo.vision.ImageGenerationVO;
import com.spingbootinit.service.UserService;
import com.spingbootinit.service.impl.ImageGenerationService;
import com.spingbootinit.service.impl.VisionAnnotationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/vision")
@RequiredArgsConstructor
@Tag(name = "Vision AI", description = "YOLO 图像题自动标注")
public class VisionAnnotationController {

    private final VisionAnnotationService visionAnnotationService;
    private final ImageGenerationService imageGenerationService;
    private final UserService userService;
    private final IsAdminRoleString isAdminRole;

    @PostMapping("/annotate")
    @Operation(summary = "使用 YOLO 识别题图并生成目标计数")
    public Result<VisionAnnotationVO> annotate(@Valid @RequestBody VisionAnnotateRequest request) {
        Long userId = UserContext.getCurrentUserId();
        User user = userId == null ? null : userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_LOGIN_ERROR);
        }
        if (!isAdminRole.isAdminRoleString(user.getUserRole())) {
            throw new BusinessException(ResultCode.NO_AUTH_ERROR);
        }
        return Result.success(visionAnnotationService.annotate(request));
    }

    @PostMapping("/generate-image")
    @Operation(summary = "使用本地 ComfyUI 生成图像编程题题图并转存 MinIO")
    public Result<ImageGenerationVO> generateImage(@Valid @RequestBody ImageGenerateRequest request) {
        Long userId = requireAdmin();
        return Result.success(imageGenerationService.generate(request, userId));
    }

    @GetMapping("/generation-models")
    public Result<java.util.Map<String, Object>> generationModels() {
        requireAdmin();
        return Result.success(imageGenerationService.catalog());
    }

    private Long requireAdmin() {
        Long userId = UserContext.getCurrentUserId();
        User user = userId == null ? null : userService.getById(userId);
        if (user == null) throw new BusinessException(ResultCode.NOT_LOGIN_ERROR);
        if (!isAdminRole.isAdminRoleString(user.getUserRole())) throw new BusinessException(ResultCode.NO_AUTH_ERROR);
        return userId;
    }
}
