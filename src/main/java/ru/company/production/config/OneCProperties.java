package ru.company.production.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Параметры подключения к 1С через COM-соединение (V83.COMConnector).
 *
 * Прямое подключение к СУБД PostgreSQL, на которой стоит база, закрыто:
 * сервер 1С держит порт 5432 только для localhost. Поэтому обмен идёт
 * через сервер 1С по COM — тем же сетевым путём, которым пользуется
 * толстый клиент. Для такого способа пароль не нужен: вход выполняется
 * под текущим пользователем Windows, ровно как при запуске 1С.
 *
 * Обмен выключен по умолчанию. COM-объект {@code V83.COMConnector}
 * существует только на машине, где установлена платформа 1С, — попытка
 * соединиться там, где её нет, дала бы ошибку на каждом запуске. На
 * рабочем месте с 1С обмен включают переменной окружения без правки
 * файла.
 *
 * Источник данных здесь НЕ объявляется бином Spring: появление
 * собственного {@code DataSource} отключило бы
 * {@code DataSourceAutoConfiguration}, и основная база приложения
 * (MySQL) перестала бы создаваться вовсе.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "application.onec")
public class OneCProperties {

    /** Разрешает обращение к 1С из раздела интеграции. */
    private boolean enabled = false;

    /** Имя сервера 1С из окна «О программе» (строка «Сервер»). */
    private String server = "app1";

    /** Имя базы 1С из окна «О программе» (строка «Имя базы»). */
    private String database = "albatros";

    /**
     * Пользователь для строки соединения.
     *
     * Пусто — вход под текущим пользователем Windows (тот же режим,
     * что у толстого клиента без пароля). Заполняют только если база
     * требует явную учётную запись 1С.
     */
    private String username = "";

    /** Пароль пользователя; заполняется лишь вместе с {@link #username}. */
    private String password = "";

    /**
     * Запрос на языке 1С, возвращающий заказы на производство.
     *
     * Требуется ровно пять колонок с фиксированными латинскими
     * псевдонимами: {@code Number}, {@code Date},
     * {@code CustomerOrder}, {@code Customer}, {@code Status}.
     *
     * Псевдонимы держатся на латинице намеренно: выборку COM читает по
     * имени поля, а кириллическое имя при передаче через PowerShell
     * зависит от кодовой страницы консоли и в разных сборках может не
     * совпасть. Русские названия полей при этом остаются в тексте
     * запроса — обращаются они к метаданным, а читаются через
     * псевдоним.
     *
     * Пустое значение — выполняется проверочный запрос без обращения
     * к таблицам: он подтверждает, что COM-канал жив, но не отдаёт
     * реальные заказы. Настоящий запрос задают, сверив имена метаданных
     * в конфигураторе.
     */
    private String query1c = "";

    /** Ограничение числа строк: защита от выборки всей базы. */
    private int maxRows = 500;

    /** Сколько секунд ждать ответа 1С; по истечении процесс прерывается. */
    private int timeoutSeconds = 60;

    /** Готовно ли соединение: включено и заданы сервер с базой. */
    public boolean isConfigured() {
        return enabled
                && notBlank(server)
                && notBlank(database);
    }

    /**
     * Строка соединения для {@code V83.COMConnector.Connect(...)}.
     *
     * Формат: {@code Srvr="сервер";Ref="база";}. Учётная запись
     * добавляется только когда она задана явно — иначе 1С берёт
     * текущего пользователя Windows.
     */
    public String connectString() {
        StringBuilder value = new StringBuilder("Srvr=")
                .append(quote(server))
                .append(";Ref=")
                .append(quote(database))
                .append(';');

        if (notBlank(username)) {
            value.append("Usr=").append(quote(username)).append(';');
            value.append("Pwd=").append(quote(password)).append(';');
        }

        return value.toString();
    }

    /**
     * Текст запроса к 1С: настроенный либо проверочный по умолчанию.
     *
     * Проверочный запрос не читает таблицы конфигурации, поэтому
     * выполняется в любой базе и служит тестом канала, а не выгрузкой.
     */
    public String resolvedQuery() {
        if (notBlank(query1c)) {
            return query1c;
        }

        return """
                ВЫБРАТЬ
                	"1С: COM-канал работает" КАК Number,
                	"2026-01-01" КАК Date,
                	"" КАК CustomerOrder,
                	"" КАК Customer,
                	"проверка соединения" КАК Status
                """;
    }

    /** Значение в двойных кавычках с удвоением внутренних — формат 1С. */
    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
