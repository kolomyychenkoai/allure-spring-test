package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import javax.sql.DataSource;

/**
 * Пул с акцессорами, которые ведут себя неудобно, — по одному на каждую ветку чтения цели
 * в {@code AllureDataSourceProxies.read}.
 * <p>
 * Все три случая живые: одноимённый метод у чужого класса вправе значить что угодно, чужой
 * акцессор вправе бросить, а обёртка вправе вернуть саму себя (так делают самописные
 * роутеры с ленивым резолвом). Ни один из них не должен ни ронять контекст, ни зацикливать
 * обход, ни заставлять нас звать метод «вдруг подойдёт».
 */
public final class AwkwardAccessorDataSource {

    private AwkwardAccessorDataSource() {
    }

    /** Акцессор с чужим типом возврата: имя совпало, смысл другой — звать его нельзя. */
    public static class ForeignReturnType extends FakeDataSource {

        private boolean called;

        public ForeignReturnType(String name) {
            super(name);
        }

        /**
         * Звали ли акцессор. Проверять надо ИМЕННО это: строку, которую он вернул бы, код
         * дальше просто игнорирует, поэтому по результату обёртки лишний вызов не виден.
         */
        public boolean wasCalled() {
            return called;
        }

        /** Не {@code DataSource} и не карта: у чужого класса это может быть что угодно. */
        public String getTargetDataSource() {
            called = true;
            return "не пул, а строка";
        }
    }

    /**
     * Акцессор, который бросает: обход обязан молча пойти дальше, но позвать его ОДИН раз.
     * <p>
     * ⚠️ Реализует {@link TargetAware} НАМЕРЕННО: кандидатов на объявление должно быть
     * несколько (свой класс и интерфейс), иначе повторный вызов не воспроизводится и страж
     * ничего не стережёт — замерено ревьюерами.
     */
    public static class ThrowingAccessor extends FakeDataSource implements TargetAware {

        private int accessorCalls;

        public ThrowingAccessor(String name) {
            super(name);
        }

        /**
         * Сколько раз обход дёрнул бросающий акцессор. Ленивый резолв редко бывает без
         * побочных эффектов, поэтому повтор — не мелочь: замерено, что при поиске «дальше
         * по кандидатам» число вызовов растёт вместе с глубиной иерархии.
         */
        public int accessorCalls() {
            return accessorCalls;
        }

        @Override
        public DataSource getTargetDataSource() {
            accessorCalls++;
            throw new IllegalStateException("резолв цели вне контекста тенанта");
        }
    }

    /**
     * Акцессор, объявленный в публичном ИНТЕРФЕЙСЕ, а класс обёртки — непубличный. Без обхода
     * интерфейсов объявление не находится нигде, вызов падает {@code IllegalAccessException},
     * защита от задвоения молча выключается — и запрос попадает в отчёт дважды.
     */
    public interface TargetAware {
        DataSource getTargetDataSource();
    }

    /**
     * Непубличный класс: объявление акцессора доступно ТОЛЬКО через интерфейс.
     * ⚠️ НЕ {@code final}: иначе обёртка отбрасывает его предпроверкой, возвращает как есть,
     * и тест про интерфейсы становится зелёным по неверной причине — поймано мутацией.
     */
    static class HiddenImpl extends FakeDataSource implements TargetAware {

        private final DataSource target;

        HiddenImpl(DataSource target) {
            super("непубличная обёртка");
            this.target = target;
        }

        @Override
        public DataSource getTargetDataSource() {
            return target;
        }
    }

    /** Непубличная обёртка над переданной целью: сам класс наружу не отдаём. */
    public static DataSource hiddenWrapperOver(DataSource target) {
        return new HiddenImpl(target);
    }

    /** Роутер, отдающий карту целей с {@code null}: у чужой реализации карта произвольна. */
    public static class NullInTargetsMap extends FakeDataSource {

        private final java.util.Map<Object, DataSource> targets = new java.util.HashMap<>();

        public NullInTargetsMap(String name, DataSource resolved) {
            super(name);
            targets.put("нерезолвленный тенант", null);
            targets.put("резолвленный", resolved);
        }

        public java.util.Map<Object, DataSource> getResolvedDataSources() {
            return targets;
        }
    }

    /** Обёртка, ссылающаяся сама на себя: обход не имеет права зациклиться. */
    public static class SelfReferencing extends FakeDataSource {

        private int accessorCalls;

        public SelfReferencing(String name) {
            super(name);
        }

        /**
         * Сколько раз обход спросил цель. Проверять надо ИМЕННО это: таймер сторожит только
         * «не бесконечно», и предел мог бы вырасти в тысячи раз, оставаясь в бюджете —
         * замерено, при пределе в миллион обход укладывался в пять секунд.
         */
        public int accessorCalls() {
            return accessorCalls;
        }

        public DataSource getTargetDataSource() {
            accessorCalls++;
            return this;
        }
    }
}
