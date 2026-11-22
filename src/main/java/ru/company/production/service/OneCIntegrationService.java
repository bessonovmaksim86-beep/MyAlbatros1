package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.repository.OneCRepository;
import ru.company.production.repository.ZpExportView;
import ru.company.production.service.OneCJdbcClient.OneCLoadResult;

import java.time.LocalDate;
import java.util.List;

/**
 * Обмен с 1С по заказам на производство (ЗП).
 *
 * Первая операция — выборка ЗП за период по признаку «закрытые /
 * оформленные». Запрос идёт в базу 1С через JDBC, если соединение
 * включено в настройках; иначе — в собственную базу приложения
 * (та же структура, но JPA-проекции).
 *
 * {@link #sendTo1C} остаётся заглушкой: протокол обмена ещё не
 * выбран. Когда станет известно, как 1С принимает данные,
 * реализацию поставим сюда.
 */
@Service
@RequiredArgsConstructor
public class OneCIntegrationService {

    /** Режим «закрытые»: выпуск выполнен и более не участвует в работе. */
    public static final String MODE_CLOSED = "closed";

    /** Режим «оформленные»: выпуск зарегистрирован, но ещё не закрыт. */
    public static final String MODE_ISSUED = "issued";

    private final OneCRepository oneCRepository;
    private final OneCJdbcClient oneCJdbcClient;

    /**
     * Выбирает ЗП для выгрузки за период.
     *
     * Если база 1С доступна и содержит данные — отдаёт их.
     * Если 1С недоступна или пуста — отдаёт из собственной базы.
     * Границы периода, переданные как {@code null}, заменяются
     * на бесконечный диапазон.
     *
     * @param from начало периода (nullable — с самой ранней даты)
     * @param to   конец периода (nullable — по сегодня)
     * @param mode режим: {@link #MODE_CLOSED} или {@link #MODE_ISSUED}
     * @return список ЗП с номером, датой и клиентом
     */
    public List<ZpExportView> loadZp(LocalDate from, LocalDate to, String mode) {
        LocalDate start = from != null ? from : LocalDate.of(1970, 1, 1);
        LocalDate end = to != null ? to : LocalDate.of(2999, 12, 31);

        OneCLoadResult result = oneCJdbcClient.loadZp(start, end);

        if (result.isOk()) {
            return oneCRowsToView(result.rows());
        }

        /*
         * 1С недоступна, выключена или пуста — возвращаем из собственной
         * базы. Список статусов обязателен для JPQL: пустой набор означал
         * бы «все статусы», и сервис подставляет полный перечень сам.
         */
        List<ProductionOrderStatus> statuses = statusesOf(mode);

        return oneCRepository.findZpForExport(start, end, statuses);
    }

    /**
     * Конвертация строк из JDBC в JPA-проекции.
     *
     * {@code ZpExportView} — интерфейс-проекция, и Spring Data JPA
     * создаёт реализацию только для своих запросов. Поэтому строки
     * из 1С оборачиваем в простой класс-адаптер.
     */
    private List<ZpExportView> oneCRowsToView(List<ru.company.production.dto.OneCZpRow> rows) {
        List<ZpExportView> result = new java.util.ArrayList<>(rows.size());

        for (ru.company.production.dto.OneCZpRow row : rows) {
            result.add(new ZpExportView() {
                @Override
                public String getNumber() {
                    return row.number();
                }

                @Override
                public java.time.LocalDate getOrderDate() {
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
     * Отправка выбранных ЗП в 1С.
     *
     * Заглушка: вызывается из интерфейса, чтобы кнопка и разметка были
     * готовы до выбора протокола. Реализацию ставим сюда — HTTP-клиент,
     * COM-соединение или выгрузку файла, когда узнаем, как 1С
     * принимает данные.
     *
     * @return true — если обмен прошёл успешно
     */
    public boolean sendTo1C(List<ZpExportView> items) {
        // TODO: реализовать реальный обмен с 1С, когда известен протокол
        return true;
    }
}
