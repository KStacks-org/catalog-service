package com.kaustack.catalog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(indexes = {
        @Index(name = "idx_schedule_section_id", columnList = "section_id")
})
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String type;

    private Integer startTime;

    private Integer endTime;

    private String rawTime;

    private String days;

    private String location;

    private String dateRange;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    @JsonIgnoreProperties("schedules")
    private Section section;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id")
    @JsonIgnoreProperties("schedules")
    private Instructor instructor;
}