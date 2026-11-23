package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.company.production.config.OneCProperties;
import ru.company.production.dto.OneCZpRow;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.repository.OneCRepository;
import ru.company.production.repository.ZpExportView;
import ru.company.production.service.OneCComClient.OneCComResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Обмен с 1С по заказам на производство (ЗП).
 *
 * Первая операция — выборка ЗП за период по признаку «закрытые /
 * оформленные». Источник данных выбирает сама 1С: если COM-канал
 * настроен и отвечает, строки берутся из неё; если канал выключен или
 * недоступен, выгрузка идёт из собственной базы приложения. Так
 * раздел остаётся рабочим на машине без 1С, а на рабочем месте с 1С
 * показывает настоящие заказы.
 *
 * {@link #sendTo1C} остаётся заглушкой: чтение канала отработано, но
 * запись в 1С требует отдельного механизма (штатная проводка документа
 * через тот же COM), и его включаем после проверки выборки.
 */
@Service
@RequiredArgsConstructor
public class OneCIntegrationService {

    /** Режим «закрытые»: выпуск выполнен и более не участвует в работе. */
    public static final String MODE_CLOSED = "closed";

    /** Режим «оформленные»: выпуск зарегистрирован, но ещё не закрыт. */
    public static final String MODE_ISSUED = "issued";

    private final OneCRepository oneCRepository;
    private final OneCComClient oneCComClient;
    private final OneCProperties oneCProperties;

    /**
     * Выбирает ЗП за период и отдаёт строки вместе с источником.
     *
     * @param from начало периода (nullable — без нижней границы)
     * @param to   конец периода (nullable — без верхней границы)
     * @param mode режим: {@link #MODE_CLOSED} или {@link #MODE_ISSUED}
     * @return строки выгрузки и признак, откуда они взяты
     */
    public OneCQueryResult loadZp(LocalDate from, LocalDate to, String mode) {
        OneCComResult comResult = oneCComClient.loadZp();

        switch (comResult.status()) {
            case OneCComClient.STATUS_OK:
                return new OneCQueryResult(
                        OneCSource.ONEC,
                        comResult.status(),
                        oneCRowsToView(filterOneC(comResult.rows(), from, to, mode))
                );

            case OneCComClient.STATUS_EMPTY:
                return new OneCQueryResult(
                        OneCSource.ONEC,
                        comResult.status(),
                        List.of()
                );

            default:
                // Канал выключен или недоступен — отдаём собственную базу.
                return new OneCQueryResult(
                        OneCSource.LOCAL,
                        comResult.status(),
                        loadLocal(from, to, mode)
                );
        }
    }

    /**
     * Фильтрует строки 1С по периоду и режиму.
     *
     * COM-запрос возвращает выборку целиком (имена таблиц конфигурации
     * ещё не сверены, поэтому границу периода в запрос 1С не вшиваем),
     * а отбор делает приложение. Строка без читаемой даты при заданном
     * периоде отбрасывается: подтвердить её принадлежность нечем.
     */
    private List<OneCZpRow> filterOneC(
            List<OneCZpRow> rows,
            LocalDate from,
            LocalDate to,
            String mode
    ) {
        boolean closedMode = MODE_CLOSED.equalsIgnoreCase(mode);

        List<OneCZpRow> result = new ArrayList<>();

        for (OneCZpRow row : rows) {
            if (from != null && (row.orderDate() == null || row.orderDate().isBefore(from))) {
                continue;
            }

            if (to != null && (row.orderDate() == null || row.orderDate().isAfter(to))) {
                continue;
            }

            if (row.isClosed() != closedMode) {
                continue;
            }

            result.add(row);
        }

        return result;
    }

    /**
     * Выгрузка из собственной базы приложения.
     *
     * Список статусов обязателен для JPQL: пустой набор означал бы
     * «все статусы», поэтому сервис подставляет перечень по режиму сам.
     */
    private List<ZpExportView> loadLocal(LocalDate from, LocalDate to, String mode) {
        LocalDate start = from != null ? from : LocalDate.of(1970, 1, 1);
        LocalDate end = to != null ? to : LocalDate.of(2999, 12, 31);

        return oneCRepository.findZpForExport(start, end, statusesOf(mode));
    }

    /**
     * Набор статусов по режиму: закрытые либо оформленные.
     *
     * Неизвестное значение трактуется как оформленные — так страница по
     * умолчанию показывает незакрытые заказы, а не пустую таблицу.
     */
    private List<ProductionOrderStatus> statusesOf(String mode) {
        if (MODE_CLOSED.equalsIgnoreCase(mode)) {
            return List.of(
                    ProductionOrderStatus.DONE,
                    ProductionOrderStatus.CANCELLED
            );
        }

        return List.of(
                ProductionOrderStatus.NEW,
                ProductionOrderStatus.IN_WORK,
                ProductionOrderStatus.PARTIALLY_DONE
        );
    }

    /**
     * Оборачивает строки 1С в проекцию {@link ZpExportView}.
     *
     * {@code ZpExportView} — интерфейс-проекция, реальную реализацию
     * которой создаёт Spring Data только для своих запросов. Строки из
     * 1С来到这里 как адаптер: статус отдаётся текстом 1С, поэтому
     * {@link #getStatus()} остаётся {@code null}, а подпись статуса
     * переопределяется строкой источника.
     */
    private List<ZpExportView> oneCRowsToView(List<OneCZpRow> rows) {
        List<ZpExportView> result = new ArrayList<>(rows.size());

        for (OneCZpRow row : rows) {
            result.add(new ZpExportView() {
                @Override
                public String getNumber() {
                    return row.number();
                }

                @Override
                public LocalDate getOrderDate() {
                    return row.orderDate();
                }

                @Override
                public String getCustomerOrderNumber() {
                    return row.customerOrderNumber();
                }

                @Override
                public String getCustomerName() {
                    return row.customerName();
                }

                @Override
                public ProductionOrderStatus getStatus() {
                    return null;
                }

                @Override
                public String getFormattedOrderDate() {
                    return row.getFormattedOrderDate();
                }

                @Override
                public String getStatusDisplayName() {
                    return row.getStatusLabel();
                }

                @Override
                public String getCustomerLabel() {
                    return row.getCustomerLabel();
                }
            });
        }

        return result;
    }

    /**
     * Отправка выбранных ЗП в 1С.
     *
     * Заглушка: вызывается из интерфейса, чтобы кнопка и разметка были
     * готовы до включения записи. Реализацию ставим сюда — проводку
     * документа через тот же {@code V83.COMConnector}, когда выборка
     * проверена и имена метаданных сверены.
     *
     * @return true — если обмен прошёл успешно
     */
    public boolean sendTo1C(List<ZpExportView> items) {
        // TODO: реализовать запись в 1С через COM, когда проверка выборки пройдена
        return oneCProperties.isConfigured();
    }

    /** Откуда взяты строки выдачи. */
    public enum OneCSource {
        /** Строки из 1С через COM-канал. */
        ONEC,

        /** Строки из собственной базы приложения. */
        LOCAL
    }

    /**
     * Итог выборки: строки, источник и код состояния COM-канала.
     *
     * Код состояния нужен интерфейсу, чтобы объяснить администратору,
     * почему показана собственная база: канал выключен, недоступен или
     * 1С вернула пустоту. Без этого пустая таблица читалась бы как
     * «заказов нет», а не как «1С не отвечает».
     *
     * @param source   источник строк
     * @param comStatus один из {@code OneCComClient.STATUS_*}
     * @param rows     строки выгрузки
     */
    public record OneCQueryResult(OneCSource source, int comStatus, List<ZpExportView> rows) {

        public boolean fromOneC() {
            return source == OneCSource.ONEC;
        }
    }
}
