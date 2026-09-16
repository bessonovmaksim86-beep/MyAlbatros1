package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
@Entity @Table(name="classifier_inclusion")
@Getter @Setter @NoArgsConstructor
public class ClassifierInclusion {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)
 @JoinColumn(name="owner_id",nullable=false,foreignKey=@ForeignKey(name="fk_classifier_inclusion_owner"))
 private ProductClassifier owner;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)
 @JoinColumn(name="target_id",nullable=false,foreignKey=@ForeignKey(name="fk_classifier_inclusion_target"))
 private ProductClassifier target;
 @Column(nullable=false,precision=15,scale=3) private BigDecimal quantity;
 /*
  * Верхняя граница: сколько таких изделий допускается в системе.
  * Необязательное поле — пустое значение означает «не ограничено».
  */
 @Column(name="max_quantity",precision=15,scale=3) private BigDecimal maxQuantity;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=10) private MeasurementUnit unit;
    @Enumerated(EnumType.STRING)
    @Column(name = "inclusion_mode", nullable = false, length = 20)
    private InclusionMode inclusionMode;
 @Column(nullable=false) private Integer position;
}
