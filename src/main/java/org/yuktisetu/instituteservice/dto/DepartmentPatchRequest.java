package org.yuktisetu.instituteservice.dto;

import jakarta.validation.constraints.Size;

/** Partial update; null means "leave alone". See TrustPatchRequest for why this is separate from DepartmentRequest. */
public record DepartmentPatchRequest(
        @Size(max = 255) String name,
        @Size(max = 32) String code,
        Long degreeId
) {}
