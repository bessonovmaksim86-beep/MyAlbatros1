package ru.company.production.repository;

/**
 * Количество записей, сгруппированное по идентификатору типа.
 */
public interface TypeCountProjection {

    Long getTypeId();

    Long getTotal();
}
