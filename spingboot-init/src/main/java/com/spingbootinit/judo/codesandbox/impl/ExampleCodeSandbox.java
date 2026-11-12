package com.spingbootinit.judo.codesandbox.impl;



import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spingbootinit.config.CodeSandboxProperties;
import com.spingbootinit.judo.codesandbox.CodeSandbox;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeRequest;
import com.spingbootinit.judo.codesandbox.model.ExecuteCodeResponse;
import com.spingbootinit.model.dto.questionsubmit.JudgeInfo;
import com.spingbootinit.model.enums.JudgeInfoMessageEnum;
import com.spingbootinit.model.enums.QuestionSubmitStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 通过 HTTP 调用独立 Sandbox 服务执行判题。
 */
@Slf4j
@Service
public class ExampleCodeSandbox implements CodeSandbox {

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private CodeSandboxProperties codeSandboxProperties;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        try {
            String url = codeSandboxProperties.getUrl();
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set(codeSandboxProperties.getAuthHeaderName(), codeSandboxProperties.getAuthKey());
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            if (executeCodeRequest.getUserId() != null && executeCodeRequest.getUserId() > 0) {
                httpHeaders.set("X-OJ-User-Id", executeCodeRequest.getUserId().toString());
            }

            // 创建请求实体（POST请求才适合带body）
            HttpEntity<ExecuteCodeRequest> entity = new HttpEntity<>(executeCodeRequest, httpHeaders);

            // 发送POST请求（因为需要传递请求体）
            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.POST,  // 改为POST
                    entity,
                    String.class
            );

            ObjectMapper objectMapper = new ObjectMapper();
            return parseSandboxResponse(objectMapper, responseEntity.getBody());

        } catch (Exception e) {
            log.error("调用远程沙箱失败", e);
            ExecuteCodeResponse errorResponse = new ExecuteCodeResponse();
            errorResponse.setCode(500);
            errorResponse.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            errorResponse.setMessage("远程调用失败: " + e.getMessage());
            JudgeInfo judgeInfo = new JudgeInfo();
            judgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
            errorResponse.setJudgeInfo(judgeInfo);
            return errorResponse;
        }
    }

    private ExecuteCodeResponse parseSandboxResponse(ObjectMapper objectMapper, String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setCode(readInt(root, "code"));
        response.setMessage(readText(root, "message"));
        response.setStatus(mapSubmitStatus(root));
        response.setOutputList(readOutputList(root));
        response.setJudgeInfo(buildJudgeInfo(root));
        return response;
    }

    private Integer mapSubmitStatus(JsonNode root) {
        String resultType = readText(root, "resultType");
        if ("SUCCESS".equalsIgnoreCase(resultType)) {
            return QuestionSubmitStatusEnum.SUCCEED.getValue();
        }
        return QuestionSubmitStatusEnum.FAILED.getValue();
    }

    private List<String> readOutputList(JsonNode root) {
        List<String> result = new ArrayList<>();
        JsonNode outputListNode = root.get("outputList");
        if (outputListNode != null && outputListNode.isArray()) {
            for (JsonNode item : outputListNode) {
                result.add(item.asText(""));
            }
        }
        return result;
    }

    private JudgeInfo buildJudgeInfo(JsonNode root) {
        JudgeInfo judgeInfo = new JudgeInfo();
        JsonNode judgeInfoNode = root.get("judgeInfo");
        if (judgeInfoNode != null && !judgeInfoNode.isNull()) {
            judgeInfo.setMessage(normalizeJudgeMessage(readText(judgeInfoNode, "message"), readText(root, "resultType")));
            judgeInfo.setTime(readLong(judgeInfoNode, "time"));
            judgeInfo.setMemory(readLong(judgeInfoNode, "memory"));
            return judgeInfo;
        }
        judgeInfo.setMessage(normalizeJudgeMessage(readText(root, "message"), readText(root, "resultType")));
        return judgeInfo;
    }

    private String normalizeJudgeMessage(String rawMessage, String resultType) {
        if ("SUCCESS".equalsIgnoreCase(resultType)) {
            return JudgeInfoMessageEnum.ACCEPTED.getValue();
        }
        if ("COMPILE_ERROR".equalsIgnoreCase(resultType)) {
            return JudgeInfoMessageEnum.COMPILE_ERROR.getValue();
        }
        if ("TIME_LIMIT".equalsIgnoreCase(resultType)) {
            return JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue();
        }
        if (rawMessage == null || rawMessage.isBlank()) {
            return JudgeInfoMessageEnum.SYSTEM_ERROR.getValue();
        }
        return rawMessage;
    }

    private String readText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field == null || field.isNull() ? null : field.asText();
    }

    private Integer readInt(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field == null || field.isNull() ? null : field.asInt();
    }

    private Long readLong(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field == null || field.isNull() ? null : field.asLong();
    }
}
