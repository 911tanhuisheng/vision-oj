package com.spingbootinit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spingbootinit.model.vo.questionsubmitvo.QuestionSubmitCreatedVO;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenApiLongIdConfigTest {
    @Test
    void declaresLongIdsAsStringsButKeepsCountersNumeric() {
        var model = new ObjectSchema()
                .addProperty("id", new IntegerSchema().format("int64"))
                .addProperty("questionId", new IntegerSchema().format("int64"))
                .addProperty("total", new IntegerSchema().format("int64"))
                .addProperty("ids", new ArraySchema().items(new IntegerSchema().format("int64")));
        var operation = new Operation().addParametersItem(new Parameter().name("userId")
                .schema(new IntegerSchema().format("int64")));
        OpenAPI api = new OpenAPI()
                .components(new Components().addSchemas("Example", model))
                .paths(new Paths().addPathItem("/example", new PathItem().get(operation)));

        new OpenApiLongIdConfig().longIdAsStringCustomizer().customise(api);

        Schema<?> result = api.getComponents().getSchemas().get("Example");
        assertEquals("string", result.getProperties().get("id").getType());
        assertEquals("string", result.getProperties().get("questionId").getType());
        assertEquals("integer", result.getProperties().get("total").getType());
        assertEquals("string", ((ArraySchema) result.getProperties().get("ids")).getItems().getType());
        assertEquals("string", operation.getParameters().get(0).getSchema().getType());
    }

    @Test
    void serializesReturnedQuestionIdWithoutLosingDigits() throws Exception {
        QuestionSubmitCreatedVO response = new QuestionSubmitCreatedVO();
        response.setQuestionId(2120682224959410177L);
        response.setSubmitNo(1L);

        String json = new ObjectMapper().writeValueAsString(response);

        assertEquals("2120682224959410177", new ObjectMapper().readTree(json).get("questionId").asText());
        assertEquals("STRING", new ObjectMapper().readTree(json).get("questionId").getNodeType().name());
        assertEquals(1L, new ObjectMapper().readTree(json).get("submitNo").asLong());
    }
}
