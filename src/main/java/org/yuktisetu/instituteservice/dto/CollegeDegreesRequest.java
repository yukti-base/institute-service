package org.yuktisetu.instituteservice.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** The complete set of degrees a college offers. Replaces the previous set. */
public record CollegeDegreesRequest(@NotEmpty List<Long> degreeIds) {}
