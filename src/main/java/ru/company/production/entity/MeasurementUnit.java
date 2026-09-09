package ru.company.production.entity;
import lombok.*;
@Getter @RequiredArgsConstructor
public enum MeasurementUnit {
 PCS("шт."), KG("кг"), G("г"), M("м"), MM("мм"), L("л"), ML("мл");
 private final String displayName;
}
