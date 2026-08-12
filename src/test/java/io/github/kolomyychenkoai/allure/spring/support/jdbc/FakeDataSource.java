package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * Пул-пустышка для проверок обёртки {@code AllureDataSourceProxies}.
 * <p>
 * Настоящий класс, а не мок: мок сам сгенерирован байткодом, и проксировать его — значит
 * проверять поведение генератора моков, а не наше. По той же причине {@link #name} читается
 * ПОЛЕМ: у подкласса, созданного без конструктора, поле пусто, поэтому {@link #name()}
 * отвечает верно только если вызов дошёл до настоящего объекта.
 */
public class FakeDataSource extends AbstractDataSource {

    /** Соединение-пустышка: важна не его работа, а то, вернули его или обёртку над ним. */
    private final Connection connection = (Connection) Proxy.newProxyInstance(
            FakeDataSource.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "toString" -> "соединение-пустышка";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> null;
            });

    private final String name;

    public FakeDataSource(String name) {
        this.name = name;
    }

    /** Имя пула. Читает поле — тем и годится в проверку «вызов дошёл до настоящего объекта». */
    public String name() {
        return name;
    }

    /** Соединение, которое отдаёт этот пул: с ним сравнивают то, что вернул прокси. */
    public Connection rawConnection() {
        return connection;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) {
        return connection;
    }
}
