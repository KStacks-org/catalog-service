package com.kaustack.catalog.misc;

import com.kaustack.catalog.model.*;
import com.kaustack.catalog.repository.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataLoader implements CommandLineRunner {

    private final TermRepository termRepository;
    private final RestTemplate restTemplate;

    // Using native JDBC for high-speed bulk inserts, bypassing JPA overhead
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.data.load:false}")
    private boolean load;

    @Value("${app.data.courses-url}")
    private String coursesUrl;

    @Value("${app.data.instructors-url}")
    private String instructorsUrl;

    @Override
    public void run(String... args) {
        if (!load) {
            log.info("Data loading is disabled (app.data.load=false). Skipping catalog sync.");
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("=== Starting High-Speed Catalog Data Sync ===");

        loadCourses();
        loadInstructors();

        long endTime = System.currentTimeMillis();
        log.info("=== Catalog Data Sync Complete in {} ms ===", (endTime - startTime));
    }

    private void nukeDatabase() {
        log.info("Nuking the database using ultra-fast native queries...");
        jdbcTemplate.execute("DELETE FROM schedule");
        jdbcTemplate.execute("DELETE FROM section");
        jdbcTemplate.execute("DELETE FROM course");
        jdbcTemplate.execute("DELETE FROM instructor");
        jdbcTemplate.execute("DELETE FROM term");
        log.info("Database cleared successfully!");
    }

    private void loadCourses() {
        log.info("[1/5] Fetching courses...");
        CoursesApiResponse response = restTemplate.getForObject(coursesUrl, CoursesApiResponse.class);

        if (response == null || !"success".equals(response.getStatus()) || response.getData() == null) {
            log.warn("No valid course data received from API. Aborting course load.");
            return;
        }

        log.info("[2/5] Successfully downloaded {} courses.", response.getData().size());

        // Data secured in memory. Safe to nuke the remote database now.
        nukeDatabase();

        log.info("Saving new Term data via JPA...");
        Term term = new Term();
        term.setName(response.getTermName());
        term.setTermCode(response.getTermId());
        term.setUpdatedAt(LocalDateTime.now());
        term.setCreatedAt(LocalDateTime.now());
        term = termRepository.save(term);

        log.info("[3/5] Building highly optimized memory structures...");
        Map<String, Course> coursesToSave = new HashMap<>();
        Map<String, Instructor> instructorsToSave = new HashMap<>();
        List<Section> sectionsToSave = new ArrayList<>();
        List<Schedule> schedulesToSave = new ArrayList<>();

        for (CourseData cd : response.getData()) {
            String courseKey = cd.getCourseCode() + "-" + cd.getCourseNumber();
            Course course = new Course();
            course.setId(cd.getId());
            course.setCode(cd.getCourseCode());
            course.setNumber(cd.getCourseNumber());
            course.setTitle(cd.getTitle());

            if (cd.getSections() != null && !cd.getSections().isEmpty()) {
                SectionData firstSection = cd.getSections().getFirst();
                course.setCredits(firstSection.getCredits());
                course.setLevel(firstSection.getLevel());
            }
            coursesToSave.put(courseKey, course);

            if (cd.getSections() == null) continue;

            for (SectionData sd : cd.getSections()) {
                Instructor instructor = null;
                if (sd.getInstructorId() != null && !sd.getInstructorId().isBlank()) {
                    instructor = instructorsToSave.get(sd.getInstructorId());
                    if (instructor == null) {
                        instructor = new Instructor();
                        instructor.setId(sd.getInstructorId());
                        instructor.setName(sd.getInstructorId());
                        instructorsToSave.put(sd.getInstructorId(), instructor);
                    }
                }

                Section section = new Section();
                section.setId(sd.getId());
                section.setCrn(sd.getCrn());
                section.setTerm(term);
                section.setCourse(course);
                section.setInstructor(instructor);
                section.setCode(sd.getCode());
                section.setBranch(sd.getBranch());
                section.setScheduleType(sd.getScheduleType());
                section.setInstructionMethod(sd.getInstructionMethod());

                if (sd.getCreatedAt() != null) section.setCreatedAt(Instant.parse(sd.getCreatedAt()).atZone(ZoneOffset.UTC).toLocalDateTime());
                if (sd.getUpdatedAt() != null) section.setUpdatedAt(Instant.parse(sd.getUpdatedAt()).atZone(ZoneOffset.UTC).toLocalDateTime());

                sectionsToSave.add(section);

                if (sd.getSchedules() != null) {
                    for (ScheduleData schd : sd.getSchedules()) {
                        Schedule schedule = new Schedule();
                        schedule.setId(UUID.randomUUID().toString()); // Manual UUID generation for JDBC
                        schedule.setSection(section);
                        schedule.setType(schd.getType());
                        schedule.setStartTime(schd.getStartTime());
                        schedule.setEndTime(schd.getEndTime());
                        schedule.setRawTime(schd.getRawTime());
                        schedule.setDays(schd.getDays());
                        schedule.setLocation(schd.getLocation());
                        schedule.setDateRange(schd.getDateRange());
                        schedulesToSave.add(schedule);
                    }
                }
            }
        }

        log.info("[4/5] Memory structures completely built. Initiating raw JDBC Batch Blast to PostgreSQL...");

        int batchSize = 1500;

        // 1. Blast Instructors
        log.info("  -> Pushing {} Instructors...", instructorsToSave.size());
        jdbcTemplate.batchUpdate("INSERT INTO instructor (id, name, email) VALUES (?, ?, ?)",
                new ArrayList<>(instructorsToSave.values()), batchSize, (ps, inst) -> {
                    ps.setString(1, inst.getId());
                    ps.setString(2, inst.getName());
                    ps.setString(3, inst.getEmail());
                });

        // 2. Blast Courses
        log.info("  -> Pushing {} Courses...", coursesToSave.size());
        jdbcTemplate.batchUpdate("INSERT INTO course (id, code, number, title, credits, level) VALUES (?, ?, ?, ?, ?, ?)",
                new ArrayList<>(coursesToSave.values()), batchSize, (ps, c) -> {
                    ps.setString(1, c.getId());
                    ps.setString(2, c.getCode());
                    ps.setString(3, c.getNumber());
                    ps.setString(4, c.getTitle());
                    ps.setObject(5, c.getCredits());
                    ps.setString(6, c.getLevel());
                });

        // 3. Blast Sections
        log.info("  -> Pushing {} Sections...", sectionsToSave.size());
        jdbcTemplate.batchUpdate("INSERT INTO section (id, crn, term_id, course_id, instructor_id, code, branch, schedule_type, instruction_method, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sectionsToSave, batchSize, (ps, s) -> {
                    ps.setString(1, s.getId());
                    ps.setObject(2, s.getCrn());
                    ps.setString(3, s.getTerm().getId());
                    ps.setString(4, s.getCourse().getId());
                    ps.setString(5, s.getInstructor() != null ? s.getInstructor().getId() : null);
                    ps.setString(6, s.getCode());
                    ps.setString(7, s.getBranch());
                    ps.setString(8, s.getScheduleType());
                    ps.setString(9, s.getInstructionMethod());
                    ps.setTimestamp(10, s.getCreatedAt() != null ? Timestamp.valueOf(s.getCreatedAt()) : null);
                    ps.setTimestamp(11, s.getUpdatedAt() != null ? Timestamp.valueOf(s.getUpdatedAt()) : null);
                });

        // 4. Blast Schedules
        log.info("  -> Pushing {} Schedules...", schedulesToSave.size());
        jdbcTemplate.batchUpdate("INSERT INTO schedule (id, type, start_time, end_time, raw_time, days, location, date_range, section_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                schedulesToSave, batchSize, (ps, sch) -> {
                    ps.setString(1, sch.getId());
                    ps.setString(2, sch.getType());
                    ps.setObject(3, sch.getStartTime());
                    ps.setObject(4, sch.getEndTime());
                    ps.setString(5, sch.getRawTime());
                    ps.setString(6, sch.getDays());
                    ps.setString(7, sch.getLocation());
                    ps.setString(8, sch.getDateRange());
                    ps.setString(9, sch.getSection().getId());
                });

        log.info("[5/5] Phase 1 completed instantly via native network batching.");
    }

    private void loadInstructors() {
        log.info("[1/2] Fetching extended instructor details from API...");
        InstructorsApiResponse response = restTemplate.getForObject(instructorsUrl, InstructorsApiResponse.class);

        if (response == null || !"success".equals(response.getStatus()) || response.getData() == null) {
            log.warn("No valid instructor data received from API.");
            return;
        }

        log.info("[2/2] Downloaded {} updated instructor records. Executing targeted SQL updates...", response.getData().size());

        List<Instructor> instructorsToUpdate = new ArrayList<>();
        List<Section> sectionsToLink = new ArrayList<>();

        for (InstructorData id : response.getData()) {
            Instructor inst = new Instructor();
            inst.setId(id.getId());
            inst.setName(id.getName());
            inst.setEmail(id.getEmail());
            instructorsToUpdate.add(inst);

            if (id.getSections() != null) {
                for (SectionRef sr : id.getSections()) {
                    Section sec = new Section();
                    sec.setId(sr.getId());
                    sec.setInstructor(inst);
                    sectionsToLink.add(sec);
                }
            }
        }

        int batchSize = 1500;

        jdbcTemplate.batchUpdate("UPDATE instructor SET name = ?, email = ? WHERE id = ?",
                instructorsToUpdate, batchSize, (ps, inst) -> {
                    ps.setString(1, inst.getName());
                    ps.setString(2, inst.getEmail());
                    ps.setString(3, inst.getId());
                });

        jdbcTemplate.batchUpdate("UPDATE section SET instructor_id = ? WHERE id = ?",
                sectionsToLink, batchSize, (ps, sec) -> {
                    ps.setString(1, sec.getInstructor().getId());
                    ps.setString(2, sec.getId());
                });

        log.info("Done linking enhanced instructor data.");
    }

    // --- API DTOs Below Remain Unchanged ---

    @Data
    private static class CoursesApiResponse {
        private String status;
        private String termName;
        private String termId;
        private List<CourseData> data;
    }

    @Data
    private static class CourseData {
        private String id;
        private String courseCode;
        private String courseNumber;
        private String title;
        private List<SectionData> sections;
    }

    @Data
    private static class SectionData {
        private String id;
        private Integer crn;
        private String instructorId;
        private String code;
        private String branch;
        private String scheduleType;
        private String instructionMethod;
        private String level;
        private Integer credits;
        private String createdAt;
        private String updatedAt;
        private List<ScheduleData> schedules;
    }

    @Data
    private static class ScheduleData {
        private String type;
        private Integer startTime;
        private Integer endTime;
        private String rawTime;
        private String days;
        private String location;
        private String dateRange;
    }

    @Data
    private static class InstructorsApiResponse {
        private String status;
        private String termName;
        private String termId;
        private List<InstructorData> data;
    }

    @Data
    private static class InstructorData {
        private String id;
        private String name;
        private String email;
        private List<SectionRef> sections;
    }

    @Data
    private static class SectionRef {
        private String id;
    }
}