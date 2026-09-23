package org.yuktisetu.instituteservice.dto;

import org.yuktisetu.institute.model.TenantStatus;

import java.time.Instant;

public record DepartmentResponse(
        Long id,
        Long collegeId,
        String name,
        String code,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt
) {}
