package org.yuktisetu.instituteservice.dto;

import jakarta.validation.constraints.Size;

/** Partial update; null means "leave alone". */
public record DegreePatchRequest(
        @Size(max = 32) String code,
        @Size(max = 64) String name
) {}
