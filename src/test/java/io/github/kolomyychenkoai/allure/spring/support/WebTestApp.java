package io.github.kolomyychenkoai.allure.spring.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Минимальное web-приложение для живого MockMvc-теста: поднимает MVC-слой и пару
 * эндпоинтов. Docker/БД не нужны.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class WebTestApp {

    // вложенный @RestController авто-регистрируется как бин @Configuration-класса
    @RestController
    public static class DemoController {

        @GetMapping("/api/hello/{name}")
        public Map<String, String> hello(@PathVariable String name) {
            return Map.of("greeting", "hello " + name);
        }

        @PostMapping("/api/echo")
        public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            return body;
        }

        @GetMapping("/api/search")
        public Map<String, Object> search(@RequestParam String q) {
            return Map.of("query", q);
        }

        @GetMapping("/api/boom")
        public String boom() {
            throw new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, "boom for test");
        }
    }
}
