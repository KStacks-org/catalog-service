package com.kaustack.catalog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@Table(indexes = {
        @Index(name = "idx_term_course", columnList = "term_id, course_id")})
public class Section {
    @Id
    private String id;

    private Integer crn;

    @ManyToOne
    @JsonIgnoreProperties("sections")
    private Term term;

    @ManyToOne
    @JsonIgnoreProperties("sections")
    private Course course;

    @ManyToOne
    @JsonIgnoreProperties({"sectionsTaught", "schedules"})
    private Instructor instructor;

    private String code;
    private String branch;

    private String scheduleType;

    private String instructionMethod;

    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL)
    @JsonIgnoreProperties("section")
    private List<Schedule> schedules;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}