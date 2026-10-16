package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.ControlParamForm;
import ru.company.production.entity.ControlParam;
import ru.company.production.entity.ControlParamType;
import ru.company.production.entity.ControlParamValue;
import ru.company.production.repository.ControlParamRepository;
import ru.company.production.repository.TechProcessOperationControlParamRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Справочник контролируемых параметров и их вариантов.
 *
 * Состав вариантов задаёт технолог списком строк: под последним
 * заполненным значением форма всегда показывает пустую строку,
 * поэтому пустую строку сервис считает незаполненной и отбрасывает,
 * а пустое значение у уже сохранённого варианта — удалением.
 */
@Service
@RequiredArgsConstructor
public class ControlParamService {

    private final ControlParamRepository controlParamRepository;
    private final TechProcessOperationControlParamRepository
            controlParamLinkRepository;

    @Transactional(readOnly = true)
    public List<ControlParam> findAll() {
        return controlParamRepository.findAllDetailed();
    }

    @Transactional(readOnly = true)
    public ControlParam get(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Не указан идентификатор контролируемого параметра"
            );
        }

        return controlParamRepository
                .findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Контролируемый параметр с идентификатором "
                                + id
                                + " не найден"
                ));
    }

    @Transactional(readOnly = true)
    public ControlParamForm getForm(Long id) {
        ControlParam param = get(id);

        ControlParamForm form = new ControlParamForm();
        form.setName(param.getName());
        form.setType(param.getType());

        for (ControlParamValue value : param.getValues()) {
            ControlParamForm.ValueItem item =
                    new ControlParamForm.ValueItem();

            item.setId(value.getId());
            item.setValue(value.getValue());

            form.getValues().add(item);
        }

        return form;
    }

    @Transactional
    public ControlParam create(ControlParamForm form) {
        String name = normalizeName(form.getName());
        ControlParamType type = normalizeType(form);

        if (controlParamRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException(
                    "Контролируемый параметр «" + name + "» уже существует"
            );
        }

        ControlParam param = new ControlParam();
        param.setName(name);
        param.setType(type);

        /*
         * У текстового параметра вариантов нет: значение вписывает
         * исполнитель, и справочник его не перечисляет. У параметра
         * «…ячейки…» вариантов тоже нет — его значение берут из
         * состава изделия, а не из списка справочника.
         */
        if (needsValues(type, name)) {
            for (String value : distinctValues(form)) {
                param.addValue(newValue(value));
            }

            requireValues(param);
        }

        return controlParamRepository.save(param);
    }

    @Transactional
    public ControlParam update(Long id, ControlParamForm form) {
        ControlParam param = get(id);

        String name = normalizeName(form.getName());

        if (controlParamRepository
                .findByNameAndIdNot(name, id)
                .isPresent()) {

            throw new IllegalArgumentException(
                    "Контролируемый параметр «" + name + "» уже существует"
            );
        }

        param.setName(name);

        applyType(param, normalizeType(form));

        /*
         * Варианты ведут только у параметра со списком значений: у
         * текстового и у «ячеечного» их не бывает, и строки блока
         * значений форма не показывает.
         *
         * Строки формы с идентификатором — это сохранённые варианты:
         * заполненная строка меняет значение, пустая удаляет вариант.
         * Строки без идентификатора добавляют новые значения, а
         * сохранённый вариант, которого в форме нет, удаляется — так
         * работает форма, нарисованная шаблоном.
         */
        if (needsValues(param.getType(), param.getName())) {
            applyValues(param, form);
            requireValues(param);
        }

        return controlParamRepository.save(param);
    }

    @Transactional
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "Не указан идентификатор контролируемого параметра"
            );
        }

        ControlParam param = controlParamRepository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Контролируемый параметр с идентификатором "
                                + id
                                + " не найден"
                ));

        controlParamRepository.delete(param);
        controlParamRepository.flush();
    }

    /**
     * Смена типа параметра с проверками.
     *
     * LIST → TEXT_INPUT разрешён только если у параметра нет вариантов,
     * которые используются в маршрутах (строки с control_param_value_id
     * не переживут смену типа).
     *
     * TEXT_INPUT → LIST разрешён: вариантов у текстового параметра нет,
     * и при смене на LIST они появятся при добавлении значений.
     *
     * Применение параметра в маршруте (строка с control_param_value_id
     * не null) не запрещает смену типа: при LIST → TEXT_INPUT сервис
     * удалит все варианты — но если хоть один из них уже привязан к
     * операции, это будет ошибка целостности на уровне БД. Поэтому
     * добавлена проверка: если вариант используется — запретить смену.
     */
    private void applyType(ControlParam param, ControlParamType newType) {
        ControlParamType oldType = param.getType();

        if (oldType != newType) {
            /*
             * LIST → TEXT_INPUT: если есть сохранённые варианты —
             * удаляем их, но только если ни один не привязан к
             * операциям маршрута.
             */
            if (oldType == ControlParamType.LIST
                    && controlParamLinkRepository
                            .existsByControlParamId(param.getId())) {

                throw new IllegalArgumentException(
                        "Нельзя превратить параметр «"
                                + param.getName()
                                + "» в текстовый — его варианты уже "
                                + "используются в маршрутах"
                );
            }

            param.setType(newType);
        }
    }

    /**
     * Обновление вариантов параметра по строкам формы.
     *
     * Строки формы с идентификатором — это сохранённые варианты:
     * заполненная строка меняет значение, пустая удаляет вариант.
     * Строки без идентификатора добавляют новые значения, а
     * сохранённый вариант, которого в форме нет, удаляется.
     */
    private void applyValues(ControlParam param, ControlParamForm form) {
        Map<Long, String> kept = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();

        for (ControlParamForm.ValueItem item : form.getValues()) {
            String value = item.getValue() == null
                    ? ""
                    : item.getValue().trim();

            if (item.getId() == null) {
                if (!value.isBlank()) {
                    added.add(value);
                }
                continue;
            }

            if (!value.isBlank()) {
                kept.put(item.getId(), value);
            }
        }

        param.getValues()
                .removeIf(existing -> !kept.containsKey(existing.getId()));

        for (ControlParamValue existing : param.getValues()) {
            existing.setValue(kept.get(existing.getId()));
        }

        for (String value : mergeDistinct(kept.values(), added)) {
            boolean alreadyStored = param.getValues()
                    .stream()
                    .anyMatch(stored -> stored.getValue().equals(value));

            if (!alreadyStored) {
                param.addValue(newValue(value));
            }
        }
    }

    /**
     * Валидация типа: не пусто и не пустая строка.
     */
    private ControlParamType normalizeType(ControlParamForm form) {
        if (form.getType() == null) {
            throw new IllegalArgumentException(
                    "Выберите тип контролируемого параметра"
            );
        }

        return form.getType();
    }

    /**
     * Нужны ли параметру варианты справочника.
     *
     * Варианты заводят только параметру со списком значений. У
     * текстового параметра значение вписывает исполнитель, у
     * «ячеечного» его берут из состава изделия — в обоих случаях
     * список вариантов был бы пустой формальностью, а требование
     * «хотя бы один вариант» сделало бы такой параметр невозможным
     * к созданию.
     */
    private static boolean needsValues(ControlParamType type, String name) {
        if (type != ControlParamType.LIST) {
            return false;
        }

        return name == null || !name.toLowerCase().contains("ячейк");
    }

    /**
     * Требование хотя бы одного варианта для типа LIST.
     */
    private void requireValues(ControlParam param) {
        if (param.getValues().isEmpty()) {
            throw new IllegalArgumentException(
                    "Укажите хотя бы один вариант параметра"
            );
        }
    }

    /**
     * Заполненные значения формы без повторов, в порядке ввода.
     */
    private static List<String> distinctValues(ControlParamForm form) {
        return mergeDistinct(List.of(), valuesOf(form));
    }

    private static List<String> valuesOf(ControlParamForm form) {
        List<String> values = new ArrayList<>();

        for (ControlParamForm.ValueItem item : form.getValues()) {
            if (item.getValue() != null
                    && !item.getValue().isBlank()) {

                values.add(item.getValue().trim());
            }
        }

        return values;
    }

    /**
     * Склеивает два набора значений, отбрасывая пустые и повторные.
     *
     * Повтор вариант технолог трактует как одну и ту же строку,
     * введённую дважды: молча сохранить дубль — значит показать его
     * потом в двух экземплярах при выборе значения в операции.
     */
    private static List<String> mergeDistinct(
            Iterable<String> first,
            List<String> second
    ) {
        List<String> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String value : first) {
            if (value != null && seen.add(value)) {
                merged.add(value);
            }
        }

        for (String value : second) {
            if (seen.add(value)) {
                merged.add(value);
            }
        }

        return merged;
    }

    private static ControlParamValue newValue(String value) {
        ControlParamValue paramValue = new ControlParamValue();
        paramValue.setValue(value);

        return paramValue;
    }

    private static String normalizeName(String name) {
        if (name == null) {
            throw new IllegalArgumentException(
                    "Введите наименование параметра"
            );
        }

        String normalized = name.trim().replaceAll("\\s+", " ");

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Введите наименование параметра"
            );
        }

        if (normalized.length() > 255) {
            throw new IllegalArgumentException(
                    "Наименование параметра не должно превышать 255 символов"
            );
        }

        return normalized;
    }
}
