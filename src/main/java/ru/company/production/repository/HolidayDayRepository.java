package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.HolidayDay;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HolidayDayRepository
        extends JpaRepository<HolidayDay, Long> {

    List<HolidayDay> findByHolidayDateBetweenOrderByHolidayDateAsc(
            LocalDate from,
            LocalDate to
    );

    Optional<HolidayDay> findByHolidayDate(LocalDate holidayDate);

    boolean existsByHolidayDate(LocalDate holidayDate);

    long countByHolidayDateBetween(LocalDate from, LocalDate to);
}
