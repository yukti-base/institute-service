package org.yuktisetu.instituteservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.yuktisetu.instituteservice.dto.DepartmentPatchRequest;
import org.yuktisetu.instituteservice.dto.DepartmentRequest;
import org.yuktisetu.instituteservice.dto.DepartmentResponse;
import org.yuktisetu.instituteservice.dto.StatusChangeRequest;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.instituteservice.service.DepartmentService;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    // TNP_COLLEGE_ADMIN included at the coarse gate, unlike CollegeController --
    // the fine-grained "which college are they actually scoped to" check
    // happens inside DepartmentService via InstituteHierarchyPolicy.canManageDepartment,
    // this annotation only screens out roles that can NEVER touch a department
    // (Student, Ground Volunteer, etc.).
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<DepartmentResponse>> create(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestBody @Valid DepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(departmentService.create(actor, request), 201, "Department created"));
    }

    @GetMapping
    public ResponseEntity<YuktiSetuResponse<List<DepartmentResponse>>> listForCollege(@RequestParam Long collegeId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(departmentService.listActiveForCollege(collegeId)));
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<List<DepartmentResponse>>> listDeleted(@RequestParam Long collegeId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                departmentService.listDeletedForCollege(collegeId), "Deleted departments fetched"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<YuktiSetuResponse<DepartmentResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(departmentService.get(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<DepartmentResponse>> update(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid DepartmentRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(departmentService.update(actor, id, request), "Department updated"));
    }

    /**
     * Partial update -- the portal's inline edit uses this. PUT remains a full
     * replacement; sending PATCH with only the changed fields is what stops an
     * omitted optional field being blanked.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<DepartmentResponse>> patch(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid DepartmentPatchRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                departmentService.patch(actor, id, request), "Department updated"));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<DepartmentResponse>> setStatus(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id,
            @RequestBody @Valid StatusChangeRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(departmentService.setStatus(actor, id, request.status()), "Department status updated"));
    }

    // Soft-delete admits TNP_COLLEGE_ADMIN, matching create/update/status.
    // This coarse gate only checks that the caller holds the role at all --
    // DepartmentService is what confirms they hold it for THIS department's
    // college, which is the check that actually contains them.
    //
    // Restore and hard-delete below stay IT_ADMIN/TNP_SUPER_ADMIN: undoing a
    // deletion or making one permanent is trust-level authority, not day-to-day
    // college administration.
    @PostMapping("/{id}/soft-delete")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<Void>> softDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        departmentService.softDelete(actor, id);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "Department soft-deleted"));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<DepartmentResponse>> restore(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        return ResponseEntity.ok(YuktiSetuResponse.success(departmentService.restore(actor, id), "Department restored"));
    }

    @PostMapping("/{id}/hard-delete")
    @PreAuthorize("hasAuthority('ROLE_IT_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<Void>> hardDelete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
        departmentService.hardDelete(actor, id);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "Department hard-deleted"));
    }
}
