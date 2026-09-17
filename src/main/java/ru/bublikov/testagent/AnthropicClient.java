package ru.bublikov.testagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Вызывает Anthropic Messages API в режиме agentic tool use: модель НЕ обязана
 * отвечать сразу — сначала она может сколько угодно раз попросить исходник ещё
 * одного класса из проекта через read_class_source (чтобы точно замокать зависимость
 * или подсмотреть стиль в соседнем тесте), и только когда сочтёт, что контекста
 * достаточно, вызывает submit_test_plan со структурированным решением.
 */
@Component
public class AnthropicClient {

    private static final int MAX_TOOL_ITERATIONS = 6;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AnthropicClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com/v1")
                .defaultHeader("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public enum Action {ADD_TEST_METHOD, UPDATE_TEST_METHOD, CREATE_TEST_CLASS, NO_CHANGE}

    public record AgentDecision(
            Action action, String existingTestMethodName, String testCode, String reasoning) {
    }

    /**
     * @param classResolver вызывается, когда модель просит показать ей класс по имени;
     *                       возвращает исходник или null, если такого класса не нашлось.
     *                       Сам поиск (где искать) — забота вызывающего кода, не модели.
     */
    public AgentDecision decide(String sourceCode, String methodName, String existingTestCode,
                                 Function<String, String> classResolver) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userMessage(sourceCode, methodName, existingTestCode)));

        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", "claude-sonnet-4-5");
            requestBody.put("max_tokens", 4096);
            requestBody.put("system", systemPrompt());
            requestBody.put("tools", List.of(readClassSourceTool(), submitTestPlanTool()));
            requestBody.put("tool_choice", Map.of("type", "auto"));
            requestBody.put("messages", messages);

            JsonNode response = restClient.post()
                    .uri("/messages")
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            messages.add(Map.of("role", "assistant", "content", response.get("content")));

            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (JsonNode block : response.get("content")) {
                if (!"tool_use".equals(block.get("type").asText())) {
                    continue;
                }
                String toolName = block.get("name").asText();
                if ("submit_test_plan".equals(toolName)) {
                    return objectMapper.convertValue(block.get("input"), AgentDecision.class);
                }
                if ("read_class_source".equals(toolName)) {
                    String className = block.get("input").get("className").asText();
                    String source = classResolver.apply(className);
                    toolResults.add(Map.of(
                            "type", "tool_result",
                            "tool_use_id", block.get("id").asText(),
                            "content", source != null ? source : "Класс " + className + " не найден в проекте."));
                }
            }

            if (toolResults.isEmpty()) {
                throw new IllegalStateException("Модель не вызвала ни read_class_source, ни submit_test_plan: " + response);
            }
            messages.add(Map.of("role", "user", "content", toolResults));
        }
        throw new IllegalStateException("Агент не принял решение за " + MAX_TOOL_ITERATIONS + " итераций подряд");
    }

    private String userMessage(String sourceCode, String methodName, String existingTestCode) {
        String testSection = existingTestCode == null
                ? "Тестового класса пока не существует."
                : "Существующий тестовый класс:\n```java\n" + existingTestCode + "\n```";

        return "Метод, для которого нужен тест: " + methodName
                + "\n\nИсходный класс:\n```java\n" + sourceCode + "\n```"
                + "\n\n" + testSection;
    }

    private String systemPrompt() {
        return """
                Ты помогаешь разработчику поддерживать юнит-тесты (JUnit 5) в актуальном
                состоянии для одного конкретного метода Java-класса.

                Тебе дают исходный код класса, имя интересующего метода и (если есть) текущий
                текст тестового класса для этого же исходного класса.

                У тебя есть инструмент read_class_source(className) — им можно запросить
                исходник любого класса проекта по простому имени (без пакета). Пользуйся им,
                когда:
                - методу нужен мок зависимости, а её точная сигнатура не очевидна из самого
                  вызова в коде;
                - хочешь посмотреть на соседний тестовый класс в том же пакете, чтобы перенять
                  стиль команды — именование тестов, какая библиотека ассертов используется,
                  как оформлены моки/стабы. Это не обязательно, но сильно повышает качество
                  результата, если такой класс есть.

                Вызывай read_class_source столько раз, сколько реально нужно (не больше, чем
                нужно), и только потом принимай решение:

                - подходящего теста нет вообще — ADD_TEST_METHOD, новый тест-метод (с @Test) для
                  вставки в существующий класс;
                - тест есть, но проверяет уже неактуальное поведение — UPDATE_TEST_METHOD,
                  existingTestMethodName (точное имя старого метода) и исправленный текст;
                - тестового класса не существует вообще — CREATE_TEST_CLASS, полный текст нового
                  файла (package, импорты, класс, минимум один тест на нужный метод);
                - существующий тест уже корректно покрывает метод — NO_CHANGE, testCode пустой.

                Правь только то, что относится к указанному методу — остальные тест-методы не
                трогай. Кратко объясни решение в reasoning.

                Финальный ответ — только вызовом инструмента submit_test_plan, это единственный
                способ его дать.
                """;
    }

    private Map<String, Object> readClassSourceTool() {
        return Map.of(
                "name", "read_class_source",
                "description", "Найти и прочитать исходник Java-класса по простому имени "
                        + "(без пакета) где-то в проекте — для точного мока зависимости или "
                        + "чтобы подсмотреть стиль в соседнем тестовом классе.",
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of("className", Map.of("type", "string")),
                        "required", List.of("className")));
    }

    private Map<String, Object> submitTestPlanTool() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("ADD_TEST_METHOD", "UPDATE_TEST_METHOD", "CREATE_TEST_CLASS", "NO_CHANGE")),
                        "existingTestMethodName", Map.of("type", "string",
                                "description", "заполняется только для UPDATE_TEST_METHOD"),
                        "testCode", Map.of("type", "string",
                                "description", "новый тест-метод или целый файл класса; пусто для NO_CHANGE"),
                        "reasoning", Map.of("type", "string")),
                "required", List.of("action", "reasoning"));

        return Map.of(
                "name", "submit_test_plan",
                "description", "Зафиксировать финальное решение по актуализации теста для метода",
                "input_schema", schema);
    }
}
