package ru.company.production.repository;

/**
 * Проекция «активное ЗНП, привязанное к серийнику через операцию».
 *
 * Используется разделом комплектования, чтобы показать только те
 * операции сборки, под которые диспетчер поднял заказ комплектующих
 * в статусе В РАБОТЕ. Сборка triples идёт одним пакетным запросом
 * {@link SerialProductionOrderRepository#findActiveZnpLinksBySerialIds}
 * (ключ пары — серийник + операция, тот же, что и в карточке выпуска),
 * поэтому проекция держит ровно три поля и не тянет за собой сущности.
 *
 * Формы интерфейса достаточно: Spring Data проецирует алиасы JPQL
 * (serialId, operationId, znpNumber) на геттеры по имени, отдельная
 * реализация не нужна.
 */
public interface KittingZnpLinkView {

    Long getSerialId();

    Long getOperationId();

    String getZnpNumber();
}