package org.yuktisetu.instituteservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.instituteservice.dto.DepartmentPatchRequest;
import org.yuktisetu.instituteservice.dto.DepartmentRequest;
import org.yuktisetu.instituteservice.dto.DepartmentResponse;
import org.yuktisetu.instituteservice.exception.InstituteExceptions;
import org.yuktisetu.instituteservice.policy.InstituteHierarchyPolicy;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.institute.db.College;
import org.yuktisetu.institute.db.Department;
import org.yuktisetu.institute.model.TenantStatus;
import org.yuktisetu.institute.repository.CollegeRepository;
import org.yuktisetu.institute.repository.DepartmentRepository;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final CollegeRepository collegeRepository;
    private final InstituteHierarchyPolicy policy;
    private final InstituteDependencyGuard dependencyGuard;

    @Transactional
    public DepartmentResponse create(UserPrincipal actor, DepartmentRequest req) {
        College college = collegeRepository.findByIdAndIsDeletedFalse(req.collegeId())
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("College", req.collegeId()));

        // Scope check happens AFTER we know which college, on purpose --
        // a TNP_COLLEGE_ADMIN's authority is scoped per-college, so we can't
        // evaluate "can this actor manage a department" without first
        // knowing which college it would belong to.
        if (!policy.canManageDepartment(actor, college.getId())) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }

        if (college.getStatus() != TenantStatus.ACTIVE) {
            throw new InstituteExceptions.ParentNotActiveException("College", college.getId());
        }

        String code = req.code().trim();
        if (departmentRepository.existsByCollegeIdAndCodeIgnoreCaseAndIsDeletedFalse(college.getId(), code)) {
            throw new InstituteExceptions.DuplicateCodeException("Department", code);
        }

        Department department = new Department();
        department.setCollege(college);
        department.setName(req.name().trim());
        department.setCode(code);
        department.setStatus(TenantStatus.ACTIVE);
        department.setCreatedBy(actor.userId());
        department.setUpdatedBy(actor.userId());

        Department saved = departmentRepository.save(department);
        log.info("Created department id={} code={} collegeId={} actorId={}", saved.getId(), saved.getCode(), college.getId(), actor.userId());
        return toResponse(saved);
    }

    public List<DepartmentResponse> listActiveForCollege(Long collegeId) {
        return departmentRepository.findByCollegeIdAndIsDeletedFalse(collegeId).stream().map(this::toResponse).toList();
    }

    /** The restore screen, scoped to one college. */
    public List<DepartmentResponse> listDeletedForCollege(Long collegeId) {
        return departmentRepository.findByCollegeIdAndIsDeletedTrue(collegeId).stream().map(this::toResponse).toList();
    }

    public DepartmentResponse get(Long id) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Department", id));
        return toResponse(department);
    }

    @Transactional
    public DepartmentResponse update(UserPrincipal actor, Long id, DepartmentRequest req) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Department", id));

        if (!policy.canManageDepartment(actor, department.getCollege().getId())) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }

        String code = req.code().trim();
        if (!code.equalsIgnoreCase(department.getCode())
                && departmentRepository.existsByCollegeIdAndCodeIgnoreCaseAndIsDeletedFalse(department.getCollege().getId(), code)) {
            throw new InstituteExceptions.DuplicateCodeException("Department", code);
        }

        department.setName(req.name().trim());
        department.setCode(code);
        department.setUpdatedBy(actor.userId());

        Department saved = departmentRepository.save(department);
        log.info("Updated department id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    /** Partial update; only fields actually present are touched. See TrustService.patch. */
    @Transactional
    public DepartmentResponse patch(UserPrincipal actor, Long id, DepartmentPatchRequest req) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Department", id));
        // Scoped check: a College Admin may edit departments in their own
        // college only, which is why the college id comes off the loaded row
        // rather than from the request.
        if (!policy.canManageDepartment(actor, department.getCollege().getId())) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }

        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) {
                throw new InstituteExceptions.InvalidRequestException("Department name cannot be blank.");
            }
            department.setName(name);
        }
        if (req.code() != null) {
            String code = req.code().trim();
            if (code.isEmpty()) {
                throw new InstituteExceptions.InvalidRequestException("Department code cannot be blank.");
            }
            if (!code.equalsIgnoreCase(department.getCode())
                    && departmentRepository.existsByCollegeIdAndCodeIgnoreCaseAndIsDeletedFalse(
                            department.getCollege().getId(), code)) {
                throw new InstituteExceptions.DuplicateCodeException("Department", code);
            }
            department.setCode(code);
        }

        department.setUpdatedBy(actor.userId());
        Department saved = departmentRepository.save(department);
        log.info("Patched department id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public DepartmentResponse setStatus(UserPrincipal actor, Long id, TenantStatus status) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Department", id));
        if (!policy.canManageDepartment(actor, department.getCollege().getId())) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        department.setStatus(status);
        department.setUpdatedBy(actor.userId());
        Department saved = departmentRepository.save(department);
        log.info("Department id={} status changed to={} actorId={}", saved.getId(), status, actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(UserPrincipal actor, Long id) {
        Department department = departmentRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Department", id));
        // Scoped, like create/update/status -- a TNP_COLLEGE_ADMIN may delete a
        // department in a college they are actually assigned to, and only there.
        // The college id comes off the loaded row rather than the request, so the
        // scope cannot be widened by what the caller sends.
        //
        // Deliberately NOT the same tier as restore and hard-delete, which stay
        // IT_ADMIN/TNP_SUPER_ADMIN: a college admin running their own department
        // list is day-to-day administration, while reversing a deletion or making
        // one permanent is not. The delete is guarded and reversible, so the worst
        // a college admin can do here is hide an empty department from their own
        // college until someone senior restores it.
        if (!policy.canManageDepartment(actor, department.getCollege().getId())) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        // Refuse rather than orphan -- staff are assigned at department level and
        // drives target departments directly, so a deleted department silently
        // breaks both.
        var blockers = dependencyGuard.forDepartment(id);
        if (!blockers.isEmpty()) {
            log.warn("Refused soft-delete of department id={} -- still referenced by {}", id, blockers);
            throw new InstituteExceptions.StillReferencedException(
                    "Department", department.getName(), InstituteDependencyGuard.describe(blockers));
        }

        department.setDeleted(true);
        department.setDeletedAt(Instant.now());
        department.setDeletedBy(actor.userId());
        departmentRepository.save(department);
        log.info("Soft-deleted department id={} code={} actorId={}", department.getId(), department.getCode(), actor.userId());
    }

    @Transactional
    public DepartmentResponse restore(UserPrincipal actor, Long id) {
        if (!policy.canRestore(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Department", id));
        department.setDeleted(false);
        department.setDeletedAt(null);
        department.setDeletedBy(null);
        department.setUpdatedBy(actor.userId());
        Department saved = departmentRepository.save(department);
        log.info("Restored department id={} code={} actorId={}", saved.getId(), saved.getCode(), actor.userId());
        return toResponse(saved);
    }

    @Transactional
    public void hardDelete(UserPrincipal actor, Long id) {
        if (!policy.canHardDelete(actor)) {
            throw new InstituteExceptions.InsufficientAuthorityException();
        }
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new InstituteExceptions.NotFoundException("Department", id));
        if (!department.isDeleted()) {
            throw new InstituteExceptions.NotYetDeactivatedException();
        }
        // No further child tier below Department in institute-dal today --
        // if/when a Department-scoped entity is added later, add the same
        // existsByDepartmentId guard used at the Trust/College tiers above.
        departmentRepository.delete(department);
        log.info("Hard-deleted department id={} code={} actorId={}", department.getId(), department.getCode(), actor.userId());
    }

    private DepartmentResponse toResponse(Department d) {
        return new DepartmentResponse(
                d.getId(), d.getCollege().getId(), d.getName(), d.getCode(),
                d.getStatus(), d.getCreatedAt(), d.getUpdatedAt()
        );
    }
}
