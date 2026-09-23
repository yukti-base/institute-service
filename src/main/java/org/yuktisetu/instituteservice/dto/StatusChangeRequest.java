package org.yuktisetu.instituteservice.dto;

import jakarta.validation.constraints.NotNull;
import org.yuktisetu.institute.model.TenantStatus;

public record StatusChangeRequest(@NotNull TenantStatus status) {}
