package org.yuktisetu.instituteservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.institute.db.College;
import org.yuktisetu.institute.db.Degree;
import org.yuktisetu.institute.repository.DegreeRepository;
import org.yuktisetu.instituteservice.dto.CollegeDegreesRequest;
import org.yuktisetu.instituteservice.dto.DepartmentRequest;
import org.yuktisetu.institute.db.Department;
import org.yuktisetu.institute.db.Trust;
import org.yuktisetu.institute.model.TenantStatus;
import org.yuktisetu.institute.repository.CollegeRepository;
import org.yuktisetu.institute.repository.DepartmentRepository;
import org.yuktisetu.institute.repository.TrustRepository;
import org.yuktisetu.instituteservice.dto.CollegePatchRequest;
import org.yuktisetu.instituteservice.dto.DepartmentPatchRequest;
import org.yuktisetu.instituteservice.dto.TrustPatchRequest;
import org.yuktisetu.instituteservice.service.CollegeService;
import org.yuktisetu.instituteservice.service.DepartmentService;
import org.yuktisetu.instituteservice.service.TrustService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the update and delete verbs on the institute hierarchy.
 *
 * Two behaviours here are worth a real database rather than a unit test.
 *
 * The first is the delete guard. Before it existed, soft-deleting a college
 * flipped one flag and blocked nothing -- its departments, its staff role
 * assignments and any drive targeting it kept pointing at a row that had
 * vanished from every listing, with no error raised anywhere. The counts that
 * decide the refusal are read straight from the database by
 * InstituteDependencyGuard, including from tables this service does not map, so
 * there is nothing to assert against without one.
 *
 * The second is PATCH. Its entire purpose is that an absent field is left alone,
 * and the failure mode -- an omitted optional field being silently blanked -- is
 * only visible by reading the row back after the write.
 *
 * Seeds are deliberately tiny: a handful of rows per test, never a bulk fixture.
 */
@SpringBootTest
@ActiveProfiles("test")
class InstituteLifecycleIntegrationTest {

    @Autowired TrustService trustService;
    @Autowired CollegeService collegeService;
    @Autowired DepartmentService departmentService;
    @Autowired TrustRepository trustRepository;
    @Autowired CollegeRepository collegeRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired DegreeRepository degreeRepository;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** IT_ADMIN clears every gate, so authority never masks the behaviour under test. */
    private static final UserPrincipal ADMIN = new UserPrincipal(
            1L, "it@yuktisetu.test",
            List.of(new UserPrincipal.RoleClaim("IT_ADMIN", null, null)));

    /** A TNP_COORDINATOR holds none of the manage/restore/hard-delete authorities. */
    private static final UserPrincipal COORDINATOR = new UserPrincipal(
            2L, "coord@yuktisetu.test",
            List.of(new UserPrincipal.RoleClaim("TNP_COORDINATOR", null, null)));

    /**
     * The most senior role that is still NOT allowed to hard-delete. Super Admin
     * may do everything else at every tier -- create, edit, soft-delete, restore
     * -- which is exactly why permanent deletion needs a test of its own: the
     * natural refactor is to fold it in with restore, and nothing else in the
     * suite would notice.
     */
    private static final UserPrincipal SUPER_ADMIN = new UserPrincipal(
            3L, "super@yuktisetu.test",
            List.of(new UserPrincipal.RoleClaim("TNP_SUPER_ADMIN", null, null)));

    // ---------------------------------------------------------------- seeding

    private static String code(String prefix) {
        return prefix + SEQ.incrementAndGet() + UUID.randomUUID().toString().substring(0, 4);
    }

    private Trust aTrust() {
        Trust t = new Trust();
        t.setName("Test Trust");
        t.setCode(code("TR"));
        t.setStatus(TenantStatus.ACTIVE);
        return trustRepository.save(t);
    }

    private College aCollege(Trust trust) {
        College c = new College();
        c.setTrust(trust);
        c.setName("Test College");
        c.setCode(code("CO"));
        c.setStatus(TenantStatus.ACTIVE);
        c.setLogoUrl("https://example.test/logo.png");
        c.setAddress("Pune");
        c.setPrimaryContactName("Principal");
        c.setPrimaryContactEmail("principal@example.test");
        c.setPrimaryContactPhone("9876543210");
        c.getDegrees().add(btech());
        return collegeRepository.save(c);
    }

    private Department aDepartment(College college) {
        Department d = new Department();
        d.setCollege(college);
        d.setName("Computer Engineering");
        d.setCode(code("DP"));
        d.setDegree(btech());
        d.setStatus(TenantStatus.ACTIVE);
        return departmentRepository.save(d);
    }

    /** The catalogue is seeded on startup; fall back to creating it so the test never depends on that. */
    private Degree btech() {
        return degreeRepository.findByIsDeletedFalseOrderByNameAsc().stream()
                .filter(d -> d.getCode().equals("BTECH")).findFirst()
                .orElseGet(() -> {
                    Degree d = new Degree();
                    d.setCode("BTECH");
                    d.setName("B.Tech");
                    d.setStatus(TenantStatus.ACTIVE);
                    return degreeRepository.save(d);
                });
    }

    private Degree anotherDegree(String code) {
        return degreeRepository.findByIsDeletedFalseOrderByNameAsc().stream()
                .filter(d -> d.getCode().equals(code)).findFirst()
                .orElseGet(() -> {
                    Degree d = new Degree();
                    d.setCode(code);
                    d.setName(code);
                    d.setStatus(TenantStatus.ACTIVE);
                    return degreeRepository.save(d);
                });
    }

    // ------------------------------------------------------------ PATCH

    /**
     * The whole reason PATCH exists alongside PUT. An admin correcting the
     * contact phone must not lose the logo and address simply by not resending
     * them, which is exactly what PUT-as-full-replacement does.
     */
    @Test
    @DisplayName("patching one field leaves every other field untouched")
    void patchTouchesOnlyWhatWasSent() {
        College college = aCollege(aTrust());
        String originalCode = college.getCode();

        collegeService.patch(ADMIN, college.getId(),
                new CollegePatchRequest(null, null, null, null, null, null, "9000000001"));

        College after = collegeRepository.findById(college.getId()).orElseThrow();
        assertEquals("9000000001", after.getPrimaryContactPhone(), "the sent field changed");
        assertEquals("Test College", after.getName());
        assertEquals(originalCode, after.getCode());
        assertEquals("https://example.test/logo.png", after.getLogoUrl(),
                "an omitted optional field must survive -- blanking it is the bug PATCH prevents");
        assertEquals("Pune", after.getAddress());
        assertEquals("principal@example.test", after.getPrimaryContactEmail());
    }

    /** Null means "leave alone"; an explicit empty string is how a field is deliberately cleared. */
    @Test
    @DisplayName("an explicitly empty string clears an optional field")
    void emptyStringClearsAField() {
        College college = aCollege(aTrust());

        collegeService.patch(ADMIN, college.getId(),
                new CollegePatchRequest(null, null, "", null, null, null, null));

        assertNull(collegeRepository.findById(college.getId()).orElseThrow().getLogoUrl());
    }

    @Test
    @DisplayName("patching a required field to blank is rejected rather than stored")
    void blankRequiredFieldIsRejected() {
        College college = aCollege(aTrust());

        assertThrows(ApiException.class, () -> collegeService.patch(ADMIN, college.getId(),
                new CollegePatchRequest("   ", null, null, null, null, null, null)));

        assertEquals("Test College", collegeRepository.findById(college.getId()).orElseThrow().getName());
    }

    /** Codes are unique per trust, so re-sending a record's own code must not collide with itself. */
    @Test
    @DisplayName("re-sending a record's own code is not treated as a duplicate")
    void ownCodeIsNotADuplicate() {
        College college = aCollege(aTrust());

        assertDoesNotThrow(() -> collegeService.patch(ADMIN, college.getId(),
                new CollegePatchRequest("Renamed College", college.getCode(), null, null, null, null, null)));

        assertEquals("Renamed College", collegeRepository.findById(college.getId()).orElseThrow().getName());
    }

    @Test
    @DisplayName("patching to a code another college in the same trust already holds is refused")
    void duplicateCodeWithinTrustIsRefused() {
        Trust trust = aTrust();
        College first = aCollege(trust);
        College second = aCollege(trust);

        assertThrows(ApiException.class, () -> collegeService.patch(ADMIN, second.getId(),
                new CollegePatchRequest(null, first.getCode(), null, null, null, null, null)));
    }

    @Test
    @DisplayName("a trust patch behaves the same way")
    void trustPatchLeavesOtherFields() {
        Trust trust = aTrust();
        trust.setPrimaryContactEmail("trust@example.test");
        trust.setLogoUrl("https://example.test/t.png");
        trustRepository.save(trust);

        trustService.patch(ADMIN, trust.getId(),
                new TrustPatchRequest("Renamed Trust", null, null, null, null));

        Trust after = trustRepository.findById(trust.getId()).orElseThrow();
        assertEquals("Renamed Trust", after.getName());
        assertEquals("https://example.test/t.png", after.getLogoUrl());
        assertEquals("trust@example.test", after.getPrimaryContactEmail());
    }

    @Test
    @DisplayName("a department patch behaves the same way")
    void departmentPatchRenames() {
        Department dept = aDepartment(aCollege(aTrust()));

        departmentService.patch(ADMIN, dept.getId(), new DepartmentPatchRequest("Information Technology", null, null));

        Department after = departmentRepository.findById(dept.getId()).orElseThrow();
        assertEquals("Information Technology", after.getName());
        assertEquals(dept.getCode(), after.getCode(), "the omitted code is untouched");
    }

    @Test
    @DisplayName("a coordinator cannot patch a college")
    void patchRequiresAuthority() {
        College college = aCollege(aTrust());
        assertThrows(ApiException.class, () -> collegeService.patch(COORDINATOR, college.getId(),
                new CollegePatchRequest("Hijacked", null, null, null, null, null, null)));
    }

    // -------------------------------------------------- the delete guard

    /**
     * The failure this guard exists to prevent: the college disappears from
     * every listing while its departments, staff and drive targets keep
     * referencing it, and nothing anywhere reports a problem.
     */
    @Test
    @DisplayName("a college with an active department cannot be deleted")
    void collegeWithDepartmentIsProtected() {
        College college = aCollege(aTrust());
        aDepartment(college);

        ApiException e = assertThrows(ApiException.class,
                () -> collegeService.softDelete(ADMIN, college.getId()));

        assertTrue(e.getMessage().contains("1 active department"),
                "the refusal must name what is blocking it, not just fail: " + e.getMessage());
        assertFalse(collegeRepository.findById(college.getId()).orElseThrow().isDeleted());
    }

    /**
     * Staff assignments live in another bounded context that this service does
     * not map, which is precisely why they were invisible to the old check.
     */
    @Test
    @DisplayName("a college with an active staff assignment cannot be deleted")
    void collegeWithRoleAssignmentIsProtected() {
        College college = aCollege(aTrust());
        jdbc.update("INSERT INTO user_role_assignments (college_id, is_active) VALUES (?, true)", college.getId());

        ApiException e = assertThrows(ApiException.class,
                () -> collegeService.softDelete(ADMIN, college.getId()));
        assertTrue(e.getMessage().contains("1 active role assignment"), e.getMessage());
    }

    /** is_active=false, not is_deleted -- the one table that uses a different flag. */
    @Test
    @DisplayName("an INACTIVE staff assignment does not block the delete")
    void inactiveRoleAssignmentDoesNotBlock() {
        College college = aCollege(aTrust());
        jdbc.update("INSERT INTO user_role_assignments (college_id, is_active) VALUES (?, false)", college.getId());

        assertDoesNotThrow(() -> collegeService.softDelete(ADMIN, college.getId()));
    }

    @Test
    @DisplayName("a college targeted by a live drive cannot be deleted")
    void collegeWithDriveTargetIsProtected() {
        College college = aCollege(aTrust());
        jdbc.update("INSERT INTO posting_targets (college_id, is_deleted) VALUES (?, false)", college.getId());

        ApiException e = assertThrows(ApiException.class,
                () -> collegeService.softDelete(ADMIN, college.getId()));
        assertTrue(e.getMessage().contains("1 drive target"), e.getMessage());
    }

    @Test
    @DisplayName("the refusal names every blocker at once, not just the first")
    void refusalListsAllBlockers() {
        College college = aCollege(aTrust());
        aDepartment(college);
        jdbc.update("INSERT INTO user_role_assignments (college_id, is_active) VALUES (?, true)", college.getId());
        jdbc.update("INSERT INTO user_role_assignments (college_id, is_active) VALUES (?, true)", college.getId());

        ApiException e = assertThrows(ApiException.class,
                () -> collegeService.softDelete(ADMIN, college.getId()));

        assertTrue(e.getMessage().contains("1 active department"), e.getMessage());
        assertTrue(e.getMessage().contains("2 active role assignments"),
                "plural, and the real count -- an admin clearing one at a time needs the total: " + e.getMessage());
    }

    @Test
    @DisplayName("a trust with an active college cannot be deleted")
    void trustWithCollegeIsProtected() {
        Trust trust = aTrust();
        aCollege(trust);

        ApiException e = assertThrows(ApiException.class, () -> trustService.softDelete(ADMIN, trust.getId()));
        assertTrue(e.getMessage().contains("1 active college"), e.getMessage());
    }

    @Test
    @DisplayName("a department with an active staff assignment cannot be deleted")
    void departmentWithRoleAssignmentIsProtected() {
        Department dept = aDepartment(aCollege(aTrust()));
        jdbc.update("INSERT INTO user_role_assignments (dept_id, is_active) VALUES (?, true)", dept.getId());

        ApiException e = assertThrows(ApiException.class,
                () -> departmentService.softDelete(ADMIN, dept.getId()));
        assertTrue(e.getMessage().contains("1 active role assignment"), e.getMessage());
    }

    /** Clearing the blocker must actually unblock it, or the guard is a dead end. */
    @Test
    @DisplayName("deleting the department first then lets the college be deleted")
    void clearingTheBlockerUnblocksTheDelete() {
        College college = aCollege(aTrust());
        Department dept = aDepartment(college);

        assertThrows(ApiException.class, () -> collegeService.softDelete(ADMIN, college.getId()));

        departmentService.softDelete(ADMIN, dept.getId());
        assertDoesNotThrow(() -> collegeService.softDelete(ADMIN, college.getId()));

        assertTrue(collegeRepository.findById(college.getId()).orElseThrow().isDeleted());
    }

    // ------------------------------------------------ soft delete / restore

    @Test
    @DisplayName("a soft-deleted college leaves the active list and appears in the deleted list")
    void softDeleteMovesItBetweenLists() {
        Trust trust = aTrust();
        College college = aCollege(trust);

        collegeService.softDelete(ADMIN, college.getId());

        assertTrue(collegeService.listActiveForTrust(trust.getId()).stream()
                .noneMatch(c -> c.id().equals(college.getId())), "it must vanish from the working view");
        assertTrue(collegeService.listDeletedForTrust(trust.getId()).stream()
                .anyMatch(c -> c.id().equals(college.getId())), "and be recoverable from the deleted view");
    }

    @Test
    @DisplayName("restore puts it back in the active list")
    void restoreBringsItBack() {
        Trust trust = aTrust();
        College college = aCollege(trust);

        collegeService.softDelete(ADMIN, college.getId());
        collegeService.restore(ADMIN, college.getId());

        assertTrue(collegeService.listActiveForTrust(trust.getId()).stream()
                .anyMatch(c -> c.id().equals(college.getId())));
        assertFalse(collegeRepository.findById(college.getId()).orElseThrow().isDeleted());
    }

    @Test
    @DisplayName("a coordinator can neither delete nor restore")
    void deleteAndRestoreRequireAuthority() {
        College college = aCollege(aTrust());

        assertThrows(ApiException.class, () -> collegeService.softDelete(COORDINATOR, college.getId()));
        collegeService.softDelete(ADMIN, college.getId());
        assertThrows(ApiException.class, () -> collegeService.restore(COORDINATOR, college.getId()));
    }

    // ------------------------------------- department delete, college-scoped

    /** A College Admin's claim carries the specific college they are scoped to. */
    private static UserPrincipal collegeAdminFor(Long collegeId) {
        return new UserPrincipal(4L, "cadmin@yuktisetu.test",
                List.of(new UserPrincipal.RoleClaim("TNP_COLLEGE_ADMIN", collegeId, null)));
    }

    @Test
    @DisplayName("a college admin can delete a department in their own college")
    void collegeAdminDeletesOwnDepartment() {
        College college = aCollege(aTrust());
        Department dept = aDepartment(college);

        assertDoesNotThrow(() -> departmentService.softDelete(collegeAdminFor(college.getId()), dept.getId()));
        assertTrue(departmentRepository.findById(dept.getId()).orElseThrow().isDeleted());
    }

    /**
     * The containment that makes the widened authority safe. One person can hold
     * TNP_COLLEGE_ADMIN across several specific colleges, so the role alone is
     * never sufficient -- the claim must name THIS department's college. The
     * check reads the college off the loaded row, so a caller cannot widen their
     * own scope through the request.
     */
    @Test
    @DisplayName("a college admin cannot delete a department in a college they are not scoped to")
    void collegeAdminCannotReachAnotherCollege() {
        Trust trust = aTrust();
        College mine = aCollege(trust);
        College theirs = aCollege(trust);
        Department foreignDept = aDepartment(theirs);

        assertThrows(ApiException.class,
                () -> departmentService.softDelete(collegeAdminFor(mine.getId()), foreignDept.getId()));
        assertFalse(departmentRepository.findById(foreignDept.getId()).orElseThrow().isDeleted(),
                "a department in another college must be untouched");
    }

    @Test
    @DisplayName("a college admin still cannot restore the department they deleted")
    void collegeAdminCannotRestore() {
        College college = aCollege(aTrust());
        Department dept = aDepartment(college);
        UserPrincipal cadmin = collegeAdminFor(college.getId());

        departmentService.softDelete(cadmin, dept.getId());

        assertThrows(ApiException.class, () -> departmentService.restore(cadmin, dept.getId()),
                "undoing a deletion is trust-level authority -- see InstituteHierarchyPolicy");
    }

    @Test
    @DisplayName("a college admin still cannot hard-delete a department")
    void collegeAdminCannotHardDelete() {
        College college = aCollege(aTrust());
        Department dept = aDepartment(college);
        UserPrincipal cadmin = collegeAdminFor(college.getId());

        departmentService.softDelete(cadmin, dept.getId());

        assertThrows(ApiException.class, () -> departmentService.hardDelete(cadmin, dept.getId()));
        assertTrue(departmentRepository.findById(dept.getId()).isPresent());
    }

    /** Widening who may delete must not weaken what blocks a delete. */
    @Test
    @DisplayName("the dependency guard still applies to a college admin's delete")
    void collegeAdminIsStillBlockedByReferences() {
        College college = aCollege(aTrust());
        Department dept = aDepartment(college);
        jdbc.update("INSERT INTO user_role_assignments (dept_id, is_active) VALUES (?, true)", dept.getId());

        ApiException e = assertThrows(ApiException.class,
                () -> departmentService.softDelete(collegeAdminFor(college.getId()), dept.getId()));
        assertTrue(e.getMessage().contains("1 active role assignment"), e.getMessage());
    }

    @Test
    @DisplayName("a coordinator still cannot delete a department")
    void coordinatorCannotDeleteDepartment() {
        Department dept = aDepartment(aCollege(aTrust()));
        assertThrows(ApiException.class, () -> departmentService.softDelete(COORDINATOR, dept.getId()));
    }

    // ------------------------------------------------------- hard delete

    @Test
    @DisplayName("hard-delete is refused on a record that has not been soft-deleted first")
    void hardDeleteRequiresSoftDeleteFirst() {
        College college = aCollege(aTrust());

        assertThrows(ApiException.class, () -> collegeService.hardDelete(ADMIN, college.getId()));
        assertTrue(collegeRepository.findById(college.getId()).isPresent());
    }

    /**
     * Stricter than the soft-delete guard on purpose: ANY department row blocks
     * this, soft-deleted ones included, because the foreign key still points here
     * and the row is about to stop existing.
     */
    @Test
    @DisplayName("hard-delete is refused while even a soft-deleted department still references the college")
    void hardDeleteBlockedBySoftDeletedChild() {
        College college = aCollege(aTrust());
        Department dept = aDepartment(college);

        departmentService.softDelete(ADMIN, dept.getId());
        collegeService.softDelete(ADMIN, college.getId());

        assertThrows(ApiException.class, () -> collegeService.hardDelete(ADMIN, college.getId()));
        assertTrue(collegeRepository.findById(college.getId()).isPresent());
    }

    @Test
    @DisplayName("hard-delete removes a soft-deleted, childless college for good")
    void hardDeleteRemovesTheRow() {
        College college = aCollege(aTrust());
        collegeService.softDelete(ADMIN, college.getId());

        collegeService.hardDelete(ADMIN, college.getId());

        assertTrue(collegeRepository.findById(college.getId()).isEmpty());
    }

    @Test
    @DisplayName("a super admin can soft-delete a college but cannot hard-delete it")
    void collegeHardDeleteIsItAdminOnly() {
        College college = aCollege(aTrust());
        collegeService.softDelete(SUPER_ADMIN, college.getId());

        assertThrows(ApiException.class, () -> collegeService.hardDelete(SUPER_ADMIN, college.getId()));
        assertTrue(collegeRepository.findById(college.getId()).isPresent(),
                "permanent deletion is IT Admin only -- a super admin must not be able to reach it");
    }

    @Test
    @DisplayName("a super admin can soft-delete a trust but cannot hard-delete it")
    void trustHardDeleteIsItAdminOnly() {
        Trust trust = aTrust();
        trustService.softDelete(SUPER_ADMIN, trust.getId());

        assertThrows(ApiException.class, () -> trustService.hardDelete(SUPER_ADMIN, trust.getId()));
        assertTrue(trustRepository.findById(trust.getId()).isPresent());
    }

    @Test
    @DisplayName("a super admin can soft-delete a department but cannot hard-delete it")
    void departmentHardDeleteIsItAdminOnly() {
        Department dept = aDepartment(aCollege(aTrust()));
        departmentService.softDelete(SUPER_ADMIN, dept.getId());

        assertThrows(ApiException.class, () -> departmentService.hardDelete(SUPER_ADMIN, dept.getId()));
        assertTrue(departmentRepository.findById(dept.getId()).isPresent());
    }

    /** Restore stays open to both roles -- only permanent deletion is narrowed. */
    @Test
    @DisplayName("a super admin CAN restore at every tier")
    void superAdminCanStillRestore() {
        Trust trust = aTrust();
        College college = aCollege(trust);
        Department dept = aDepartment(college);

        departmentService.softDelete(SUPER_ADMIN, dept.getId());
        assertDoesNotThrow(() -> departmentService.restore(SUPER_ADMIN, dept.getId()));

        departmentService.softDelete(ADMIN, dept.getId());
        collegeService.softDelete(SUPER_ADMIN, college.getId());
        assertDoesNotThrow(() -> collegeService.restore(SUPER_ADMIN, college.getId()));
    }

    // ------------------------------------------------------------ degrees

    @Test
    @DisplayName("a department can only take a degree its college offers")
    void departmentDegreeMustBeOfferedByTheCollege() {
        College college = aCollege(aTrust());          // offers B.Tech only
        Degree mca = anotherDegree("MCA");

        var ex = assertThrows(RuntimeException.class, () -> departmentService.create(ADMIN,
                new DepartmentRequest(college.getId(), "Applications", code("AP"), mca.getId())));
        assertTrue(ex.getMessage().contains("does not offer"), ex.getMessage());

        var ok = departmentService.create(ADMIN,
                new DepartmentRequest(college.getId(), "Computer", code("CS"), btech().getId()));
        assertEquals("BTECH", ok.degreeCode());
    }

    @Test
    @DisplayName("a college cannot stop offering a degree one of its departments belongs to")
    void collegeCannotDropADegreeInUse() {
        College college = aCollege(aTrust());
        Degree mca = anotherDegree("MCA");
        collegeService.setDegrees(ADMIN, college.getId(),
                new CollegeDegreesRequest(List.of(btech().getId(), mca.getId())));
        departmentService.create(ADMIN, new DepartmentRequest(college.getId(), "Applications", code("AP"), mca.getId()));

        assertThrows(RuntimeException.class, () -> collegeService.setDegrees(ADMIN, college.getId(),
                new CollegeDegreesRequest(List.of(btech().getId()))), "MCA is still used by a department");

        // B.Tech is unused by any department here, so dropping it is fine.
        var after = collegeService.setDegrees(ADMIN, college.getId(),
                new CollegeDegreesRequest(List.of(mca.getId())));
        assertEquals(List.of("MCA"), after.degrees().stream().map(d -> d.code()).toList());
    }

    @Test
    @DisplayName("a department's degree cannot change once students or drives point at it")
    void departmentDegreeIsLockedWhileReferenced() {
        College college = aCollege(aTrust());
        Degree mca = anotherDegree("MCA");
        collegeService.setDegrees(ADMIN, college.getId(),
                new CollegeDegreesRequest(List.of(btech().getId(), mca.getId())));
        Department dept = aDepartment(college);

        // Free to change while nothing uses it ...
        departmentService.patch(ADMIN, dept.getId(), new DepartmentPatchRequest(null, null, mca.getId()));
        assertEquals("MCA", departmentService.get(dept.getId()).degreeCode());

        // ... and locked as soon as a student is assigned to it.
        jdbc.update("INSERT INTO user_role_assignments (dept_id, is_active) VALUES (?, true)", dept.getId());
        assertThrows(RuntimeException.class, () -> departmentService.patch(ADMIN, dept.getId(),
                new DepartmentPatchRequest(null, null, btech().getId())));
    }
}
