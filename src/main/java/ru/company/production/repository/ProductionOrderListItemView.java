package ru.company.production.repository;

import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.ZnpKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Строка списка заказов на производство.
 *
 * Заказ показывает сводку по выпуску: сколько изделий заявлено
 * (позиций-экземпляров) и сколько из них уже собрано.
 */
public interface ProductionOrderListItemView {

    Long getId();

    String getNumber();

    LocalDate getOrderDate();

    LocalDate getDueDate();

    String getCustomerOrderNumber();

    /*
     * Номер родительского выпуска: null у заказа верхнего уровня (ЗП)
     * и заполнен у заказа-комплектующего (ЗНП). По нему же определяется
     * уровень — отдельного флага в проекции нет, чтобы не плодить
     * дублирующие друг друга поля.
     */
    String getParentOrderNumber();

    /*
     * Вид заказа-комплектующего (миграция V37). У заказа верхнего
     * уровня колонка заполнена комплектованием по умолчанию, поэтому
     * в списке вид показывают только для комплектующих — так же, как
     * это делает сущность (см. ProductionOrder.isKindApplicable).
     */
    ZnpKind getZnpKind();

    /*
     * Изделие группового или ремонтного заказа (миграция V37).
     *
     * Запрос выбирает код и наименование напрямую из классификатора,
     * а не связью: проекция сущность не отдаёт, а обращение к lazy-связи
     * вне сессии (open-in-view: false) дало бы
     * LazyInitializationException. У комплектования и выпуска оба поля
     * пусты — там состав задан позициями, а не одним изделием.
     */
    String getClassifierCode();

    String getClassifierName();

    /* Количество заказанных изделий — только у групповых видов. */
    BigDecimal getQuantity();

    String getOperationName();

    /*
     * Код техпроцесса группового заказа (миграция V40).
     *
     * Как и изделие, код берётся из запроса, а не связью: проекция
     * сущность не отдаёт, а обращение к lazy-связи вне сессии
     * (open-in-view: false) дало бы LazyInitializationException.
     * У выпуска и комплектования поле пусто — там маршрут не ведут.
     */
    String getTechProcessCode();

    /*
     * Техпроцесс в колонке списка. Пустое значение — не ошибка данных:
     * заказы, заведённые до V40, маршрут не имели, и список обязан
     * показать их без разрыва строки.
     */
    default String getTechProcessDisplay() {
        String code = getTechProcessCode();

        return code == null || code.isBlank() ? "—" : code;
    }

    /* Количество выпускаемых изделий (позиций заказа). */
    Long getItemCount();

    /* Сколько позиций доведено до статуса «Выполнена». */
    Long getDoneItemCount();

    ProductionOrderStatus getStatus();

    default String getFormattedOrderDate() {
        return CustomerOrderDateFormatter.format(getOrderDate());
    }

    default String getFormattedDueDate() {
        return CustomerOrderDateFormatter.format(getDueDate());
    }

    default String getStatusDisplayName() {
        ProductionOrderStatus status = getStatus();

        return status == null ? "—" : status.getDisplayName();
    }

    default String getStatusBadgeClass() {
        ProductionOrderStatus status = getStatus();

        return status == null ? "draft" : status.getBadgeClass();
    }

    /*
     * Уровень заказа для колонки списка. По сущности уровень считает
     * ProductionOrder.isRoot(), но проекция сущность не отдаёт, поэтому
     * уровень выводится из двух признаков — тех же, что и в сущности:
     * наличие родителя и серийность вида (V38).
     *
     * Одного «родителя нет» с V38 недостаточно: групповые и ремонтные
     * ЗНП заводятся самостоятельно, без выпускa, и по одному лишь
     * пустому родителю попали бы в колонку как «ЗП».
     */
    default boolean isComponent() {
        return getParentOrderNumber() != null
                || (getZnpKind() != null
                && !getZnpKind().isSerialBased());
    }

    default String getLevelDisplayName() {
        return isComponent()
                ? "Комплектующий (ЗНП)"
                : "Выпуск (ЗП)";
    }

    /*
     * Вид в колонке уровня: «ЗНП на ремонт ячеек». Для выпуска — «ЗП».
     *
     * Префикс «ЗНП ·» к наименованию не добавляется: оно уже содержит
     * нужное слово («ЗНП на комплектование», «Групповой ЗНП на приборы»),
     * и приставка дала бы «ЗНП · ЗНП на комплектование».
     *
     * Неизвестное (снятое из старой страницы) значение трактуется как
     * комплектование — тот же разбор, что в форме и сервисе, иначе
     * список показал бы «null» на заказе, созданном до V37.
     */
    default String getKindColumn() {
        if (!isComponent()) {
            return "ЗП";
        }

        ZnpKind kind = getZnpKind();

        return kind == null
                ? ZnpKind.KITTING.getDisplayName()
                : kind.getDisplayName();
    }

    /* Родительский выпуск или «—», если заказ верхнего уровня. */
    default String getParentDisplay() {
        String parent = getParentOrderNumber();

        return parent == null || parent.isBlank() ? "—" : parent;
    }

    /*
     * Изделие группового заказа в колонке списка: «код — наименование»,
     * как в карточке заказа (см. ProductionOrder#getClassifierLabel).
     *
     * Текст собирается здесь, а не в шаблоне: страницу «Групповые ЗНП»
     * и карточку выпуска устраивает одна и та же запись, а повторение
     * конкатенации в двух разметках разошлось бы при первом же изменении
     * формата.
     */
    default String getClassifierLabel() {
        String code = getClassifierCode();
        String name = getClassifierName();

        if (code == null && name == null) {
            return "—";
        }

        if (code == null) {
            return name;
        }

        return name == null || name.isBlank() ? code : code + " — " + name;
    }

    /*
     * Количество без хвостовых нулей — «24», а не «24.000»: групповой
     * заказ считают в штуках, дробный хвост в списке только мешает
     * (тот же приём, что в ProductionOrder#getFormattedQuantity).
     */
    default String getFormattedQuantity() {
        java.math.BigDecimal value = getQuantity();

        return value == null
                ? "—"
                : value.stripTrailingZeros().toPlainString();
    }

    /* Готово: «собрано из заявленного», например «3 из 10». */
    default String getDoneProgress() {
        long total = getItemCount() == null ? 0L : getItemCount();
        long done = getDoneItemCount() == null ? 0L : getDoneItemCount();

        return done + " из " + total;
    }
}
