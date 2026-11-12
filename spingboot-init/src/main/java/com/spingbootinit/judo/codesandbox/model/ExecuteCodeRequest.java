package com.spingbootinit.judo.codesandbox.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequest {

    private List<String> inputList;

    private String code;

    private String language;

    /** Used only to assign per-user Sandbox rate-limit buckets; not part of the JSON body. */
    @JsonIgnore
    private Long userId;

    /** 图像题用：与 inputList 一一对应的 Base64 图片，由主服务从可信 MinIO 读取。 */
    private List<String> imageBase64List;

}
