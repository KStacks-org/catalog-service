package com.kaustack.catalog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Entity
@Data
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"code", "number"})
})
public class Course {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String title;
    private String code;
    private String number;

    private String level;
    private Integer credits;

    @OneToMany(mappedBy = "course")
    @JsonIgnoreProperties("course")
    private List<Section> sections;
}