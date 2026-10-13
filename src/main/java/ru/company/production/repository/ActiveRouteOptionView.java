package ru.company.production.repository;

import ru.company.production.entity.ProductType;

/**
 * Активный техпроцесс с его основным изделием для страницы
 * «Добавить техпроцесс» в классификаторе.
 *
 * Основное изделие (productClassifier) нужно подписью: технолог
 * ищет маршрут по изделию, для которого он разработан. Тип изделия
 * и род датчика отдают отдельными полями, потому что у изделия типа
 * «Датчик» своего наименования нет, и без рода выбор был бы непонятен.
 */
public interface ActiveRouteOptionView {

    /** Идентификатор техпроцесса. */
    Long getProcessId();

    /** Код техпроцесса. */
    String getCode();

    /** Идентификатор изделия, для которого разработан маршрут. */
    Long getClassifierId();

    /** Код этого изделия. */
    Integer getClassifierCode();

    /** Вид этого изделия. */
    ProductType getProductType();

    /** Наименование этого изделия. */
    String getClassifierName();

    /** Род датчика, если изделие — датчик. */
    String getSensorClassName();

    /** Число операций маршрута. */
    long getOperationCount();
}
