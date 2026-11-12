package com.spingbootinit.config;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Java Long ID 在 JSON 中按字符串传递，OpenAPI 也必须声明为字符串。
 * 否则生成的 TypeScript 客户端会把雪花 ID 误标成 number。
 */
@Configuration
public class OpenApiLongIdConfig {

    @Bean
    public OpenApiCustomizer longIdAsStringCustomizer() {
        return openApi -> {
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                openApi.getComponents().getSchemas().values().forEach(model -> {
                    if (model.getProperties() == null) return;
                    model.getProperties().forEach((name, property) -> {
                        if (!(name instanceof String propertyName) || !(property instanceof Schema<?> propertySchema)) return;
                        if (isIdName(propertyName)) {
                            stringifyInt64(propertySchema);
                        } else if ("ids".equals(propertyName) && propertySchema instanceof ArraySchema arraySchema) {
                            stringifyInt64(arraySchema.getItems());
                        }
                    });
                });
            }
            if (openApi.getPaths() != null) {
                openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                    if (operation.getParameters() == null) return;
                    for (Parameter parameter : operation.getParameters()) {
                        if (isIdName(parameter.getName())) stringifyInt64(parameter.getSchema());
                    }
                }));
            }
        };
    }

    private static boolean isIdName(String name) {
        return name != null && ("id".equals(name) || name.endsWith("Id"));
    }

    private static void stringifyInt64(Schema<?> schema) {
        if (schema != null && "integer".equals(schema.getType()) && "int64".equals(schema.getFormat())) {
            schema.setType("string");
            schema.setFormat(null);
            schema.setPattern("^[1-9][0-9]*$");
        }
    }
}
