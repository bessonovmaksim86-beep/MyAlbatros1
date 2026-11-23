package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.company.production.config.OneCProperties;
import ru.company.production.dto.OneCZpRow;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Чтение заказов из 1С через COM-соединение (V83.COMConnector).
 *
 * Прямой доступ к СУБД PostgreSQL закрыт (порт 5432 сервер 1С держит
 * только для localhost), поэтому обмен идёт по COM — тем же путём,
 * которым пользуется толстый клиент. Вызов COM из Java требует
 * нативных мостов, а платформа 1С уже ставит объект {@code V83.COMConnector};
 * надёжнее всего обратиться к нему через PowerShell, который есть
 * на каждой рабочей машине Windows.
 *
 * Как это устроено:
 *   1) запрос на языке 1С и строка соединения пишутся во временный
 *      скрипт {@code .ps1} в кодировке UTF-8 с BOM;
 *   2) PowerShell выполняет скрипт и печатает строки выборки в stdout;
 *   3) Java читает stdout как UTF-8 и собирает {@link OneCZpRow}.
 *
 * UTF-8 с BOM — не придирка: кириллический текст запроса и русские
 * наименования контрагентов иначе зависят от кодовой страницы консоли.
 * Тот же случай, из-за которого в {@code pom.xml} путь к агенту
 * покрытия вынесен за пределы кириллического пути: cmd.exe терял
 * не-ASCII символы. Здесь источник тот же — консоль Windows.
 *
 * Соединение живёт ровно одну команду PowerShell: процесс завершился —
 * соединения нет. Приложение не держит канал к 1С между запросами и не
 * создаёт фоновой нагрузки на сервер.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OneCComClient {

    /** 1С выключена в настройках — обращения не было. */
    public static final int STATUS_DISABLED = 0;

    /** COM-канал жив, но выборка вернула ноль строк. */
    public static final int STATUS_EMPTY = 1;

    /** COM-канал жив, данные получены. */
    public static final int STATUS_OK = 2;

    /** Ошибка соединения или запроса: 1С недоступна. */
    public static final int STATUS_ERROR = 3;

    /** Разделитель колонок в строке вывода — табуляция. */
    private static final char FIELD_SEPARATOR = '\t';

    /** Маркер строки данных в выводе скрипта. */
    private static final String ROW_PREFIX = "ROW\t";

    /** Маркер успешного завершения скрипта. */
    private static final String DONE_MARKER = "DONE";

    /** Маркер ошибки с текстом после двоеточия. */
    private static final String ERROR_PREFIX = "ERR\t";

    /**
     * Формат даты, который передаёт запрос 1С (строкой), и который
     * читает клиент. ISO убран от случайных разделителей времени.
     */
    private static final DateTimeFormatter ONEC_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final OneCProperties oneCProperties;

    /**
     * Выполняет запрос к 1С и возвращает строки с кодом состояния.
     *
     * @return результат; пустой список при выключенном канале или ошибке
     */
    public OneCComResult loadZp() {
        if (!oneCProperties.isConfigured()) {
            return new OneCComResult(STATUS_DISABLED, List.of());
        }

        Path script = null;

        try {
            script = writeScript();

            List<String> output = runPowerShell(script);

            return parseOutput(output);
        } catch (Exception ex) {
            log.error("Сбой обращения к 1С через COM: {}", ex.getMessage());
            return new OneCComResult(STATUS_ERROR, List.of());
        } finally {
            deleteQuietly(script);
        }
    }

    /**
     * Пишет временный PowerShell-скрипт в UTF-8 с BOM.
     *
     * BOM обязателен: без него powershell.exe читает файл в кодовой
     * странице системы (на русской Windows — CP1251), и кириллица
     * запроса превращается в мусор ещё до передачи в 1С.
     */
    private Path writeScript() throws Exception {
        String body = """
                $ErrorActionPreference = 'Stop'
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                $OutputEncoding = [System.Text.Encoding]::UTF8
                try {
                    $connector = New-Object -ComObject V83.COMConnector
                    $connection = $connector.Connect(%CONNECT%)
                    $query = $connection.NewObject('Query', %QUERY%)
                    $result = $query.Execute()
                    $selection = $result.Select()
                    while ($selection.Next()) {
                        $number = [string]$selection.Value('Number')
                        $date = [string]$selection.Value('Date')
                        $customerOrder = [string]$selection.Value('CustomerOrder')
                        $customer = [string]$selection.Value('Customer')
                        $status = [string]$selection.Value('Status')
                        $line = $number + "`t" + $date + "`t" + $customerOrder + "`t" + $customer + "`t" + $status
                        Write-Output ('ROW' + "`t" + $line)
                    }
                    Write-Output 'DONE'
                } catch {
                    $message = $_.Exception.Message -replace "[`r`n`t]", ' '
                    Write-Output ('ERR' + "`t" + $message)
                }
                """
                .replace("%CONNECT%", powershellString(oneCProperties.connectString()))
                .replace("%QUERY%", powershellString(oneCProperties.resolvedQuery()));

        Path script = Files.createTempFile("onec-", ".ps1");

        try (var out = Files.newOutputStream(script)) {
            // UTF-8 BOM: EF BB BF.
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }

        return script;
    }

    /**
     * Запускает powershell.exe и собирает его stdout как UTF-8.
     *
     * Флаг {@code -NoProfile} убирает загрузку пользовательского
     * профиля: он может печатать свои строки и менять кодировку.
     */
    private List<String> runPowerShell(Path script) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", script.toAbsolutePath().toString()
        );
        builder.redirectErrorStream(true);

        Process process = builder.start();

        List<String> lines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        boolean finished = process.waitFor(
                oneCProperties.getTimeoutSeconds(), TimeUnit.SECONDS
        );

        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "1С не ответила за " + oneCProperties.getTimeoutSeconds() + " с"
            );
        }

        return lines;
    }

    /**
     * Разбирает вывод скрипта: строки данных, признак конца либо ошибку.
     *
     * Ошибкой считается и отсутствие маркера DONE: значит скрипт упал
     * до завершения (COM-объект не создан, соединение не открылось), и
     * молча вернуть пустоту было бы неверно.
     */
    private OneCComResult parseOutput(List<String> output) {
        List<OneCZpRow> rows = new ArrayList<>();
        boolean done = false;

        for (String line : output) {
            if (line.startsWith(ERROR_PREFIX)) {
                String message = line.substring(ERROR_PREFIX.length()).trim();
                log.error("1С вернула ошибку: {}", message);
                return new OneCComResult(STATUS_ERROR, List.of());
            }

            if (DONE_MARKER.equals(line.trim())) {
                done = true;
                continue;
            }

            if (line.startsWith(ROW_PREFIX)) {
                OneCZpRow row = mapRow(line.substring(ROW_PREFIX.length()));

                if (rows.size() < oneCProperties.getMaxRows()) {
                    rows.add(row);
                }
            }
        }

        if (!done) {
            log.error("Обмен с 1С прервался без подтверждения завершения");
            return new OneCComResult(STATUS_ERROR, List.of());
        }

        return new OneCComResult(
                rows.isEmpty() ? STATUS_EMPTY : STATUS_OK,
                rows
        );
    }

    /**
     * Разбор строки «номер, дата, заказ покупателя, клиент, статус».
     *
     * Дата приходит из 1С в различном виде (с временем либо без);
     * нечитаемая дата строку не отбрасывает — заказ остаётся в выдаче.
     */
    private OneCZpRow mapRow(String line) {
        String[] parts = splitRow(line);

        return new OneCZpRow(
                part(parts, 0),
                parseDate(part(parts, 1)),
                part(parts, 2),
                part(parts, 3),
                part(parts, 4)
        );
    }

    /** Разделитель — табуляция; пустые поля сохраняют позицию. */
    private static String[] splitRow(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char symbol = line.charAt(i);

            if (symbol == FIELD_SEPARATOR) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(symbol);
            }
        }

        fields.add(current.toString());

        return fields.toArray(new String[0]);
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index].trim() : "";
    }

    /** Дата из 1С: берёт дату из строки «2026-01-31...», иначе null. */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String text = value.trim();

        // Отрезаем время, если 1С отдала «2026-01-31 10:20:00».
        int space = text.indexOf(' ');
        if (space > 0) {
            text = text.substring(0, space);
        }

        try {
            return LocalDate.parse(text, ONEC_DATE);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Литерал строки PowerShell: одинарные кавычки с удвоением внутренних. */
    private static String powershellString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void deleteQuietly(Path script) {
        if (script == null) {
            return;
        }

        try {
            Files.deleteIfExists(script);
        } catch (Exception ignored) {
            // временный файл переживёт запуск — не повод ронять обмен
        }
    }

    /**
     * Результат обращения к 1С.
     *
     * @param status один из {@code STATUS_*}
     * @param rows   строки выборки; пусты при любом статусе, кроме OK
     */
    public record OneCComResult(int status, List<OneCZpRow> rows) {

        public boolean isOk() {
            return status == STATUS_OK;
        }

        public boolean isEmpty() {
            return status == STATUS_EMPTY;
        }

        public boolean isError() {
            return status == STATUS_ERROR;
        }

        public boolean isDisabled() {
            return status == STATUS_DISABLED;
        }
    }
}
