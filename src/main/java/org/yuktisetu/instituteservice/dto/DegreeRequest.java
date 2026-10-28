package org.yuktisetu.instituteservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DegreeRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 64) String name
) {}
