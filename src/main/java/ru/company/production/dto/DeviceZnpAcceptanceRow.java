package ru.company.production.dto;

import ru.company.production.entity.ProductionOrder;

/**
 * Строка списка «Приёмка групповых ЗНП на приборы»: заказ-комплектующий
 * с выпущенными приборами, все виды ячеек которых привязаны к приборам
 * (нет непривязанных контролируемых параметров).
 *
 * Заказ-комплектующий передаётся сущностью — шаблон печатает его поля
 * в рамках уже открытой транзакции. Приёмка доступна только когда
 * у всех приборов заказа нет непривязанных ячеек — по нему видно,
 * что исполнитель собрал все изделия, и склад может принять приборы.
 *
 * @param order          заказ-комплектующий (GROUP_DEVICE)
 * @param acceptanceAvailable приёмка открыта — все виды ячеек привязаны
 * @param productLabel   подпись изделия (прибор)
 * @param productTypeLabel тип изделия (всегда «Прибор»)
 */
public record DeviceZnpAcceptanceRow(
        ProductionOrder order,
        boolean acceptanceAvailable,
        String productLabel,
        String productTypeLabel
) {
}
