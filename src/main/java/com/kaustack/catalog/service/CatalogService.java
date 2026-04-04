package com.kaustack.catalog.service;

import com.kaustack.catalog.dto.InstructorHierarchyDTO;
import com.kaustack.catalog.model.Schedule;
import com.kaustack.catalog.model.Section;
import com.kaustack.catalog.model.Term;
import com.kaustack.catalog.model.Course;
import com.kaustack.catalog.model.Instructor;
import com.kaustack.catalog.repository.ScheduleRepository;
import com.kaustack.catalog.repository.SectionRepository;
import com.kaustack.catalog.repository.TermRepository;
import jakarta.persistence.criteria.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    @Autowired
    private SectionRepository sectionRepository;

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    public List<Map<String, Object>> getCourses(String termCode, String q) {
        Term term = resolveTerm(termCode);
        List<Course> courses = sectionRepository.findUniqueCoursesByTerm(term.getId());

        String normalizedQ = normalizeSearchText(q);

        String[] queryTokens = normalizedQ.isEmpty() ? new String[0] : normalizedQ.split("[\\s\\-]+");

        return courses.stream()
                .filter(c -> {
                    if (queryTokens.length == 0) return true;

                    String searchableString = normalizeSearchText(
                            c.getCode() + c.getNumber() + " " +
                                    c.getCode() + " " +
                                    c.getNumber() + " " +
                                    c.getTitle()
                    );

                    for (String token : queryTokens) {
                        if (!searchableString.contains(token)) {
                            return false;
                        }
                    }
                    return true;
                })
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", c.getId());
                    map.put("code", c.getCode());
                    map.put("number", c.getNumber());
                    map.put("title", c.getTitle());
                    map.put("fullCode", c.getCode() + "-" + c.getNumber());
                    map.put("credits", c.getCredits());
                    return map;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("fullCode")))
                .collect(Collectors.toList());
    }

    // Add this helper method down at the bottom of your class
    private String normalizeSearchText(String input) {
        if (input == null) return "";
        return input.toLowerCase()
                .replace("أ", "ا")
                .replace("إ", "ا")
                .replace("آ", "ا")
                .replace("ة", "ه")
                .replace("ي", "ى");
    }

    public List<Section> getSectionsByCourse(String termCode, String courseId, String gender) {
        Term term = resolveTerm(termCode);
        List<Section> sections = sectionRepository.findByTermIdAndCourseId(term.getId(), courseId);

        if (gender == null || gender.isEmpty()) return sections;

        String mappedGender = mapGender(gender);
        return sections.stream()
                .filter(s -> {
                    if (s.getBranch() == null) return false;
                    assert mappedGender != null;
                    return s.getBranch().contains(mappedGender);
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getInstructors(String termCode, String q) {
        Term term = resolveTerm(termCode);
        List<Instructor> instructors = sectionRepository.findUniqueInstructorsByTerm(term.getId());

        return instructors.stream()
                .filter(i -> q == null || i.getName().toLowerCase().contains(q.toLowerCase()))
                .map(i -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", i.getId());
                    map.put("name", i.getName());
                    map.put("email", i.getEmail());
                    return map;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("name")))
                .collect(Collectors.toList());
    }

    public Page<Section> search(
            String termCode, String q, int page, int limit, String days,
            String instructor, String startTime, String endTime,
            String level, String crn, String sectionCode,
            String gender, String branch
    ) {
        Term term = resolveTerm(termCode);

        Specification<Section> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Term Filter (Mandatory)
            predicates.add(cb.equal(root.get("term").get("id"), term.getId()));

            // 2. Smart Search (q) - Normalized for Course Code + Number or Title
            if (q != null && !q.trim().isEmpty()) {
                String pattern = "%" + q.toLowerCase().replace("-", "").replace(" ", "") + "%";
                // Concat code and number to match "CPCS203" or "CPCS 203"
                Expression<String> fullCourseCode = cb.concat(root.get("course").get("code"), root.get("course").get("number"));

                predicates.add(cb.or(
                        cb.like(cb.lower(fullCourseCode), pattern),
                        cb.like(cb.lower(root.get("course").get("title")), pattern)
                ));
            }

            // 3. Instructor Filter
            if (instructor != null && !instructor.isEmpty()) {
                predicates.add(cb.like(cb.lower(root.get("instructor").get("name")), "%" + instructor.toLowerCase() + "%"));
            }

            // 4. CRN Filter
            if (crn != null && !crn.isEmpty()) {
                try {
                    predicates.add(cb.equal(root.get("crn"), Integer.parseInt(crn)));
                } catch (NumberFormatException ignored) {}
            }

            // 5. Section Code Filter
            if (sectionCode != null && !sectionCode.isEmpty()) {
                predicates.add(cb.like(cb.lower(root.get("code")), "%" + sectionCode.toLowerCase() + "%"));
            }

            // 6. Level Filter
            if (level != null && !level.isEmpty()) {
                predicates.add(cb.equal(root.get("level"), level));
            }

            // 7. Gender/Branch Filter
            if (gender != null && !gender.isEmpty()) {
                String mapped = mapGender(gender);
                if (mapped != null) {
                    predicates.add(cb.like(root.get("branch"), "%" + mapped + "%"));
                }
            }

            if (branch != null && !branch.isEmpty()) {
                predicates.add(cb.like(cb.lower(root.get("branch")), "%" + branch.toLowerCase() + "%"));
            }

            // 8. Days and Time Filters (Requiring Subquery to Schedule Table)
            if ((days != null && !days.isEmpty()) || startTime != null || endTime != null) {
                // We use a Subquery to avoid duplicate Sections in the result set when multiple schedules match
                Subquery<Integer> scheduleSubquery = query.subquery(Integer.class);
                Root<Schedule> scheduleRoot = scheduleSubquery.from(Schedule.class);
                scheduleSubquery.select(cb.literal(1)); // SELECT 1

                List<Predicate> subPredicates = new ArrayList<>();
                subPredicates.add(cb.equal(scheduleRoot.get("section"), root));

                // Days Match (e.g., searching "MW" matches schedules containing "M" and "W")
                if (days != null && !days.isEmpty()) {
                    String sortedDays = sortDays(days.toUpperCase());
                    for (char day : sortedDays.toCharArray()) {
                        subPredicates.add(cb.like(scheduleRoot.get("days"), "%" + day + "%"));
                    }
                }

                // Time Range Match
                if (startTime != null) {
                    Integer startMin = parseTimeBytes(startTime);
                    if (startMin != null) {
                        subPredicates.add(cb.greaterThanOrEqualTo(scheduleRoot.get("startTime"), startMin));
                    }
                }
                if (endTime != null) {
                    Integer endMin = parseTimeBytes(endTime);
                    if (endMin != null) {
                        subPredicates.add(cb.lessThanOrEqualTo(scheduleRoot.get("endTime"), endMin));
                    }
                }

                scheduleSubquery.where(cb.and(subPredicates.toArray(new Predicate[0])));
                predicates.add(cb.exists(scheduleSubquery));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // 9. Pagination & Sorting
        Pageable pageable = PageRequest.of(page - 1, limit,
                Sort.by("course.code").ascending()
                        .and(Sort.by("course.number").ascending())
                        .and(Sort.by("code").ascending())
        );

        return sectionRepository.findAll(spec, pageable);
    }

    public Map<String, List<String>> getGroupedSections(String termCode, String courseQuery, String sectionCode, String gender) {
        Term term = resolveTerm(termCode);
        List<Section> sections = sectionRepository.findByTermId(term.getId());

        final String normalizedQ = (courseQuery == null) ? "" : courseQuery.replace("-", "").replace(" ", "").toLowerCase();

        return sections.stream()
                .filter(s -> {
                    if (!normalizedQ.isEmpty()) {
                        String target = (s.getCourse().getCode() + s.getCourse().getNumber()).toLowerCase();
                        if (!target.contains(normalizedQ)) return false;
                    }
                    if (sectionCode != null && !s.getCode().contains(sectionCode)) return false;
                    if (gender != null) {
                        String mapped = mapGender(gender);
                        return s.getBranch() != null && s.getBranch().contains(Objects.requireNonNull(mapped));
                    }
                    return true;
                })
                .collect(Collectors.groupingBy(
                        s -> s.getCourse().getCode() + "-" + s.getCourse().getNumber(),
                        TreeMap::new,
                        Collectors.mapping(Section::getCode, Collectors.toList())
                ));
    }

    // Not used for now
    public List<InstructorHierarchyDTO> getInstructorHierarchy(String termCode) {
        Term term = resolveTerm(termCode);
        List<Section> sections = sectionRepository.findByTermId(term.getId());

        return sections.stream()
                .filter(s -> s.getInstructor() != null)
                .collect(Collectors.groupingBy(s -> s.getInstructor().getName()))
                .entrySet().stream()
                .map(entry -> {
                    InstructorHierarchyDTO dto = new InstructorHierarchyDTO();
                    dto.setName(entry.getKey());
                    dto.setEmail(entry.getValue().getFirst().getInstructor().getEmail());
                    return dto;
                })
                .sorted(Comparator.comparing(InstructorHierarchyDTO::getName))
                .collect(Collectors.toList());
    }

    public Course getCourseById(String courseId) {
        return (Course) sectionRepository.findCourseById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found with ID: " + courseId));
    }

    public Map<String, Object> getInstructorDetails(String instructorId, String termCode) {
        Term term = resolveTerm(termCode);

        // Fetch schedules instead of sections
        List<Schedule> schedules = scheduleRepository.findByInstructorIdAndSectionTermId(instructorId, term.getId());

        if (schedules.isEmpty()) {
            throw new IllegalArgumentException("No teaching schedule found for this instructor.");
        }

        Instructor instructor = schedules.getFirst().getInstructor();

        // Grouping by Course Code
        Map<String, List<Map<String, Object>>> teachingMap = schedules.stream()
                .collect(Collectors.groupingBy(
                        sch -> sch.getSection().getCourse().getCode() + "-" + sch.getSection().getCourse().getNumber(),
                        Collectors.mapping(sch -> {
                            Map<String, Object> details = new HashMap<>();
                            details.put("sectionCode", sch.getSection().getCode());
                            details.put("crn", sch.getSection().getCrn());
                            details.put("days", sch.getDays());
                            details.put("time", sch.getRawTime());
                            details.put("location", sch.getLocation());
                            return details;
                        }, Collectors.toList())
                ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instructorName", instructor.getName());
        result.put("email", instructor.getEmail());
        result.put("term", term.getTermCode());
        result.put("schedule", teachingMap);

        return result;
    }

    // --- Helpers ---

    private Term resolveTerm(String termCode) {
        if (termCode != null && !termCode.isEmpty()) {
            return termRepository.findByTermCode(termCode)
                    .orElseThrow(() -> new IllegalArgumentException("Term not found: " + termCode));
        }
        return termRepository.findTopByOrderByUpdatedAtDesc()
                .orElseThrow(() -> new IllegalArgumentException("No terms found in database"));
    }

    private String mapGender(String input) {
        if ("male".equalsIgnoreCase(input)) return "طلاب";
        if ("female".equalsIgnoreCase(input)) return "طالبات";
        return null;
    }

    private Integer parseTimeBytes(String timeStr) {
        if (timeStr == null || !timeStr.matches("\\d{1,2}:\\d{2}")) return null;
        String[] parts = timeStr.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String sortDays(String input) {
        String dayOrder = "MTWRFSU";
        return Arrays.stream(input.split(""))
                .sorted(Comparator.comparingInt(dayOrder::indexOf))
                .collect(Collectors.joining());
    }
}