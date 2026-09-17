package ru.bublikov.testagent;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Agentic loop: observe (что уже покрыто тестом) -> decide (LLM решает план, по ходу
 * сама запрашивая через read_class_source любые нужные ей классы проекта, и пишет
 * код) -> act (мы сами вставляем/заменяем/создаём файл на диске). LLM никогда не
 * решает, КУДА писать — путь к тестовому файлу вычисляется по конвенции детерминированно,
 * модель отвечает только за содержимое и за то, какие классы ей нужно увидеть.
 */
@Service
public class TestGeneratorService {

    private final AnthropicClient anthropicClient;

    public TestGeneratorService(AnthropicClient anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    public record Result(AnthropicClient.Action action, String testFilePath, String reasoning) {
    }

    public Result generateTest(String sourceFilePath, String methodName) {
        String sourceCode = readFile(Path.of(sourceFilePath));
        Path testPath = deriveTestPath(Path.of(sourceFilePath));
        String existingTestCode = Files.exists(testPath) ? readFile(testPath) : null;

        AnthropicClient.AgentDecision decision = anthropicClient.decide(
                sourceCode, methodName, existingTestCode, this::findClassSource);

        switch (decision.action()) {
            case CREATE_TEST_CLASS -> writeFile(testPath, decision.testCode());
            case ADD_TEST_METHOD -> writeFile(testPath, insertMethod(existingTestCode, decision.testCode()));
            case UPDATE_TEST_METHOD -> writeFile(testPath,
                    replaceMethod(existingTestCode, decision.existingTestMethodName(), decision.testCode()));
            case NO_CHANGE -> {
                // тест уже актуален — ничего не трогаем
            }
        }

        return new Result(decision.action(), testPath.toString(), decision.reasoning());
    }

    /**
     * Ищет файл {@code <className>.java} где угодно в текущем проекте (рекурсивно от
     * рабочей директории процесса) — так модель сама "гуляет" по зависимостям и
     * соседним тестам через read_class_source, вместо того чтобы мы заранее руками
     * разбирали импорты и решали, что ей нужно.
     */
    private String findClassSource(String className) {
        try (Stream<Path> stream = Files.walk(Path.of("."))) {
            return stream
                    .filter(p -> p.getFileName().toString().equals(className + ".java"))
                    .findFirst()
                    .map(this::readFile)
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("Ошибка поиска класса " + className, e);
        }
    }

    /** src/main/java/.../Foo.java -> src/test/java/.../FooTest.java (стандартная конвенция). */
    private Path deriveTestPath(Path sourcePath) {
        String path = sourcePath.toString();
        String testPath = path.replace("src/main/java", "src/test/java")
                .replace(".java", "Test.java");
        return Path.of(testPath);
    }

    private String insertMethod(String existingTestCode, String newMethodCode) {
        int idx = existingTestCode.lastIndexOf('}');
        if (idx == -1) {
            throw new IllegalStateException("Не нашли закрывающую скобку класса, чтобы вставить новый метод");
        }
        return existingTestCode.substring(0, idx) + "\n" + newMethodCode.strip() + "\n" + existingTestCode.substring(idx);
    }

    /** Находит метод по имени построчным подсчётом фигурных скобок и заменяет его целиком. */
    private String replaceMethod(String content, String methodName, String replacement) {
        String[] lines = content.split("\n", -1);
        Pattern signature = Pattern.compile(".*\\bvoid\\s+" + Pattern.quote(methodName) + "\\s*\\(.*");

        int sigLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (signature.matcher(lines[i]).matches()) {
                sigLine = i;
                break;
            }
        }
        if (sigLine == -1) {
            throw new IllegalStateException("Не нашли метод " + methodName + " в тестовом классе для замены");
        }

        int startLine = sigLine;
        while (startLine > 0 && lines[startLine - 1].trim().startsWith("@")) {
            startLine--;
        }

        int braceBalance = 0;
        boolean seenOpenBrace = false;
        int endLine = -1;
        for (int i = sigLine; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') {
                    braceBalance++;
                    seenOpenBrace = true;
                } else if (c == '}') {
                    braceBalance--;
                }
            }
            if (seenOpenBrace && braceBalance == 0) {
                endLine = i;
                break;
            }
        }
        if (endLine == -1) {
            throw new IllegalStateException("Не смогли определить границы метода " + methodName);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < startLine; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append(replacement.strip()).append("\n");
        for (int i = endLine + 1; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать файл: " + path, e);
        }
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось записать файл: " + path, e);
        }
    }
}
