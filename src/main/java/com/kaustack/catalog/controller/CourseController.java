package com.kaustack.catalog.controller;

import com.kaustack.catalog.dto.SectionDTO;
import com.kaustack.catalog.model.Course;
import com.kaustack.catalog.model.Section;
import com.kaustack.catalog.service.CatalogMapper;
import com.kaustack.catalog.service.CatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/courses")
public class CourseController {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private CatalogMapper mapper;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getCourses(
            @RequestParam(required = false) String termCode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean grouped
    ) {
        if (grouped) {
            Map<String, List<String>> groupedData = catalogService.getGroupedSections(termCode, q, null, null);
            return ResponseEntity.ok(Map.of("status", "success", "data", groupedData));
        }

        List<Map<String, Object>> courses = catalogService.getCourses(termCode, q);
        return ResponseEntity.ok(Map.of("status", "success", "data", courses));
    }

    @GetMapping("/{courseId}")
    public ResponseEntity<Map<String, Object>> getCourseById(@PathVariable String courseId) {
        Course course = catalogService.getCourseById(courseId);

        Map<String, Object> courseData = new LinkedHashMap<>();
        courseData.put("id", course.getId());
        courseData.put("code", course.getCode());
        courseData.put("number", course.getNumber());
        courseData.put("title", course.getTitle());

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "data", courseData
        ));
    }

    @GetMapping("/{courseId}/sections")
    public ResponseEntity<Map<String, Object>> getCourseSections(
            @PathVariable String courseId,
            @RequestParam(required = false) String termCode,
            @RequestParam(required = false) String gender
    ) {
        List<Section> sections = catalogService.getSectionsByCourse(termCode, courseId, gender);
        List<SectionDTO> dtos = sections.stream().map(mapper::toDTO).toList();

        return ResponseEntity.ok(Map.of("status", "success", "data", dtos));
    }
}