package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.InputControlComponentForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.InputControlComponent;
import ru.company.production.entity.JournalControlType;
import ru.company.production.entity.Role;
import ru.company.production.entity.WarehouseSupplier;
import ru.company.production.repository.InputControlComponentRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WarehouseSupplierRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Журнал входного контроля несерийных комплектующих.
 *
 * Внесение, правка, удаление и отбор строк журнала. Поставщик
 * создаётся «на-лету» через справочник склада, если нужной записи
 * в нём ещё нет.
 *
 * Тот же сервис проводит и строки, разобранные из листа журнала:
 * {@link #saveImported} отличается от ручного сохранения только
 * источником даты контроля и поставщика, а правила сверки общие —
 * иначе ручная и импортированная записи разъехались бы по
 * требованиям, и импорт начал бы отклонять то, что форма принимает.
 *
 * Журнал не влияет на складские остатки и не создаёт записей в
 * изоляторе брака — это реестр принятых и забракованных количеств,
 * служащий для отчётности и сверки с бумажными документами.
 */
@Service
@RequiredArgsConstructor
public class InputControlComponentService {

    /*
     * Исполнители, которыми подписывают строки журнала: роли обоих
     * бюро службы качества. Импорт журнала заводит администратор,
     * но подпись обязана указывать на контролёра, вёл ли он контроль
     * или мастер бюро, — администратор контроль не ведёт.
     */
    private static final Set<Role> QUALITY_ROLES = Set.of(
            Role.BVK_MASTER,
            Role.BVK_EXECUTOR,
            Role.BTK_MASTER,
            Role.BTK_EXECUTOR
    );

    private final InputControlComponentRepository journalRepository;
    private final WarehouseSupplierRepository supplierRepository;
    private final UserRepository userRepository;

    /**
     * Перечень записей с отбором.
     *
     * Пустые параметры означают «условие не задано»: если дата не
     * выбрана, фильтр по периоду опускается, а если поставщик
     * не выбран — отбор идёт по всем поставщикам.
     */
    @Transactional(readOnly = true)
    public List<InputControlComponent> findFiltered(
            LocalDateTime dateFrom,
            LocalDateTime dateTo,
            Long supplierId,
            String productName
    ) {
        /*
         * Если productName не пустой — добавляем маску LIKE.
         * Если null или пустая строка — JPQL-условие
         * (:productName is null) сработает и отбор по тексту
         * опустится.
         */
        String likeProduct = productName == null || productName.isBlank()
                ? null
                : "%" + productName + "%";

        return journalRepository.findFiltered(
                dateFrom, dateTo, supplierId, likeProduct
        );
    }

    /**
     * Все записи журнала — для страницы, где отбор не задаётся.
     */
    @Transactional(readOnly = true)
    public List<InputControlComponent> findAll() {
        return journalRepository.findAllDetailed();
    }

    /**
     * Счётчик записей в журнале — для цифры в боковом меню.
     */
    @Transactional(readOnly = true)
    public long count() {
        return journalRepository.count();
    }

    /**
     * Одна запись журнала с поставщиком и контролёром.
     *
     * Связи читает сам запрос: {@code open-in-view} выключен, а форме
     * правки нужны и идентификатор поставщика, и фамилия подписавшего.
     */
    @Transactional(readOnly = true)
    public InputControlComponent findDetailed(Long id) {
        return journalRepository
                .findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Запись журнала не найдена"
                ));
    }

    /**
     * Создаёт или обновляет запись журнала.
     *
     * Если id пуст — создаётся новая строка. Если задан — ищется
     * существующая запись; если не найдена — отказ.
     *
     * Поставщик задаётся либо через справочник ({@code supplierId}),
     * либо как новое наименование ({@code newSupplierName}). Оба
     * поля заполнять нельзя: сервис откажет, иначе строка журнала
     * осталась бы без однозначного поставщика. Если выбран справочник,
     * новое наименование игнорируется.
     */
    @Transactional
    public InputControlComponent save(
            InputControlComponentForm form,
            AppUser checkedBy
    ) {
        String id = form.getId();

        boolean isNew = id == null || id.isBlank();

        InputControlComponent component;

        if (isNew) {
            component = new InputControlComponent();
        } else {
            component = journalRepository
                    .findDetailedById(Long.valueOf(id.trim()))
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Запись журнала не найдена"
                    ));
        }

        /*
         * Валидация: поставщик обязан быть задан ровно одним способом.
         *
         * Пустой выбор — не «поставщик неизвестен», а пропущенный
         * реквизит: без него строку журнала нельзя ни сверить с
         * сопроводительным документом, ни отдать в рекламацию.
         */
        String newSupplierName = trimToNull(form.getNewSupplierName());
        Long supplierId = form.supplierIdAsLong();

        if (newSupplierName != null && supplierId != null) {
            throw new IllegalArgumentException(
                    "Выберите поставщика из справочника или введите новое имя, а не оба сразу"
            );
        }

        if (newSupplierName == null && supplierId == null) {
            throw new IllegalArgumentException(
                    "Укажите предприятие-поставщик"
            );
        }

        /*
         * Поставщик из справочника.
         */
        if (supplierId != null) {
            WarehouseSupplier supplier = supplierRepository
                    .findById(supplierId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Поставщик не найден"
                    ));

            component.setSupplier(supplier);
        } else {
            component.setSupplier(null);
        }

        /*
         * Новое наименование поставщика: заводится в справочник,
         * если такого написания там ещё нет, и привязывается к
         * записи журнала.
         *
         * Повторное написание ищется нормализованным сравнением:
         * уникальность справочника держится на collation, и без
         * проверки журнал плодил бы дубли вида «Энергосила» и
         * «энергосила».
         */
        if (newSupplierName != null) {
            WarehouseSupplier supplier = supplierRepository
                    .findByNormalizedName(newSupplierName)
                    .orElseGet(() -> {
                        WarehouseSupplier created = new WarehouseSupplier();

                        created.setName(newSupplierName);
                        created.setCreatedBy(checkedBy);

                        return supplierRepository.save(created);
                    });

            component.setSupplier(supplier);
        }

        component.setProductName(form.getProductName());
        component.setDocNumber(trimToNull(form.getDocNumber()));
        component.setDocDate(form.getDocDate());
        component.setTotalQuantity(form.getTotalQuantity());
        component.setCheckedQuantity(form.getCheckedQuantity());
        component.setScrapQuantity(form.getScrapQuantity());
        component.setActNumber(trimToNull(form.getActNumber()));
        component.setActDate(form.getActDate());
        component.setDescription(trimToNull(form.getDescription()));
        component.setMeasures(trimToNull(form.getMeasures()));
        component.setNote(trimToNull(form.getNote()));
        component.setControlType(JournalControlType.INPUT);

        validateQuantities(component);

        /*
         * Подпись строки журнала: новую запись вносит текущий
         * контролёр, исправленная сохраняет того, кто строку завёл.
         *
         * Дата контроля при правке не меняется — она стоит в
         * колонке «Дата контроля» и задаёт место строки в журнале,
         * поэтому её сдвиг переписал бы историю проверок.
         */
        if (isNew || component.getCheckedBy() == null) {
            component.setCheckedBy(checkedBy);
        }

        return journalRepository.save(component);
    }

    /**
     * Сохраняет строку, разобранную из листа журнала.
     *
     * От ручного внесения отличается двумя вещами, и обе заданы
     * условиями раздела:
     *
     *  1) дата контроля берётся из колонки A листа. Импорт может
     *     относиться к прошлому году, и проставь мы «сейчас» —
     *     отбор по периоду показывал бы неверные строки, а журнал
     *     потерял бы исходный порядок;
     *  2) вместо строки формы приходит {@link
     *     InputControlJournalParser.ParsedJournalRow}, где поставщик
     *     — написание из листа. Оно заводится в справочник по тому
     *     же правилу, что и ручное новое имя: импорт не должен
     *     плодить дубли вида «Энергосила» и «энергосила».
     *
     * Запись подписывается выбранным исполнителем службы качества,
     * а не администратором, который нажал «Внести»: подпись обязана
     * указывать на того, кто контроль вёл.
     */
    @Transactional
    public InputControlComponent saveImported(
            InputControlJournalParser.ParsedJournalRow row,
            AppUser executor
    ) {
        InputControlComponent component = new InputControlComponent();

        /*
         * Пустая колонка A: строку датируют моментом внесения.
         * Молча оставлять дату пустой нельзя — колонка NOT NULL, и
         * база отклонила бы запись без внятного сообщения.
         */
        component.setControlDate(row.controlDate() == null
                ? LocalDateTime.now()
                : row.controlDate().atStartOfDay());

        component.setProductName(row.productName());
        component.setControlType(JournalControlType.INPUT);

        /*
         * Поставщик из листа: написание ищется в справочнике, при
         * отсутствии — заводится там же.
         */
        WarehouseSupplier supplier = resolveSupplier(row.supplier(), executor);

        component.setSupplier(supplier);
        component.setCheckedBy(executor);

        component.setDocNumber(trimToNull(row.docNumber()));
        component.setDocDate(row.docDate());
        component.setTotalQuantity(row.totalQuantity());
        component.setCheckedQuantity(row.checkedQuantity());
        component.setScrapQuantity(row.scrapQuantity());
        component.setActNumber(trimToNull(row.actNumber()));
        component.setActDate(row.actDate());
        component.setDescription(trimToNull(row.description()));
        component.setMeasures(trimToNull(row.measures()));
        component.setNote(trimToNull(row.note()));

        validateQuantities(component);

        return journalRepository.save(component);
    }

    /**
     * Поставщик по написанию: запись справочника либо новая.
     *
     * Пустое написание — отказ: строка журнала без поставщика не
     * отдаётся в рекламацию и не сверяется с документом.
     */
    private WarehouseSupplier resolveSupplier(String name, AppUser author) {
        String supplierName = trimToNull(name);

        if (supplierName == null) {
            throw new IllegalArgumentException(
                    "Укажите предприятие-поставщик"
            );
        }

        return supplierRepository.findByNormalizedName(supplierName)
                .orElseGet(() -> {
                    WarehouseSupplier created = new WarehouseSupplier();

                    created.setName(supplierName);
                    created.setCreatedBy(author);

                    return supplierRepository.save(created);
                });
    }

    /**
     * Согласованность количеств.
     *
     * Проверенного не может быть больше, чем заявлено документом, а
     * забракованного — больше, чем проверено: иначе итог строки
     * физически противоречив, и журнал перестал бы сходиться с
     * сопроводительным документом.
     *
     * Пустое значение — «не внесено», а не ноль: лист заполняют
     * частями, и отсутствующую колонку проверять не с чем.
     */
    private static void validateQuantities(InputControlComponent component) {
        Integer total = component.getTotalQuantity();
        Integer checked = component.getCheckedQuantity();
        Integer scrap = component.getScrapQuantity();

        if (total != null && checked != null && checked > total) {
            throw new IllegalArgumentException(
                    "Проверенное количество (" + checked
                            + ") больше общего по документу (" + total + ")"
            );
        }

        if (checked != null && scrap != null && scrap > checked) {
            throw new IllegalArgumentException(
                    "Забракованное количество (" + scrap
                            + ") больше проверенного (" + checked + ")"
            );
        }
    }

    /**
     * Удаляет запись из журнала.
     *
     * Если запись не найдена — отказ без исключения: удаление из
     * шаблона идёт через ссылку, и пользователь не должен видеть
     * 500-ю ошибку из-за удалённой кем-то строки.
     */
    @Transactional
    public void delete(Long id) {
        InputControlComponent component = journalRepository
                .findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Запись журнала не найдена"
                ));

        journalRepository.delete(component);
    }

    /**
     * Поставщики справочника — для выпадающего списка в форме.
     */
    @Transactional(readOnly = true)
    public List<WarehouseSupplier> findAllSuppliers() {
        return supplierRepository.findAllByOrderByNameAsc();
    }

    /**
     * Исполнители службы качества — подписанты импортируемых строк.
     *
     * Список берут по четырём ролям обоих бюро разом и сортируют по
     * фамилии: администратору важно найти человека, а не разбирать
     * перечень по ролям. Отключённые учётные записи исключены —
     * подпись строки журнала обязан нести действующий сотрудник.
     */
    @Transactional(readOnly = true)
    public List<AppUser> findQualityExecutors() {
        return userRepository
                .findByRoleInAndActiveTrueOrderByFullNameAscUsernameAsc(
                        List.copyOf(QUALITY_ROLES)
                );
    }

    /**
     * Исполнитель строки импорта по идентификатору.
     *
     * Право на подпись проверяют по роли, а не по факту выбора в
     * списке: форму могли отправить с чужим или устаревшим
     * идентификатором, и подпись администратора или сотрудника цеха
     * журнал принять не должен.
     */
    @Transactional(readOnly = true)
    public AppUser requireQualityExecutor(Long id) {
        AppUser user = userRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Исполнитель не найден"
                ));

        if (!user.isActive()
                || user.getRole() == null
                || !QUALITY_ROLES.contains(user.getRole())) {
            throw new IllegalArgumentException(
                    "Строку журнала вправе подписывать только сотрудник "
                            + "бюро входного или технического контроля"
            );
        }

        return user;
    }

    /**
     * Форма поверх сохранённой записи: при повторном открытии поля
     * обязаны содержать внесённое, иначе правка одной опечатки
     * обнулила бы всю строку.
     */
    public InputControlComponentForm formFrom(InputControlComponent component) {
        InputControlComponentForm form = new InputControlComponentForm();

        form.setId(String.valueOf(component.getId()));
        form.setProductName(component.getProductName());
        form.setSupplierId(component.getSupplier() == null
                ? null
                : String.valueOf(component.getSupplier().getId()));
        form.setDocNumber(component.getDocNumber());
        form.setDocDate(component.getDocDate());
        form.setTotalQuantity(component.getTotalQuantity());
        form.setCheckedQuantity(component.getCheckedQuantity());
        form.setScrapQuantity(component.getScrapQuantity());
        form.setActNumber(component.getActNumber());
        form.setActDate(component.getActDate());
        form.setDescription(component.getDescription());
        form.setMeasures(component.getMeasures());
        form.setNote(component.getNote());

        return form;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
