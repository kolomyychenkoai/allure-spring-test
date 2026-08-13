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

    /** Акцессор, который бросает: обход обязан молча пойти дальше. */
    public static class ThrowingAccessor extends FakeDataSource {

        public ThrowingAccessor(String name) {
            super(name);
        }

        public DataSource getTargetDataSource() {
            throw new IllegalStateException("резолв цели вне контекста тенанта");
        }
    }

    /** Обёртка, ссылающаяся сама на себя: обход не имеет права зациклиться. */
    public static class SelfReferencing extends FakeDataSource {

        public SelfReferencing(String name) {
            super(name);
        }

        public DataSource getTargetDataSource() {
            return this;
        }
    }
}
