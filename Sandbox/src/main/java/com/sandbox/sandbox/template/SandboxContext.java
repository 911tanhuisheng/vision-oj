package com.sandbox.sandbox.template;

import com.sandbox.model.ExecuteCodeRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 模板方法执行上下文：贯穿保存/编译/运行/清理的共享数据。
 */
@Data
public class SandboxContext {
    public final  ExecuteCodeRequest request;

    public List<String> inputList;
    public final List<String> outputList = new ArrayList<>();

    public File workDir;
    public File imageDir;
    public File sourceFile;

    public long maxTimeMs = 0L;

    /**
     * 构造函数：只保存请求参数
     */
    public SandboxContext(ExecuteCodeRequest request) {
        this.request = request;
    }
}

