package ru.bublikov.testagent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Единственная точка входа: путь к файлу + имя метода -> тест написан/обновлён на диске. */
@RestController
public class TestGeneratorController {

    private final TestGeneratorService service;

    public TestGeneratorController(TestGeneratorService service) {
        this.service = service;
    }

    public record Request(String sourceFilePath, String methodName) {
    }

    @PostMapping("/generate-test")
    public TestGeneratorService.Result generateTest(@RequestBody Request request) {
        return service.generateTest(request.sourceFilePath(), request.methodName());
    }
}
