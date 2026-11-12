package com.spingbootinit.controller;

import com.spingbootinit.common.result.Result;
import com.spingbootinit.context.UserContext.UserContext;
import com.spingbootinit.model.dto.bailian.AiModelConfigRequest;
import com.spingbootinit.service.AiModelSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ai/models")
public class AiModelSettingsController {
    private final AiModelSettingsService settings;

    @GetMapping("/catalog")
    public Result<Map<String, Object>> catalog() {
        return Result.success(settings.catalog());
    }

    @GetMapping("/mine")
    public Result<List<AiModelSettingsService.SavedConfig>> mine() {
        return Result.success(settings.list(UserContext.getCurrentUserId()));
    }

    @PutMapping("/mine/{providerCode}")
    public Result<Void> save(@PathVariable String providerCode, @Valid @RequestBody AiModelConfigRequest body) {
        settings.save(UserContext.getCurrentUserId(), providerCode, body.getModelId(), body.getApiKey());
        return Result.success(null);
    }

    @DeleteMapping("/mine/{providerCode}")
    public Result<Void> delete(@PathVariable String providerCode) {
        settings.delete(UserContext.getCurrentUserId(), providerCode);
        return Result.success(null);
    }

    @PostMapping("/mine/{providerCode}/test")
    public Result<Void> test(@PathVariable String providerCode, @Valid @RequestBody AiModelConfigRequest body) {
        settings.test(UserContext.getCurrentUserId(), providerCode, body.getModelId());
        return Result.success(null);
    }
}
