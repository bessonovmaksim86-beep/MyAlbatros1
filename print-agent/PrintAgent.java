import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Агент печати этикеток для рабочего места кладовщика.
 *
 * Приложение работает на сервере и запустить BarTender на компьютере
 * пользователя не может: браузер не даёт веб-странице запускать
 * программы, а сервер не видит ни установленный у кладовщика BarTender,
 * ни его принтер. Поэтому на рабочем месте ставят этот агент: страница
 * обращается к нему по адресу 127.0.0.1, а он уже запускает локальный
 * bartend.exe с теми же параметрами, что и серверная печать.
 *
 * Слушает только петлевой интерфейс: из локальной сети агент недоступен,
 * и запустить печать на чужой машине через него нельзя.
 *
 * Сборка и запуск — см. README.md рядом с этим файлом.
 */
public final class PrintAgent {

    /** Порт по умолчанию; меняется строкой port= в agent.properties. */
    private static final int DEFAULT_PORT = 7788;

    /** Сколько секунд ждать завершения BarTender. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** Имена полей файла данных — те же, что у серверной печати. */
    private static final String FIELD_NUMBER = "NUMBER";
    private static final String FIELD_INDEX = "INDEX";
    private static final String FIELD_CLASSIFIER = "CLASSIFIER";
    private static final String FIELD_NAME = "NAME";
    private static final String FIELD_ORDER = "ORDER";
    private static final String FIELD_SHOW_TEXT = "SHOWTEXT";

    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Настройки, прочитанные из agent.properties. */
    private record Config(
            int port,
            String bartenderPath,
            String templatePath,
            String printer,
            String dataDir,
            String delimiter,
            boolean utf16,
            boolean header,
            int copies,
            int timeoutSeconds,
            String token
    ) {
    }

    private static final Object PRINT_LOCK = new Object();

    private static Path logFile;

    public static void main(String[] args) throws Exception {

        Path baseDir = baseDirectory();

        logFile = baseDir.resolve("print-agent.log");

        Config config = readConfig(baseDir.resolve("agent.properties"));

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port()),
                0
        );

        server.createContext("/health", exchange -> {
            try {
                handleHealth(exchange, config);
            } catch (RuntimeException exception) {
                log("Ошибка /health: " + exception);
                respond(exchange, 500, "application/json; charset=UTF-8",
                        "{\"ok\":false,\"message\":\"" + escape(exception.toString()) + "\"}");
            }
        });

        server.createContext("/print", exchange -> {
            try {
                handlePrint(exchange, config);
            } catch (RuntimeException exception) {
                log("Ошибка /print: " + exception);
                respond(exchange, 500, "application/json; charset=UTF-8",
                        "{\"ok\":false,\"message\":\"" + escape(exception.toString()) + "\"}");
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        log("Агент печати запущен на 127.0.0.1:" + config.port());
        log("BarTender: " + describe(config.bartenderPath()));
        log("Шаблон: " + describe(config.templatePath()));
        log("Принтер: " + (config.printer().isBlank()
                ? "из шаблона"
                : config.printer()));
        log("Готовность: " + (configurationProblem(config) == null
                ? "настроен"
                : "нет: " + configurationProblem(config)));
    }

    /**
     * Отвечает на проверку готовности.
     *
     * Страница спрашивает этот адрес до включения кнопки, поэтому в
     * ответе не только «да/нет», но и причина: администратор рабочего
     * места должен видеть, чего не хватает, без чтения логов.
     */
    private static void handleHealth(HttpExchange exchange, Config config)
            throws IOException {

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 204, null, "");
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"message\":\"Метод не поддерживается\"}");
            return;
        }

        String problem = configurationProblem(config);

        String body = "{\"ok\":" + (problem == null)
                + ",\"ready\":" + (problem == null)
                + ",\"bartender\":\"" + escape(config.bartenderPath()) + "\""
                + ",\"template\":\"" + escape(config.templatePath()) + "\""
                + ",\"message\":\"" + escape(problem == null ? "" : problem) + "\"}";

        respond(exchange, 200, "application/json; charset=UTF-8", body);
    }

    /**
     * Печатает этикетки партии.
     *
     * Тело запроса — простой текст «ключ=значение» по строке. Формат
     * выбран намеренно: JSON пришлось бы разбирать самому, без
     * библиотек, и ошибка разбора на кириллице в наименовании изделия
     * испортила бы этикетку молча. Построчный текст разбирается
     * однозначно.
     */
    private static void handlePrint(HttpExchange exchange, Config config)
            throws IOException {

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 204, null, "");
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"message\":\"Метод не поддерживается\"}");
            return;
        }

        if (!config.token().isBlank()) {
            String given = exchange.getRequestHeaders().getFirst("X-Print-Token");

            if (given == null || !given.equals(config.token())) {
                respond(exchange, 403, "application/json; charset=UTF-8",
                        "{\"ok\":false,\"message\":\"Неверный токен печати\"}");
                return;
            }
        }

        String problem = configurationProblem(config);

        if (problem != null) {
            respond(exchange, 200, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"message\":\"" + escape(problem) + "\"}");
            return;
        }

        Map<String, List<String>> fields = readBody(exchange);

        List<String> numbers = fields.getOrDefault("number", List.of());

        if (numbers.isEmpty()) {
            respond(exchange, 200, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"message\":\"В запросе нет номеров\"}");
            return;
        }

        int copies = Math.max(1, intValue(fields, "copies", config.copies()));
        boolean showText = boolValue(fields, "showText", false);

        String classifier = first(fields, "classifier");
        String name = first(fields, "name");
        String order = first(fields, "order");
        String batchId = first(fields, "batchId");

        Path dataFile = null;

        try {
            dataFile = writeDataFile(
                    config, batchId, numbers, classifier, name, order, showText
            );

            int exitCode = runBartender(config, dataFile);

            if (exitCode != 0) {
                String message = "BarTender вернул код " + exitCode
                        + " — задание печати не создано. "
                        + "Проверьте шаблон, принтер и права на каталог данных.";

                log(message);

                respond(exchange, 200, "application/json; charset=UTF-8",
                        "{\"ok\":false,\"message\":\"" + escape(message) + "\"}");
                return;
            }

            int labels = numbers.size() * copies;

            log("Напечатано этикеток: " + labels
                    + " (партия " + batchId + ", номера "
                    + numbers.get(0) + ".." + numbers.get(numbers.size() - 1) + ")");

            respond(exchange, 200, "application/json; charset=UTF-8",
                    "{\"ok\":true,\"printed\":" + labels + "}");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            respond(exchange, 200, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"message\":\"Ожидание печати прервано\"}");
        } catch (IOException exception) {
            log("Не удалось напечатать: " + exception);

            respond(exchange, 200, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"message\":\"Не удалось напечатать: "
                            + escape(exception.getMessage()) + "\"}");
        } finally {
            deleteQuietly(dataFile);
        }
    }

    /**
     * Готовность агента к печати и причина неготовности.
     *
     * Проверяется наличие файлов, а не только заполненность настроек:
     * путь может быть указан верно, но BarTender обновили и каталог
     * называется иначе. Тогда оператор увидит причину на странице,
     * а не пустое место после нажатия кнопки.
     */
    private static String configurationProblem(Config config) {

        if (config.bartenderPath().isBlank()) {
            return "Не задан путь к bartend.exe в agent.properties (bartender.path)";
        }

        if (!Files.isRegularFile(Paths.get(config.bartenderPath()))) {
            return "BarTender не найден по пути «" + config.bartenderPath()
                    + "» — проверьте bartender.path в agent.properties";
        }

        if (config.templatePath().isBlank()) {
            return "Не задан путь к шаблону этикетки в agent.properties (template.path)";
        }

        if (!Files.isRegularFile(Paths.get(config.templatePath()))) {
            return "Шаблон этикетки не найден по пути «" + config.templatePath()
                    + "» — проверьте template.path в agent.properties";
        }

        return null;
    }

    /**
     * Пишет файл данных для шаблона BarTender.
     *
     * Формат совпадает с серверной печатью: строка заголовка и по
     * строке на номер. Перевод строки — Windows-стиль: BarTender
     * разбирает только его.
     */
    private static Path writeDataFile(Config config,
                                      String batchId,
                                      List<String> numbers,
                                      String classifier,
                                      String name,
                                      String order,
                                      boolean showText) throws IOException {

        Path directory = config.dataDir().isBlank()
                ? Paths.get(System.getProperty("java.io.tmpdir"))
                : Paths.get(config.dataDir());

        Files.createDirectories(directory);

        String safeBatch = batchId == null || batchId.isBlank()
                ? "0"
                : batchId.replaceAll("[^0-9]", "");

        Path file = directory.resolve(
                "labels-batch-" + safeBatch + "-" + System.nanoTime() + ".txt"
        );

        String delimiter = delimiterOf(config.delimiter());

        StringBuilder text = new StringBuilder();

        if (config.header()) {
            text.append(String.join(delimiter,
                    FIELD_NUMBER, FIELD_INDEX, FIELD_CLASSIFIER,
                    FIELD_NAME, FIELD_ORDER, FIELD_SHOW_TEXT));
            text.append("\r\n");
        }

        int index = 1;

        for (String number : numbers) {
            text.append(String.join(delimiter,
                    number,
                    String.valueOf(index++),
                    safe(classifier),
                    safe(name),
                    safe(order),
                    showText ? "1" : "0"));
            text.append("\r\n");
        }

        if (config.utf16()) {
            Files.write(file, text.toString().getBytes(StandardCharsets.UTF_16LE));
        } else {
            Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
        }

        return file;
    }

    /**
     * Запускает BarTender и ждёт его завершения.
     *
     * {@code /P} — печать без диалога, {@code /X} — закрыть BarTender
     * после постановки задания. Ожидание обязательно: только код
     * возврата отличает «задание ушло на принтер» от «BarTender
     * показал ошибку и закрылся».
     */
    private static int runBartender(Config config, Path dataFile)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();

        command.add(Paths.get(config.bartenderPath()).toAbsolutePath().toString());
        command.add("/F=" + config.templatePath());
        command.add("/D=" + dataFile.toAbsolutePath());
        command.add("/P");
        command.add("/X");

        if (config.copies() > 1) {
            command.add("/C=" + config.copies());
        }

        if (!config.printer().isBlank()) {
            command.add("/PRN=" + config.printer());
        }

        ProcessBuilder builder = new ProcessBuilder(command);

        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        log("Запуск BarTender: " + command);

        synchronized (PRINT_LOCK) {

            Process process = builder.start();

            boolean finished = process.waitFor(
                    config.timeoutSeconds(), TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroyForcibly();

                throw new IOException(
                        "BarTender не завершился за "
                                + config.timeoutSeconds() + " с"
                );
            }

            return process.exitValue();
        }
    }

    /**
     * Читает тело запроса в поля «ключ → значения».
     *
     * Повторяющийся ключ number даёт несколько номеров: так в запрос
     * попадает вся партия без отдельного формата массива.
     */
    private static Map<String, List<String>> readBody(HttpExchange exchange)
            throws IOException {

        Map<String, List<String>> fields = new LinkedHashMap<>();

        String body;

        try (InputStream input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String line : body.split("\\r?\\n")) {

            if (line.isBlank()) {
                continue;
            }

            int separator = line.indexOf('=');

            if (separator <= 0) {
                continue;
            }

            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1);

            fields.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }

        return fields;
    }

    /** Читает agent.properties рядом с агентом; отсутствие файла — значения по умолчанию. */
    private static Config readConfig(Path file) throws IOException {

        Map<String, String> values = new LinkedHashMap<>();

        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {

                String trimmed = line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int separator = trimmed.indexOf('=');

                if (separator <= 0) {
                    continue;
                }

                values.put(
                        trimmed.substring(0, separator).trim(),
                        trimmed.substring(separator + 1).trim()
                );
            }
        } else {
            log("Файл настроек не найден: " + file
                    + " — используются значения по умолчанию");
        }

        return new Config(
                configInt(values, "port", DEFAULT_PORT),
                values.getOrDefault("bartender.path", ""),
                values.getOrDefault("template.path", ""),
                values.getOrDefault("printer", ""),
                values.getOrDefault("data.dir", ""),
                values.getOrDefault("delimiter", "tab"),
                "UTF-16".equalsIgnoreCase(values.getOrDefault("encoding", "UTF-8")),
                !"false".equalsIgnoreCase(values.getOrDefault("header", "true")),
                configInt(values, "copies", 1),
                configInt(values, "timeout.seconds", DEFAULT_TIMEOUT_SECONDS),
                values.getOrDefault("token", "")
        );
    }

    /** Каталог агента: рядом с jar, а при запуске из классов — текущий. */
    private static Path baseDirectory() {
        try {
            Path location = Paths.get(
                    PrintAgent.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI()
            );

            return Files.isDirectory(location)
                    ? location
                    : location.getParent();
        } catch (Exception exception) {
            return Paths.get(".").toAbsolutePath();
        }
    }

    private static String delimiterOf(String value) {
        String normalized = value == null ? "tab" : value.trim().toLowerCase();

        return switch (normalized) {
            case "comma", "," -> ",";
            case "semicolon", "semi", ";" -> ";";
            default -> "\t";
        };
    }

    private static String first(Map<String, List<String>> fields, String key) {
        List<String> values = fields.get(key);

        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private static int intValue(Map<String, List<String>> fields,
                                String key,
                                int fallback) {
        try {
            String value = first(fields, key);

            return value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int configInt(Map<String, String> values,
                                 String key,
                                 int fallback) {
        try {
            String value = values.get(key);

            return value == null || value.isBlank()
                    ? fallback
                    : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean boolValue(Map<String, List<String>> fields,
                                     String key,
                                     boolean fallback) {
        String value = first(fields, key);

        if (value.isBlank()) {
            return fallback;
        }

        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String describe(String value) {
        return value == null || value.isBlank() ? "не задан" : "«" + value + "»";
    }

    /** Ответ браузеру: заголовки CORS обязательны, страница живёт на другом адресе. */
    private static void respond(HttpExchange exchange,
                                int status,
                                String contentType,
                                String body) throws IOException {

        Headers headers = exchange.getResponseHeaders();

        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, X-Print-Token");
        headers.set("Access-Control-Max-Age", "600");
        headers.set("Cache-Control", "no-store");

        /*
         * Private Network Access.
         *
         * Страница приложения открыта по адресу сервера в локальной
         * сети (например, http://10.0.0.127:8080), а агент слушает
         * 127.0.0.1 на компьютере кладовщика. Для браузера это переход
         * из частной сети в локальную, и Chrome отправляет предварительный
         * запрос с Access-Control-Request-Private-Network: true. Без
         * разрешающего заголовка в ответе браузер блокирует печать,
         * хотя агент запущен и доступен.
         */
        headers.set("Access-Control-Allow-Private-Network", "true");

        if (contentType != null) {
            headers.set("Content-Type", contentType);
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /** Экранирование строки для ручной сборки JSON. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            /* Файл во временном каталоге: его уберёт система. */
        }
    }

    /** Пишет строку в консоль и в файл рядом с агентом. */
    private static void log(String message) {

        String line = "[" + LocalDateTime.now().format(LOG_TIME) + "] " + message;

        System.out.println(line);

        if (logFile == null) {
            return;
        }

        try {
            Files.writeString(
                    logFile,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            /* Журнал не должен мешать печати. */
        }
    }
}
