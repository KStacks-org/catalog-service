package com.kaustack.catalog.controller;

import com.kaustack.catalog.dto.SectionDTO;
import com.kaustack.catalog.model.Section;
import com.kaustack.catalog.service.CatalogMapper;
import com.kaustack.catalog.service.CatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/courses/sections")
public class SearchController {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private CatalogMapper mapper;

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String termCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String days,
            @RequestParam(required = false) String instructor,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String crn,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String branch,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        Page<Section> pageResult = catalogService.search(
                termCode, q, page, limit, days, instructor,
                startTime, endTime, level, crn, section, gender, branch
        );

        List<SectionDTO> dtos = pageResult.getContent().stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");

        Map<String, Object> meta = new HashMap<>();
        meta.put("total", pageResult.getTotalElements());
        meta.put("page", page);
        meta.put("totalPages", pageResult.getTotalPages());

        response.put("meta", meta);
        response.put("data", dtos);

        return ResponseEntity.ok(response);
    }
}