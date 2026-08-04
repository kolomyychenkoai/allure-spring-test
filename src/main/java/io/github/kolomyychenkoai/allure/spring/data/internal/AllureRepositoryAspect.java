package io.github.kolomyychenkoai.allure.spring.data.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.BaseStream;
import java.util.stream.Collectors;

/**
 * Логирует вызовы Spring Data репозиториев в Allure-отчёт шагом
 * «DB Repo.method» с вложениями «DB Call» (метод и аргументы — снятые ДО вызова,
 * чтобы отражать отправленное в БД) и «DB Result» (что вернулось).
 * Ставится автоматически — см. {@code AllureDataJpaAutoConfiguration}, код в тестах не нужен.
 * <p>
 * Шаг открывается ДО вызова, поэтому реальный SQL (модуль {@code AllureDataSourceListener})
 * вкладывается ВНУТРЬ этого шага — в отчёте видно «вызов репозитория → его SQL»,
 * а не вперемешку. {@code try/finally} гарантирует закрытие шага (степы не «пропадают»).
 * <p>
 * Гейтинг — по активному Allure тест-кейсу (а НЕ по хардкоду пакетов): логируем все
 * обращения к БД, пока идёт тест (в т.ч. сквозь прод-код), и молчим во время старта
 * контекста. Так модуль не привязан к структуре пакетов потребителя.
 * <p>
 * Pointcut ловит ВСЕ методы любого {@code Repository+} (Crud/Jpa/PagingAndSorting +
 * derived-методы). Ограничение: REACTIVE-репозитории (Spring Data R2DBC,
 * {@code ReactiveCrudRepository}) НЕ охвачены — нужен отдельный аспект; модуль рассчитан
 * на синхронный (JPA) стек.
 * <p>
 * Потокобезопасен: единственное общее состояние — {@code fieldCache}
 * ({@link ConcurrentHashMap}); шаги идут на вызывающем потоке через {@code uuid}-локальный
 * lifecycle, общего изменяемого состояния шага между потоками нет.
 */
@Aspect
public class AllureRepositoryAspect {

    // Резолвим jakarta.persistence.Entity рефлексивно: у потребителя Spring Data может быть
    // без JPA (spring-data-jdbc/mongo/redis) — тогда JPA-аннотации на classpath нет, и жёсткая
    // ссылка на .class бросила бы NoClassDefFoundError при первом же вызове репозитория.
    // Null означает «JPA не подключён» — просто идём по generic-рендеру.
    @SuppressWarnings("unchecked")
    private static final Class<? extends Annotation> ENTITY_ANNOTATION = resolveEntityAnnotation();

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> resolveEntityAnnotation() {
        try {
            return (Class<? extends Annotation>) Class.forName("jakarta.persistence.Entity");
        } catch (ClassNotFoundException notOnClasspath) {
            return null;
        }
    }

    private final Map<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();

    private record Call(String stepName, String callText) {
    }

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object logRepositoryCall(ProceedingJoinPoint pjp) throws Throwable {
        // снимок ДО вызова: аргументы должны отражать то, что ОТПРАВИЛИ в БД,
        // а не мутированное состояние после вызова (напр. сгенерированный id у save)
        Call call = snapshotIfActive(pjp);
        if (call == null) {
            return pjp.proceed();
        }

        // шаг открываем ДО proceed — тогда SQL внутри вызова вложится В НЕГО
        String uuid = UUID.randomUUID().toString();
        boolean started = startStep(uuid, call.stepName());
        try {
            Object result = pjp.proceed();
            finish(started, uuid, call.callText(), formatResponse(result), Status.PASSED);
            return result;
        } catch (Throwable error) {
            // помечаем шаг BROKEN (родная семантика Allure для ошибки), но текст исключения
            // НЕ дублируем — его покажет Allure на уровне теста. «DB Call» (что ушло в БД) остаётся.
            finish(started, uuid, call.callText(), null, Status.BROKEN);
            throw error;
        } finally {
            stopQuietly(started, uuid);
        }
    }

    private Call snapshotIfActive(ProceedingJoinPoint pjp) {
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return null;
            }
            String repoName = repositoryName(pjp);
            String methodName = pjp.getSignature().getName();
            return new Call("DB " + repoName + "." + methodName,
                    formatRequest(repoName, methodName, pjp.getArgs()));
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("DbSnapshot", t); // не роняем вызов, но сбой видно на WARNING
            return null;
        }
    }

    private boolean startStep(String uuid, String name) {
        try {
            Allure.getLifecycle().startStep(uuid, new StepResult().setName(name).setStatus(Status.PASSED));
            return true;
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("DbStartStep", t);
            return false;
        }
    }

    private void finish(boolean started, String uuid, String callText, String resultText, Status status) {
        if (!started) {
            return;
        }
        try {
            // вложения и статус — на ТЕКУЩИЙ (наш) шаг; SQL уже вложился сюда же во время proceed
            Allure.addAttachment("DB Call", "text/plain", callText);
            if (resultText != null) { // при ошибке resultText == null — «DB Result» не пишем
                Allure.addAttachment("DB Result", "text/plain", resultText);
            }
            Allure.getLifecycle().updateStep(uuid, s -> s.setStatus(status));
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("DbFinishStep", t); // не роняем тест, сбой видно на WARNING
        }
    }

    private void stopQuietly(boolean started, String uuid) {
        if (!started) {
            return;
        }
        try {
            Allure.getLifecycle().stopStep(uuid);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("DbStopStep", t);
        }
    }

    private static String repositoryName(ProceedingJoinPoint pjp) {
        Class<?> target = pjp.getTarget().getClass();
        // первый прикладной интерфейс (порядок getInterfaces() не гарантирован, первым может
        // оказаться служебный org.springframework.aop.SpringProxy/Advised) — это репозиторий потребителя.
        // spring-data не в compile-classpath (pointcut — строкой), поэтому фильтруем по имени пакета.
        for (Class<?> iface : target.getInterfaces()) {
            String name = iface.getName();
            if (!name.startsWith("org.springframework.") && !name.startsWith("java.")) {
                return iface.getSimpleName();
            }
        }
        Class<?>[] ifaces = target.getInterfaces();
        return ifaces.length > 0 ? ifaces[0].getSimpleName() : target.getSimpleName();
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

    /** Не раздуваем отчёт на больших выборках. */
    private static final int ITEMS_CAP = 20;

    /**
     * Потолок ОБХОДА (не показа): дальше не считаем даже элементы. Число в отчёте — справка,
     * ради неё нельзя вычерпывать источник: он бывает ленивым и одноразовым.
     */
    private static final int COUNT_CAP = 1_000;

    private String formatResponse(Object result) {
        if (result == null) {
            return "void";
        }
        if (result instanceof Optional<?> opt) {
            return opt.map(this::describe).orElse("Optional.empty");
        }
        // ⚠️ Поток ОДНОРАЗОВЫЙ (@Query + Stream): прочитать его ради отчёта — сломать вызывающего
        // («stream has already been operated upon»). Показываем только маркер.
        if (result instanceof BaseStream<?, ?>) {
            return result.getClass().getSimpleName() + " (поток; не читаем — одноразовый)";
        }
        if (result instanceof Collection<?> col) {
            String items = col.stream().limit(ITEMS_CAP).map(this::describe).collect(Collectors.joining("\n"));
            String more = col.size() > ITEMS_CAP ? "\n… и ещё " + (col.size() - ITEMS_CAP) : "";
            return "Collection size: " + col.size() + "\n\n" + items + more;
        }
        // Page / Slice / Window / Streamable — ВСЕ они Iterable, поэтому одна ветка закрывает
        // семейство, и тянуть spring-data в compile-classpath не нужно (её там нет намеренно:
        // поинткат аспекта задан строкой). Без этой ветки Page уходил в toString и давал
        // «Page 1 of 5 containing …Widget instances» вместо самих сущностей.
        if (result instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            int total = 0;
            boolean capped = false;
            for (Object item : iterable) {
                // Обход ОГРАНИЧЕН: пересчитывать источник целиком ради строчки «всего N» нельзя.
                // Page/Slice/Window ограничены страницей, но ленивая обёртка — нет, и полный
                // проход означал бы и лишнюю работу, и риск вычерпать чужой одноразовый источник
                // (для BaseStream отдельный гейт выше, но Streamable вокруг потока им не ловится).
                if (total >= COUNT_CAP) {
                    capped = true;
                    break;
                }
                total++;
                if (items.size() < ITEMS_CAP) {
                    items.add(item);
                }
            }
            String body = items.stream().map(this::describe).collect(Collectors.joining("\n"));
            String more = total > items.size()
                    ? "\n… и ещё " + (capped ? "≥" : "") + (total - items.size()) : "";
            return paging(result, total, capped) + "\n\n" + body + more;
        }
        return describe(result);
    }

    /**
     * Заголовок для страничного результата: «Страница 1 из 3, всего: 25». Тип определяем
     * УТИНОЙ типизацией (геттеры по имени) — чтобы не тащить spring-data в compile-classpath.
     * Нет таких геттеров (обычный Iterable/Streamable) — просто размер.
     */
    private String paging(Object result, int total, boolean capped) {
        StringBuilder head = new StringBuilder("Iterable size: " + (capped ? "≥" : "") + total);
        try {
            Object number = invokeIfPresent(result, "getNumber");
            Object totalPages = invokeIfPresent(result, "getTotalPages");
            Object totalElements = invokeIfPresent(result, "getTotalElements");
            if (number != null && totalPages != null) {
                head = new StringBuilder("Страница " + (((Number) number).intValue() + 1) + " из " + totalPages);
            }
            if (totalElements != null) {
                head.append(", всего: ").append(totalElements);
            }
        } catch (Throwable notPaged) {
            // не страница — заголовка про страницы просто не будет
        }
        return head.toString();
    }

    private static Object invokeIfPresent(Object target, String getter) {
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (Throwable absent) {
            return null;
        }
    }

    private String describe(Object obj) {
        if (obj == null) {
            return "null";
        }
        Class<?> clazz = obj.getClass();
        // obj — всегда объект (примитивы заболочены), поэтому проверяем по обёрткам/типам
        if (obj instanceof Number || obj instanceof String
                || obj instanceof Boolean || obj instanceof Enum) {
            return obj.toString();
        }
        if (ENTITY_ANNOTATION != null && clazz.isAnnotationPresent(ENTITY_ANNOTATION)) {
            return describeEntity(obj, clazz);
        }
        // safeValue, а не safe: это ТЕЛО вложения «DB Call»/«DB Result» — многострочный toString
        // (агрегат, JSON-поле) там читается, а схлопывание и обрезка по 500 его бы съели.
        return AllureAdviceSupport.safeValue(obj);
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
                // Здесь safe() ОСОЗНАННО, а не safeValue: сущность печатается однострочным
                // «Widget{id=1, name=…}», и список выборки читается строка-на-сущность.
                // Многострочное значение поля разорвало бы этот формат.
                sj.add(field.getName() + "=" + AllureAdviceSupport.safe(field.get(obj)));
            } catch (Throwable e) {
                // напр. LazyInitializationException по ленивой связи — не теряем остальные поля
                sj.add(field.getName() + "=?");
            }
        }
        return sj.toString();
    }
}
