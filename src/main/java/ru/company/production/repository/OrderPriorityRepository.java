package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.OrderPriority;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderPriorityRepository
        extends JpaRepository<OrderPriority, Long> {

    List<OrderPriority> findAllByOrderByIdAsc();

    List<OrderPriority> findByActiveTrueOrderByIdAsc();

    Optional<OrderPriority> findByCode(String code);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    /* Все коды: числовая часть кода используется автогенерацией. */
    @Query("select priority.code from OrderPriority priority")
    List<String> findAllCodes();
}
