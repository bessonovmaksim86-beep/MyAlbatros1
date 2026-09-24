package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.company.production.config.LabelPrintProperties;
import ru.company.production.dto.LabelPrintResult;
import ru.company.production.entity.CellReceivingBatch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Печать этикеток партии через BarTender.
 *
 * BarTender не умеет принимать данные по протоколу приложения — он
 * читает их из файла, который подключён к шаблону {@code .btw} как
 * источник. Поэтому шаг состоит из трёх действий:
 *
 *   1. номера партии записываются в текстовый файл формата, который
 *      ожидает импорт внутри шаблона (таблица или CSV, одна строка —
 *      одна этикетка);
 *   2. запускается {@code bartend.exe /F=шаблон /D=данные /P /X};
 *   3. дожидаемся завершения процесса: BarTender с {@code /X} выходит
 *      после постановки задания в очередь принтера, и только по коду
 *      возврата можно честно сказать оператору, что печать состоялась.
 *
 * Барьер, а не «выстрелил и забыл»: партия помечается напечатанной
 * только когда задание реально ушло. Иначе оператор перешёл бы к
 * сканированию по этикеткам, которых нет, и расхождение обнаружилось
 * бы уже на складе.
 *
 * Вызов внешнего процесса возможен лишь там, где BarTender установлен,
 * поэтому печать выключена по умолчанию и включается настройкой
 * {@code application.label-printing.enabled}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelPrintService {

    /* Имена полей файла данных. Шаблон .btw обязан использовать их же. */
    private static final String FIELD_NUMBER = "NUMBER";
    private static final String FIELD_INDEX = "INDEX";
    private static final String FIELD_CLASSIFIER = "CLASSIFIER";
    private static final String FIELD_NAME = "NAME";
    private static final String FIELD_ORDER = "ORDER";

    private static final Charset UTF_16LE = StandardCharsets.UTF_16LE;

    private final LabelPrintProperties properties;

    /**
     * Печатает этикетки партии.
     *
     * Ошибки не бросаются наружу: страница шага QR должна остаться
     * перед оператором с его же номерами, а не превратиться в страницу
     * ошибки Spring. Результат несёт готовую надпись, объясняющую, что
     * именно не так — путь до принтера оператор не найдёт сам.
     */
    public LabelPrintResult printBatch(CellReceivingBatch batch) {

        List<String> numbers = batch.fullNumbers();

        if (numbers.isEmpty()) {
            return LabelPrintResult.failed(
                    "В партии нет номеров — печатать нечего"
            );
        }

        String problem = configurationProblem();

        if (problem != null) {
            return LabelPrintResult.failed(problem);
        }

        Path dataFile = null;

        try {
            dataFile = writeDataFile(batch, numbers);

            int exitCode = runBartender(dataFile);

            if (exitCode != 0) {
                return LabelPrintResult.failed(
                        "BarTender вернул код " + exitCode
                                + " — задание печати не создано. "
                                + "Проверьте шаблон, принтер и права на каталог "
                                + "файлов данных."
                );
            }

            int labels = numbers.size() * Math.max(1, properties.getCopies());

            return LabelPrintResult.printed(
                    labels,
                    "Печать отправлена в BarTender: " + labels
                            + " этикеток по номерам " + batch.getNumberRangeLabel()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            return LabelPrintResult.failed(
                    "Ожидание печати прервано — проверьте, вышел ли лист"
            );
        } catch (IOException exception) {
            log.error("Не удалось подготовить печать этикеток партии {}",
                    batch.getId(), exception);

            return LabelPrintResult.failed(
                    "Не удалось подготовить печать: " + exception.getMessage()
            );
        } finally {
            deleteQuietly(dataFile);
        }
    }

    /**
     * Готовность печати и причина неготовности одним выражением.
     *
     * Текст возвращает не «ошибка конфигурации», а конкретную настройку:
     * кладовщик не найдёт путь к bartend.exe сам, и подсказка обязана
     * называть параметр, который администратору править в yml.
     */
    public String configurationProblem() {
        if (!properties.isEnabled()) {
            return "Печать через BarTender не включена на этом рабочем месте "
                    + "(application.label-printing.enabled = false). "
                    + "Скопируйте номера и напечатайте их вручную.";
        }

        if (!properties.isBartenderConfigured()) {
            return "BarTender не найден: проверьте путь в "
                    + "application.label-printing.bartender-path";
        }

        if (!properties.isTemplateConfigured()) {
            return "Шаблон этикетки не найден: проверьте путь в "
                    + "application.label-printing.template-path";
        }

        return null;
    }

    /** Каталог печати доступен — проверка для подсказки на странице. */
    public boolean isReady() {
        return configurationProblem() == null;
    }

    /**
     * Файл данных BarTender: строка заголовка и по строке на номер.
     *
     * Файл пишется в отдельный каталог и удаляется сразу после печати:
     * в нём реквизиты прихода, и накапливать их в временной папке
     * незачем. Имя содержит id партии, чтобы два оператора, печатающие
     * одновременно, не перезаписали файл друг друга.
     */
    private Path writeDataFile(CellReceivingBatch batch,
                               List<String> numbers) throws IOException {

        Path directory = properties.resolveDataDir();

        Files.createDirectories(directory);

        String delimiter = properties.delimiterSequence();
        Charset charset = properties.isUnicodeDataFile() ? UTF_16LE : StandardCharsets.UTF_8;

        Path file = directory.resolve(
                "labels-batch-" + batch.getId() + "-" + System.nanoTime()
                        + ".txt"
        );

        String classifier = batch.getCellClassifier() == null
                ? ""
                : String.valueOf(batch.getCellClassifier().getCode());
        String name = batch.getCellClassifier() == null
                ? ""
                : safe(batch.getCellClassifier().getDisplayName());

        try (BufferedWriter writer = Files.newBufferedWriter(file, charset)) {

            if (properties.isHeader()) {
                writer.write(String.join(
                        delimiter,
                        FIELD_NUMBER,
                        FIELD_INDEX,
                        FIELD_CLASSIFIER,
                        FIELD_NAME,
                        FIELD_ORDER
                ));
                writer.write("\r\n");
            }

            int index = 1;

            for (String number : numbers) {
                writer.write(String.join(
                        delimiter,
                        number,
                        String.valueOf(index++),
                        classifier,
                        name,
                        safe(batch.getIncomingOrderNumber())
                ));
                /* BarTender читает переводы строки только Windows-стиля. */
                writer.write("\r\n");
            }
        }

        restrictToOwner(file);

        return file;
    }

    /**
     * Командная строка BarTender.
     *
     * {@code /F} — шаблон, {@code /D} — файл данных, {@code /P} —
     * печатать без показа диалога, {@code /X} — закрыть BarTender после
     * задания. {@code /PRN} передаётся только когда принтер задан:
     * иначе печатается то, что выбрано внутри шаблона.
     */
    private int runBartender(Path dataFile) throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();

        command.add(properties.bartenderExecutable().getAbsolutePath());
        command.add("/F=" + properties.templateArgument());
        command.add("/D=" + dataFile.toAbsolutePath());
        command.add("/P");
        command.add("/X");

        if (properties.getCopies() > 1) {
            command.add("/C=" + properties.getCopies());
        }

        if (!properties.printerName().isEmpty()) {
            command.add("/PRN=" + properties.printerName());
        }

        ProcessBuilder builder = new ProcessBuilder(command);

        builder.redirectErrorStream(true);

        /*
         * Вывод BarTender не показывается оператору — он не разберётся
         * в логе интеграции, — но он обязателен в журнале приложения:
         * без него сбой печати расследовать нечем.
         */
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        log.info("Запуск BarTender для печати этикеток: {}", command);

        Process process = builder.start();

        if (!process.waitFor(Math.max(5, properties.getTimeoutSeconds()),
                TimeUnit.SECONDS)) {
            process.destroyForcibly();

            throw new IOException(
                    "BarTender не завершился за "
                            + Math.max(5, properties.getTimeoutSeconds())
                            + " с — печать снята, чтобы не дублировать этикетки"
            );
        }

        return process.exitValue();
    }

    /*
     * Значения не должны содержать разделитель и переводы строки:
     * одна лишняя строка сдвинула бы всю партию в импорте BarTender.
     *
     * Разделитель берётся из настройки, а не задан списком «\t,;»:
     * если в шаблоне настроена запятая, а из наименования вырезается
     * только табуляция, запятая в названии разъедает строку импорта
     * пополам и этикетки печатаются со сдвинутыми полями.
     */
    private String safe(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace(properties.delimiterSequence(), " ");

        return cleaned.trim();
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Не удалён файл данных этикеток {}", file, exception);
        }
    }

    /*
     * Windows прав на POSIX не знает — там вызов просто ничего не делает,
     * на Linux без него файл реквизитов стал бы доступен всем в /tmp.
     */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(
                    file,
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE
                    )
            );
        } catch (UnsupportedOperationException | IOException ignored) {
            /* Файловая система без POSIX (Windows/NTFS) — права даёт ACL. */
        }
    }
}