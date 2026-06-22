package io.github.kolomyychenkoai.allure.spring.data;

import io.qameta.allure.Allure;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Логирует вызовы Spring Data репозиториев в Allure-отчёт шагом
 * «DB Repo.method» с вложениями «DB Query» (аргументы) и «DB Result» (результат).
 * Ставится автоматически — см. {@code AllureDataJpaAutoConfiguration}, код в тестах не нужен.
 * <p>
 * Гейтинг — по активному Allure тест-кейсу (а НЕ по хардкоду пакетов): логируем все
 * обращения к БД, пока идёт тест (в т.ч. сквозь прод-код), и молчим во время старта
 * контекста. Так модуль не привязан к структуре пакетов потребителя.
 */
@Aspect
public class AllureRepositoryAspect {

    private final Map<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object logRepositoryCall(ProceedingJoinPoint pjp) throws Throwable {
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable error) {
            // при падении репозитория шаг тоже нужен — видно, ЧТО ушло в БД (диагностируемость)
            logStep(pjp, "<exception: " + error.getClass().getSimpleName() + ": " + error.getMessage() + ">");
            throw error;
        }
        logStep(pjp, formatResponse(result));
        return result;
    }

    private void logStep(ProceedingJoinPoint pjp, String resultText) {
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            String repoName = repositoryName(pjp);
            String methodName = pjp.getSignature().getName();
            Object[] args = pjp.getArgs();

            Allure.step("DB " + repoName + "." + methodName, step -> {
                Allure.addAttachment("DB Query", "text/plain",
                        formatRequest(repoName, methodName, args));
                Allure.addAttachment("DB Result", "text/plain", resultText);
            });
        } catch (Throwable ignored) {
            // инструментирование не должно ронять тест
        }
    }

    private static String repositoryName(ProceedingJoinPoint pjp) {
        Class<?>[] ifaces = pjp.getTarget().getClass().getInterfaces();
        return ifaces.length > 0 ? ifaces[0].getSimpleName()
                : pjp.getTarget().getClass().getSimpleName();
    }

    private String formatRequest(String repoName, String methodName, Object[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append(repoName).append('.').append(methodName);
        if (args != null && args.length > 0) {
            sb.append("\n\nArguments:\n");
            for (int i = 0; i < args.length; i++) {
                sb.append("  [").append(i).append("]: ").append(describe(args[i])).append('\n');
            }
        }
        return sb.toString();
    }

    private String formatResponse(Object result) {
        if (result == null) {
            return "void";
        }
        if (result instanceof Optional<?> opt) {
            return opt.map(this::describe).orElse("Optional.empty");
        }
        if (result instanceof Collection<?> col) {
            return "Collection size: " + col.size() + "\n\n"
                    + col.stream().map(this::describe).collect(Collectors.joining("\n"));
        }
        return describe(result);
    }

    private String describe(Object obj) {
        if (obj == null) {
            return "null";
        }
        Class<?> clazz = obj.getClass();
        if (clazz.isPrimitive() || obj instanceof Number || obj instanceof String
                || obj instanceof Boolean || obj instanceof Enum) {
            return obj.toString();
        }
        if (clazz.isAnnotationPresent(jakarta.persistence.Entity.class)) {
            return describeEntity(obj, clazz);
        }
        return obj.toString();
    }

    private String describeEntity(Object obj, Class<?> clazz) {
        Field[] fields = fieldCache.computeIfAbsent(clazz, c -> {
            List<Field> all = new ArrayList<>();
            Class<?> current = c;
            // обходим всю цепочку наследования — чтобы поля BaseEntity (id, createdAt…) не пропали
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        all.add(f);
                    } catch (Throwable inaccessible) {
                        // поле под module-системой без opens — пропускаем, остальное покажем
                    }
                }
                current = current.getSuperclass();
            }
            return all.toArray(new Field[0]);
        });

        StringJoiner sj = new StringJoiner(", ", clazz.getSimpleName() + "{", "}");
        for (Field field : fields) {
            try {
                sj.add(field.getName() + "=" + field.get(obj));
            } catch (Throwable e) {
                // напр. LazyInitializationException по ленивой связи — не теряем остальные поля
                sj.add(field.getName() + "=?");
            }
        }
        return sj.toString();
    }
}
