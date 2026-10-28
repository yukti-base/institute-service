package org.yuktisetu.instituteservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers one question before a record is retired: what is still pointing at it?
 *
 * Soft-delete used to flip a flag and block nothing. Retiring PCCOE that way
 * left 3 departments, 168 role assignments and 4 drive targets referencing a
 * college that had vanished from every dropdown -- no error, no cascade, just a
 * hierarchy that no longer resolved. The TnP would discover it when a
 * coordinator could not be assigned or a drive targeted nobody.
 *
 * So deletion is now refused while anything live is attached, and the refusal
 * names the counts so an admin knows what to clear rather than guessing.
 *
 * WHY RAW SQL. institute-service maps only org.yuktisetu.institute.db --
 * user_role_assignments, posting_targets and placement_offers belong to other
 * contexts and are deliberately NOT on this service's classpath. Counting them
 * through JDBC keeps that boundary intact: this service asks the shared database
 * a numeric question, it does not gain the ability to load or mutate another
 * context's entities. Adding identity-dal here just to run a COUNT would drag
 * every identity entity onto the classpath permanently.
 *
 * Note the two different liveness flags below. Everything in the institute
 * hierarchy uses is_deleted; user_role_assignments uses is_active instead. Using
 * the wrong one silently counts zero and the guard waves the delete straight
 * through -- which is the failure this class exists to prevent, so each query
 * names its flag explicitly rather than sharing a helper.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstituteDependencyGuard {

    private final JdbcTemplate jdbc;

    /** A live reference that stands in the way of a delete. */
    public record Blocker(String label, long count) {
        @Override
        public String toString() {
            return count + " " + label + (count == 1 ? "" : "s");
        }
    }

    public List<Blocker> forTrust(Long trustId) {
        List<Blocker> blockers = new ArrayList<>(1);
        add(blockers, "active college",
                "SELECT count(*) FROM colleges WHERE trust_id = ? AND is_deleted = false", trustId);
        return blockers;
    }

    public List<Blocker> forCollege(Long collegeId) {
        List<Blocker> blockers = new ArrayList<>(4);
        add(blockers, "active department",
                "SELECT count(*) FROM departments WHERE college_id = ? AND is_deleted = false", collegeId);
        // is_active, not is_deleted -- see the class javadoc.
        add(blockers, "active role assignment",
                "SELECT count(*) FROM user_role_assignments WHERE college_id = ? AND is_active = true", collegeId);
        add(blockers, "drive target",
                "SELECT count(*) FROM posting_targets WHERE college_id = ? AND is_deleted = false", collegeId);
        add(blockers, "placement offer",
                "SELECT count(*) FROM placement_offers WHERE college_id = ? AND is_deleted = false", collegeId);
        return blockers;
    }

    /** What still uses a degree, anywhere -- so it cannot be switched off under live data. */
    public List<Blocker> forDegree(Long degreeId) {
        List<Blocker> blockers = new ArrayList<>(4);
        add(blockers, "department",
                "SELECT count(*) FROM departments WHERE degree_id = ? AND is_deleted = false", degreeId);
        add(blockers, "active role assignment",
                "SELECT count(*) FROM user_role_assignments WHERE degree_id = ? AND is_active = true", degreeId);
        add(blockers, "drive target",
                "SELECT count(*) FROM posting_targets WHERE degree_id = ? AND is_deleted = false", degreeId);
        return blockers;
    }

    /** What still uses this degree AT this college -- checked before the college stops offering it. */
    public List<Blocker> forCollegeDegree(Long collegeId, Long degreeId) {
        List<Blocker> blockers = new ArrayList<>(3);
        add2(blockers, "department",
                "SELECT count(*) FROM departments WHERE college_id = ? AND degree_id = ? AND is_deleted = false",
                collegeId, degreeId);
        add2(blockers, "active role assignment",
                "SELECT count(*) FROM user_role_assignments WHERE college_id = ? AND degree_id = ? AND is_active = true",
                collegeId, degreeId);
        add2(blockers, "drive target",
                "SELECT count(*) FROM posting_targets WHERE college_id = ? AND degree_id = ? AND is_deleted = false",
                collegeId, degreeId);
        return blockers;
    }

    public List<Blocker> forDepartment(Long departmentId) {
        List<Blocker> blockers = new ArrayList<>(2);
        add(blockers, "active role assignment",
                "SELECT count(*) FROM user_role_assignments WHERE dept_id = ? AND is_active = true", departmentId);
        add(blockers, "drive target",
                "SELECT count(*) FROM posting_targets WHERE department_id = ? AND is_deleted = false", departmentId);
        return blockers;
    }

    /** Renders blockers the way the admin reads them: "3 active departments and 168 active role assignments". */
    public static String describe(List<Blocker> blockers) {
        if (blockers.size() == 1) {
            return blockers.get(0).toString();
        }
        String head = blockers.subList(0, blockers.size() - 1).stream()
                .map(Blocker::toString).reduce((a, b) -> a + ", " + b).orElse("");
        return head + " and " + blockers.get(blockers.size() - 1);
    }

    private void add2(List<Blocker> blockers, String label, String sql, Long a, Long b) {
        Long count = jdbc.queryForObject(sql, Long.class, a, b);
        if (count != null && count > 0) {
            blockers.add(new Blocker(label, count));
        }
    }

    private void add(List<Blocker> blockers, String label, String sql, Long id) {
        Long count = jdbc.queryForObject(sql, Long.class, id);
        if (count != null && count > 0) {
            blockers.add(new Blocker(label, count));
        }
    }
}
