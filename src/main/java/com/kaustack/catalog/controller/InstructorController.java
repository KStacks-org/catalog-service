package com.kaustack.catalog.controller;

import com.kaustack.catalog.service.CatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/instructors")
public class InstructorController {

    @Autowired
    private CatalogService catalogService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getInstructors(
            @RequestParam(required = false) String termCode,
            @RequestParam(required = false) String q
    ) {
        List<Map<String, Object>> instructors = catalogService.getInstructors(termCode, q);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "meta", Map.of("count", instructors.size()),
                "data", instructors
        ));
    }

    @GetMapping("/{instructorId}")
    public ResponseEntity<Map<String, Object>> getInstructorById(
            @PathVariable String instructorId,
            @RequestParam(required = false) String termCode
    ) {
        try {
            Map<String, Object> instructorData = catalogService.getInstructorDetails(instructorId, termCode);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "data", instructorData
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "status", "error",
                            "message", e.getMessage()
                    ));
        }
    }
}