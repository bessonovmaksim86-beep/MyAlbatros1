package ru.company.production.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Настройки печати этикеток через BarTender.
 *
 * BarTender управляет печатью сам: приложение лишь готовит файл данных
 * с номерами партии и запускает {@code bartend.exe} с параметрами
 * {@code /F=шаблон /D=данные /P /X}. Поэтому здесь нет ни шрифтов, ни
 * размеров этикетки — всё это живёт в файле {@code .btw}, который
 * готовит технолог. Приложение обязано передать только номера.
 *
 * По умолчанию печать выключена: на машине разработчика BarTender
 * обычно нет, а включать вызов внешнего процесса «потому что он есть в
 * PATH» нельзя — оператор получил бы ошибку вместо этикеток.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "application.label-printing")
public class LabelPrintProperties {

    /** Разрешает запуск BarTender из интерфейса. */
    private boolean enabled = false;

    /** Полный путь к bartend.exe. */
    private String bartenderPath = "";

    /** Полный путь к шаблону этикетки .btw. */
    private String templatePath = "";

    /** Имя принтера этикеток; пусто — принтер, указанный в шаблоне. */
    private String printer = "";

    /** Куда писать файлы данных; пусто — системный временный каталог. */
    private String dataDir = "";

    /** Разделитель полей файла данных: tab, comma или semicolon. */
    private String delimiter = "tab";

    /** Кодировка файла данных: UTF-8 или UTF-16LE. */
    private String encoding = "UTF-8";

    /** Писать строку имён полей первой строкой файла. */
    private boolean header = true;

    /** Этикеток на один номер (параметр /C BarTender). */
    private int copies = 1;

    /** Сколько секунд ждать завершения BarTender. */
    private int timeoutSeconds = 60;

    /**
     * Адрес агента печати на рабочем месте, например
     * {@code http://127.0.0.1:7788}.
     *
     * Агент нужен там, где приложение работает на сервере, а BarTender
     * и принтер этикеток стоят на компьютере кладовщика: сервер не
     * может запустить программу на чужой машине. Пусто — печать идёт
     * серверным BarTender, как раньше.
     */
    private String agentUrl = "";

    /** Токен агента; пусто — агент не проверяет заголовок X-Print-Token. */
    private String agentToken = "";

    /** true — печать выполняет агент на рабочем месте, а не сервер. */
    public boolean hasAgent() {
        return agentUrl != null && !agentUrl.isBlank();
    }

    /** Адрес агента без завершающей косой черты. */
    public String agentBaseUrl() {
        if (!hasAgent()) {
            return "";
        }

        String trimmed = agentUrl.trim();

        return trimmed.endsWith("/")
                ? trimmed.substring(0, trimmed.length() - 1)
                : trimmed;
    }

    /**
     * Разделитель полей в виде символа файла данных.
     *
     * Значение обязано совпадать с разделителем, настроенным в
     * импорте текста внутри шаблона {@code .btw}: BarTender читает его
     * из самого файла формата, а не из командной строки.
     */
    public String delimiterSequence() {
        return switch (delimiter == null ? "tab" : delimiter.trim().toLowerCase()) {
            case "comma", ",", ";" -> ",";
            case "semicolon", "semi" -> ";";
            default -> "\t";
        };
    }

    /** true — файл данных пишется в два байта (UTF-16LE). */
    public boolean isUnicodeDataFile() {
        return encoding != null
                && encoding.trim().toUpperCase().startsWith("UTF-16");
    }

    /** Каталог файлов данных: заданный или системный временный. */
    public Path resolveDataDir() {
        return dataDir == null || dataDir.isBlank()
                ? Paths.get(System.getProperty("java.io.tmpdir"))
                : Paths.get(dataDir.trim());
    }

    public boolean isBartenderConfigured() {
        return bartenderPath != null
                && !bartenderPath.isBlank()
                && Files.isRegularFile(Paths.get(bartenderPath.trim()));
    }

    public boolean isTemplateConfigured() {
        return templatePath != null
                && !templatePath.isBlank()
                && Files.isRegularFile(Paths.get(templatePath.trim()));
    }

    /** Путь к bartend.exe без кавычек — кавычки подставит ProcessBuilder. */
    public File bartenderExecutable() {
        return Paths.get(bartenderPath.trim()).toFile();
    }

    /** Путь к шаблону: BarTender принимает и {@code lib://} пути репозитория. */
    public String templateArgument() {
        return templatePath.trim();
    }

    public String printerName() {
        return printer == null ? "" : printer.trim();
    }
}
