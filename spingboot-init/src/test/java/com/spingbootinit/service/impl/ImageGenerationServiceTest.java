package com.spingbootinit.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spingbootinit.common.exception.BusinessException;
import com.spingbootinit.config.ImageGenerationProperties;
import com.spingbootinit.model.dto.vision.ImageGenerateRequest;
import com.spingbootinit.service.AiModelSettingsService;
import com.spingbootinit.utils.MinioUtils;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageGenerationServiceTest {
    private final HttpClient client = mock(HttpClient.class);
    private final MinioUtils minio = mock(MinioUtils.class);
    private final AiModelSettingsService settings = mock(AiModelSettingsService.class);
    private final ImageGenerationService service = new ImageGenerationService(
            new ImageGenerationProperties(), new ObjectMapper(), minio, settings, client);

    private ImageGenerateRequest request(String source, String model) {
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("a clear street scene");
        request.setSource(source);
        request.setModelId(model);
        return request;
    }

    @Test
    void rejectsUnlistedCloudModelBeforeLookingUpKey() {
        assertThrows(BusinessException.class, () -> service.generate(request("DASHSCOPE", "some-other-model"), 7L));
        verifyNoInteractions(settings, client, minio);
    }

    @Test
    void rejectsNonBeijingCloudEndpoint() {
        ImageGenerationProperties properties = new ImageGenerationProperties();
        properties.setCloudBaseUrl("https://example.com/compatible-mode/v1");
        ImageGenerationService configured = new ImageGenerationService(properties, new ObjectMapper(), minio, settings, client);
        when(settings.resolve(7L, "DASHSCOPE", null)).thenReturn(
                new AiModelSettingsService.ModelRoute("DASHSCOPE", "qwen-plus", "", "teacher-secret", true));
        assertThrows(BusinessException.class,
                () -> configured.generate(request("DASHSCOPE", "qwen-image-2.1-turbo"), 7L));
        verifyNoInteractions(client, minio);
    }

    @Test
    void oldRequestWithoutSourceStillGeneratesUsingLocalCheckpoint() throws Exception {
        HttpResponse<String> catalog = mock(HttpResponse.class);
        when(catalog.body()).thenReturn("{\"CheckpointLoaderSimple\":{\"input\":{\"required\":{\"ckpt_name\":[[\"v1-5-pruned-emaonly.safetensors\"]]}}}}");
        HttpResponse<String> queued = mock(HttpResponse.class);
        when(queued.statusCode()).thenReturn(200);
        when(queued.body()).thenReturn("{\"prompt_id\":\"abc\"}");
        HttpResponse<String> history = mock(HttpResponse.class);
        when(history.statusCode()).thenReturn(200);
        when(history.body()).thenReturn("{\"abc\":{\"status\":{\"completed\":true},\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\",\"type\":\"output\"}]}}}}");
        HttpResponse<byte[]> image = mock(HttpResponse.class);
        when(image.statusCode()).thenReturn(200);
        when(image.body()).thenReturn(new byte[]{1, 2});
        doReturn(catalog, queued, history, image).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        when(minio.uploadBytes(any(byte[].class), eq("image/png"), eq(".png"), eq("question-images/ai/")))
                .thenReturn("https://minio.example/a.png");
        var result = service.generate(request(null, null), 7L);
        assertEquals("LOCAL_COMFYUI", result.getSource());
        assertEquals("v1-5-pruned-emaonly.safetensors", result.getModelId());
        assertEquals("https://minio.example/a.png", result.getImageUrl());
        verifyNoInteractions(settings);
    }

    @Test
    void localModelMustBeInstalledAndAllowed() throws Exception {
        HttpResponse<String> catalog = mock(HttpResponse.class);
        when(catalog.body()).thenReturn("{\"CheckpointLoaderSimple\":{\"input\":{\"required\":{\"ckpt_name\":[[\"other.safetensors\"]]}}}}");
        doReturn(catalog).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThrows(BusinessException.class, () -> service.generate(request("LOCAL_COMFYUI", "other.safetensors"), 7L));
        verifyNoInteractions(settings, minio);
    }

    @Test
    void cloudGenerationUsesOnlyTeachersKeyAndStoresImageInMinio() throws Exception {
        when(settings.resolve(7L, "DASHSCOPE", null)).thenReturn(
                new AiModelSettingsService.ModelRoute("DASHSCOPE", "qwen-plus", "", "teacher-secret", true));
        HttpResponse<String> generated = mock(HttpResponse.class);
        when(generated.statusCode()).thenReturn(200);
        when(generated.body()).thenReturn("{\"data\":[{\"url\":\"https://example.aliyuncs.com/image.png\"}]}");
        HttpResponse<byte[]> downloaded = mock(HttpResponse.class);
        when(downloaded.statusCode()).thenReturn(200);
        when(downloaded.body()).thenReturn(new byte[]{1, 2, 3});
        when(downloaded.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        doReturn(generated, downloaded).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        when(minio.uploadBytes(any(byte[].class), eq("image/png"), eq(".png"), eq("question-images/ai/")))
                .thenReturn("https://minio.example/question-images/ai/generated.png");

        var result = service.generate(request("DASHSCOPE", "qwen-image-2.1-turbo"), 7L);
        assertEquals("https://minio.example/question-images/ai/generated.png", result.getImageUrl());
        assertEquals("DASHSCOPE", result.getSource());
        verify(settings).resolve(7L, "DASHSCOPE", null);
        verify(minio).uploadBytes(new byte[]{1, 2, 3}, "image/png", ".png", "question-images/ai/");
        verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void productionNativeCloudEndpointUsesNativePayloadAndResponse() throws Exception {
        ImageGenerationProperties properties = new ImageGenerationProperties();
        properties.setCloudBaseUrl("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation");
        ImageGenerationService configured = new ImageGenerationService(properties, new ObjectMapper(), minio, settings, client);
        when(settings.resolve(7L, "DASHSCOPE", null)).thenReturn(
                new AiModelSettingsService.ModelRoute("DASHSCOPE", "qwen-plus", "", "teacher-secret", true));
        HttpResponse<String> generated = mock(HttpResponse.class);
        when(generated.statusCode()).thenReturn(200);
        when(generated.body()).thenReturn("{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://example.aliyuncs.com/image.png\"}]}}]}}");
        HttpResponse<byte[]> downloaded = mock(HttpResponse.class);
        when(downloaded.statusCode()).thenReturn(200);
        when(downloaded.body()).thenReturn(new byte[]{1, 2, 3});
        when(downloaded.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("image/png")), (a, b) -> true));
        doReturn(generated, downloaded).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        when(minio.uploadBytes(any(byte[].class), eq("image/png"), eq(".png"), eq("question-images/ai/")))
                .thenReturn("https://minio.example/generated.png");

        var result = configured.generate(request("DASHSCOPE", "qwen-image-2.1-turbo"), 7L);
        assertEquals("https://minio.example/generated.png", result.getImageUrl());
        var payload = ImageGenerationService.cloudRequestBody("qwen-image-2.1-turbo", "a clear street scene", true);
        assertEquals("a clear street scene", payload.path("input").path("messages").path(0).path("content").path(0).path("text").asText());
        assertEquals("1024*1024", payload.path("parameters").path("size").asText());
        var sent = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("/api/v1/services/aigc/multimodal-generation/generation", sent.getAllValues().get(0).uri().getPath());
    }
}
