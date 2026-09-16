package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.ClassifierForm;
import ru.company.production.dto.ClassifierInclusionForm;
import ru.company.production.dto.ClassifierOption;
import ru.company.production.entity.*;
import ru.company.production.repository.ClassifierInclusionRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.SensorCatalogRepository;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ClassifierService {
    private static final BigDecimal MIN_QUANTITY = new BigDecimal("0.001");

    private final ProductClassifierRepository classifierRepository;
    private final ClassifierInclusionRepository inclusionRepository;
    private final SensorCatalogRepository sensorCatalogRepository;

    @Transactional(readOnly = true)
    public List<ProductClassifier> findAll() {
        return classifierRepository.findAllDetailed();
    }

    @Transactional(readOnly = true)
    public Map<ProductType, Long> countByProductType() {
        EnumMap<ProductType, Long> result = new EnumMap<>(ProductType.class);
        Arrays.stream(ProductType.values()).forEach(type -> result.put(type, 0L));
        classifierRepository.countGroupedByProductType().forEach(row ->
                result.put(row.getProductType(), row.getTotal()));
        return Collections.unmodifiableMap(result);
    }

    @Transactional(readOnly = true)
    public ProductClassifier findById(Long id) {
        return classifierRepository.findDetailedById(id).orElseThrow(() ->
                new EntityNotFoundException("Классификатор не найден"));
    }

    @Transactional(readOnly = true)
    public List<ClassifierOption> findInclusionOptions() {
        return classifierRepository.findAllByOrderByCodeAsc().stream()
                .map(value -> new ClassifierOption(value.getId(), value.getCode(),
                        value.getProductType(), value.getDisplayName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClassifierForm getForm(Long id) {
        ProductClassifier classifier = findById(id);
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
        form.setInclusions(classifier.getInclusions().stream().map(inclusion -> {
            ClassifierInclusionForm row = new ClassifierInclusionForm();
            row.setTargetId(inclusion.getTarget().getId());
            row.setQuantity(inclusion.getQuantity());
            row.setUnit(inclusion.getUnit());
            row.setInclusionMode(inclusion.getInclusionMode());
            return row;
        }).toList());
        return form;
    }

    @Transactional
    public ProductClassifier create(ClassifierForm form) {
        validateCodeForCreate(form.getCode());
        validateMainFields(form);
        ProductClassifier classifier = new ProductClassifier();
        applyMainFields(classifier, form);
        applyInclusions(classifier, form);
        return classifierRepository.save(classifier);
    }

    @Transactional
    public ProductClassifier update(Long id, ClassifierForm form) {
        ProductClassifier classifier = findById(id);
        validateCodeForUpdate(id, form.getCode());
        validateMainFields(form);
        if (classifier.getProductType() != form.getProductType()
                && inclusionRepository.existsByTargetId(id)) {
            throw new IllegalArgumentException("Нельзя изменить тип изделия: классификатор уже "
                    + "используется во входимости других изделий");
        }
        applyMainFields(classifier, form);
        applyInclusions(classifier, form);
        return classifierRepository.save(classifier);
    }

    @Transactional
    public void delete(Long id) {
        ProductClassifier classifier = findById(id);
        if (inclusionRepository.existsByTargetId(id)) {
            throw new IllegalArgumentException("Нельзя удалить классификатор: он входит в состав "
                    + "других классификаторов");
        }
        classifierRepository.delete(classifier);
    }

    private void validateCodeForCreate(Integer code) {
        validateCodeRange(code);
        if (classifierRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Классификатор с кодом " + code + " уже существует");
        }
    }

    private void validateCodeForUpdate(Long id, Integer code) {
        validateCodeRange(code);
        if (classifierRepository.existsByCodeAndIdNot(code, id)) {
            throw new IllegalArgumentException("Классификатор с кодом " + code + " уже существует");
        }
    }

    private void validateCodeRange(Integer code) {
        if (code == null || code < 1000 || code > 9999) {
            throw new IllegalArgumentException("Код классификатора должен быть целым числом от 1000 до 9999");
        }
    }

    private void validateMainFields(ClassifierForm form) {
        if (form.getProductType() == null) throw new IllegalArgumentException("Выберите тип изделия");
        if (form.getProductType() == ProductType.SENSOR) {
            if (form.getSensorCatalogId() == null) {
                throw new IllegalArgumentException("Выберите датчик из справочника");
            }
        } else if (isBlank(form.getName())) {
            throw new IllegalArgumentException("Наименование обязательно для выбранного типа изделия");
        }
    }

    private void applyMainFields(ProductClassifier classifier, ClassifierForm form) {
        classifier.setCode(form.getCode());
        classifier.setProductType(form.getProductType());
        classifier.setNote(normalize(form.getNote()));
        if (form.getProductType() == ProductType.SENSOR) {
            SensorCatalog sensor = sensorCatalogRepository.findById(form.getSensorCatalogId())
                    .orElseThrow(() -> new EntityNotFoundException("Выбранный датчик отсутствует в справочнике"));
            classifier.setSensorCatalog(sensor);
            classifier.setName(null);
        } else {
            classifier.setSensorCatalog(null);
            classifier.setName(normalize(form.getName()));
        }
    }

    private void applyInclusions(ProductClassifier owner, ClassifierForm form) {
        owner.clearInclusions();
        List<ClassifierInclusionForm> rows = form.getInclusions();
        if (rows == null || rows.isEmpty()) return;
        List<ClassifierInclusionForm> selected = rows.stream().filter(Objects::nonNull)
                .filter(row -> row.getTargetId() != null).toList();
        if (form.getProductType() == ProductType.CELL) {
            if (!selected.isEmpty()) throw new IllegalArgumentException("Для ячейки входимость не задаётся");
            return;
        }
        Set<Long> usedTargets = new HashSet<>();
        int position = 0;
        for (ClassifierInclusionForm row : selected) {
            if (row.getQuantity() == null || row.getQuantity().compareTo(MIN_QUANTITY) < 0) {
                throw new IllegalArgumentException("Количество должно быть не меньше 0.001");
            }
            if (row.getUnit() == null) throw new IllegalArgumentException("Выберите единицу измерения");
            if (row.getInclusionMode() == null) throw new IllegalArgumentException("Выберите режим включения изделия");
            if (!usedTargets.add(row.getTargetId())) {
                throw new IllegalArgumentException("Одно изделие нельзя добавить во входимость дважды");
            }
            ProductClassifier target = classifierRepository.findById(row.getTargetId())
                    .orElseThrow(() -> new EntityNotFoundException("Один из выбранных классификаторов не найден"));
            if (owner.getId() != null && owner.getId().equals(target.getId())) {
                throw new IllegalArgumentException("Классификатор не может входить сам в себя");
            }
            validateInclusionType(form.getProductType(), target.getProductType());
            ClassifierInclusion inclusion = new ClassifierInclusion();
            inclusion.setTarget(target);
            inclusion.setQuantity(row.getQuantity());
            inclusion.setUnit(row.getUnit());
            inclusion.setInclusionMode(row.getInclusionMode());
            inclusion.setPosition(position++);
            owner.addInclusion(inclusion);
        }
    }

    private void validateInclusionType(ProductType ownerType, ProductType targetType) {
        boolean allowed = switch (ownerType) {
            case SENSOR, DEVICE -> targetType == ProductType.CELL;
            case SYSTEM -> targetType == ProductType.DEVICE || targetType == ProductType.SENSOR;
            case CELL -> false;
        };
        if (!allowed) throw new IllegalArgumentException("Недопустимый тип изделия в разделе «Входимость»");
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private String normalize(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
