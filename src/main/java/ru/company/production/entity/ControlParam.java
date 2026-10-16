package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Справочник контролируемых параметров.
 * Пример: «Трубка», «Проволока», «Номер ячейки».
 */
@Entity
@Table(
        name = "control_params",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_control_params_name",
                columnNames = "name"
        )
)
@Getter
@Setter
@NoArgsConstructor
public class ControlParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ControlParamType type = ControlParamType.LIST;

    @OneToMany(
            mappedBy = "controlParam",
            fetch = FetchType.LAZY,
            orphanRemoval = true,
            cascade = CascadeType.ALL
    )
    private List<ControlParamValue> values = new ArrayList<>();

    /*
     * Параметр «про ячейку» — значение берут не из вариантов
     * справочника, а из состава изделия: список входящих в датчик
     * ячеек. Признак — слово «ячейка» в наименовании, поэтому
     * отдельного типа в справочнике нет: технолог получает нужный
     * режим, просто назвав параметр «Внесение номера ячейки».
     *
     * Режим ячейки важнее типа: параметр мог быть создан и как
     * «поле ввода», но раз он про ячейку — значение выбирают из
     * состава изделия, а не вписывают наугад.
     *
     * Проверка по подстроке, а не по полному совпадению: параметры
     * называют «Внесение номера ячейки», «Проверка ячейки» и т. п.,
     * и все они значат одно и то же.
     */
    @Transient
    public boolean isCellMode() {
        return name != null
                && name.toLowerCase().contains("ячейк");
    }

    public void addValue(ControlParamValue value) {
        values.add(value);
        value.setControlParam(this);
    }

    public void removeValue(ControlParamValue value) {
        values.remove(value);
        value.setControlParam(null);
    }
}
