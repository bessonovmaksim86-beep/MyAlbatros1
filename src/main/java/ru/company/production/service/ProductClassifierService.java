package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.ClassifierForm;
import ru.company.production.dto.ClassifierInclusionForm;
import ru.company.production.entity.*;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.SensorCatalogRepository;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProductClassifierService {
    private static final BigDecimal MIN_QUANTITY = new BigDecimal("0.001");
    private final ProductClassifierRepository classifiers;
    private final SensorCatalogRepository sensors;

    @Transactional(readOnly = true)
    public List<ProductClassifier> findAll() { return classifiers.findAllDetailed(); }

    @Transactional(readOnly = true)
    public Map<ProductType, Long> countByProductType() {
        EnumMap<ProductType, Long> result = new EnumMap<>(ProductType.class);
        Arrays.stream(ProductType.values()).forEach(type -> result.put(type, 0L));
        classifiers.countGroupedByProductType().forEach(row ->
                result.put(row.getProductType(), row.getTotal()));
        return Collections.unmodifiableMap(result);
    }

    @Transactional(readOnly = true)
    public ProductClassifier findOne(Long id) { return get(id); }

    @Transactional(readOnly = true)
    public List<SensorCatalog> findSensors() { return sensors.findAllByOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public List<ProductClassifier> inclusionOptions(Long excludedClassifierId) {
        return classifiers.findInclusionOptionsDetailed(excludedClassifierId);
    }

    @Transactional(readOnly = true)
    public ClassifierForm getForm(Long id) {
        ProductClassifier classifier = get(id);
        ClassifierForm form = new ClassifierForm();
        form.setId(classifier.getId());
        form.setCode(classifier.getCode());
        form.setProductType(classifier.getProductType());
        form.setName(classifier.getName());
        form.setNote(classifier.getNote());
        form.setVersion(classifier.getVersion());
        if (classifier.getSensorCatalog() != null) {
            form.setSensorCatalogId(classifier.getSensorCatalog().getId());
        }
        List<ClassifierInclusionForm> rows = new ArrayList<>();
        for (ClassifierInclusion inclusion : classifier.getInclusions()) {
            ClassifierInclusionForm row = new ClassifierInclusionForm();
            row.setTargetId(inclusion.getTarget().getId());
            row.setQuantity(inclusion.getQuantity());
            row.setUnit(inclusion.getUnit());
            row.setInclusionMode(inclusion.getInclusionMode());
            rows.add(row);
        }
        form.setInclusions(rows);
        return form;
    }

    @Transactional
    public void create(ClassifierForm form) {
        validate(form, null);
        if (classifiers.existsByCode(form.getCode())) {
            throw new IllegalArgumentException("Код уже используется");
        }
        ProductClassifier classifier = new ProductClassifier();
        copy(form, classifier);
        replaceRows(form, classifier);
        save(classifier);
    }

    @Transactional
    public void update(Long id, ClassifierForm form) {
        ProductClassifier classifier = get(id);
        validate(form, id);
        if (!Objects.equals(classifier.getVersion(), form.getVersion())) {
            throw new IllegalStateException("Запись уже изменена другим пользователем. Обновите страницу и повторите операцию.");
        }
        if (classifiers.existsByCodeAndIdNot(form.getCode(), id)) {
            throw new IllegalArgumentException("Код уже используется");
        }
        copy(form, classifier);
        classifier.clearInclusions();
        classifiers.flush();
        replaceRows(form, classifier);
        save(classifier);
    }

    @Transactional
    public void delete(Long id) {
        ProductClassifier classifier = get(id);
        try {
            classifiers.delete(classifier);
            classifiers.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("Невозможно удалить классификатор. Он используется в составе другого классификатора.", exception);
        }
    }

    private ProductClassifier get(Long id) {
        return classifiers.findDetailedById(id).orElseThrow(() ->
                new EntityNotFoundException("Классификатор с идентификатором " + id + " не найден"));
    }

    private void copy(ClassifierForm form, ProductClassifier classifier) {
        classifier.setCode(form.getCode());
        classifier.setProductType(form.getProductType());
        classifier.setNote(trim(form.getNote()));
        if (form.getProductType() == ProductType.SENSOR) {
            SensorCatalog sensor = sensors.findById(form.getSensorCatalogId()).orElseThrow(() ->
                    new EntityNotFoundException("Выбранный датчик не найден"));
            classifier.setSensorCatalog(sensor);
            classifier.setName(null);
        } else {
            classifier.setSensorCatalog(null);
            classifier.setName(trim(form.getName()));
        }
    }

    private void replaceRows(ClassifierForm form, ProductClassifier owner) {
        List<ClassifierInclusionForm> rows = selectedRows(form);
        if (rows.isEmpty()) return;
        if (owner.getProductType() == ProductType.CELL) {
            throw new IllegalArgumentException("Для ячейки нельзя указывать состав изделия");
        }
        Set<Long> targetIds = new HashSet<>();
        for (ClassifierInclusionForm row : rows) {
            validateInclusionRow(row);
            if (!targetIds.add(row.getTargetId())) {
                throw new IllegalArgumentException("Входящее изделие повторяется");
            }
            if (Objects.equals(owner.getId(), row.getTargetId())) {
                throw new IllegalArgumentException("Классификатор не может входить сам в себя");
            }
        }
        Map<Long, ProductClassifier> targets = new HashMap<>();
        classifiers.findAllById(targetIds).forEach(target -> targets.put(target.getId(), target));
        if (targets.size() != targetIds.size()) {
            throw new EntityNotFoundException("Одно из входящих изделий не найдено");
        }
        for (ClassifierInclusionForm row : rows) {
            validateAllowedTargetType(owner.getProductType(), targets.get(row.getTargetId()).getProductType());
        }
        int position = 0;
        for (ClassifierInclusionForm row : rows) {
            ClassifierInclusion inclusion = new ClassifierInclusion();
            inclusion.setTarget(targets.get(row.getTargetId()));
            inclusion.setQuantity(row.getQuantity());
            inclusion.setUnit(row.getUnit());
            inclusion.setInclusionMode(row.getInclusionMode());
            inclusion.setPosition(position++);
            owner.addInclusion(inclusion);
        }
    }

    private void validateAllowedTargetType(ProductType ownerType, ProductType targetType) {
        boolean allowed = switch (ownerType) {
            case SENSOR, DEVICE -> targetType == ProductType.CELL;
            case SYSTEM -> targetType == ProductType.SENSOR || targetType == ProductType.DEVICE;
            case CELL -> false;
        };
        if (!allowed) throw new IllegalArgumentException("Недопустимый тип изделия в составе");
    }

    private void validateInclusionRow(ClassifierInclusionForm row) {
        if (row.getQuantity() == null || row.getQuantity().compareTo(MIN_QUANTITY) < 0) {
            throw new IllegalArgumentException("Количество должно быть не меньше 0.001");
        }
        if (row.getInclusionMode() == null) throw new IllegalArgumentException("Выберите режим включения изделия");
        if (row.getUnit() == null) throw new IllegalArgumentException("Выберите единицу измерения");
    }

    private void validate(ClassifierForm form, Long currentClassifierId) {
        if (form.getCode() == null || form.getCode() < 1000 || form.getCode() > 9999) {
            throw new IllegalArgumentException("Код классификатора должен быть в диапазоне от 1000 до 9999");
        }
        if (form.getProductType() == null) throw new IllegalArgumentException("Выберите тип изделия");
        if (form.getProductType() == ProductType.SENSOR) {
            if (form.getSensorCatalogId() == null) throw new IllegalArgumentException("Выберите датчик");
        } else if (trim(form.getName()) == null) {
            throw new IllegalArgumentException("Укажите наименование");
        }
        List<ClassifierInclusionForm> rows = selectedRows(form);
        if (form.getProductType() == ProductType.CELL && !rows.isEmpty()) {
            throw new IllegalArgumentException("Для ячейки нельзя указывать состав изделия");
        }
        Set<Long> ids = new HashSet<>();
        for (ClassifierInclusionForm row : rows) {
            validateInclusionRow(row);
            if (Objects.equals(currentClassifierId, row.getTargetId())) {
                throw new IllegalArgumentException("Классификатор не может входить сам в себя");
            }
            if (!ids.add(row.getTargetId())) throw new IllegalArgumentException("Входящее изделие повторяется");
        }
    }

    private List<ClassifierInclusionForm> selectedRows(ClassifierForm form) {
        if (form.getInclusions() == null) return List.of();
        return form.getInclusions().stream().filter(Objects::nonNull)
                .filter(row -> row.getTargetId() != null).toList();
    }

    private void save(ProductClassifier classifier) {
        try {
            classifiers.saveAndFlush(classifier);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("Не удалось сохранить классификатор. Проверьте уникальность кода и связанные изделия.", exception);
        }
    }

    private String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
