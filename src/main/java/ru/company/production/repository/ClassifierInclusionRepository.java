package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.ClassifierInclusion;

public interface ClassifierInclusionRepository
        extends JpaRepository<ClassifierInclusion, Long> {

    boolean existsByTargetId(Long targetId);
}