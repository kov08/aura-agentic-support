package org.aura.aura.resolver;

import java.util.List;

public record Resolution(String answer, List<String> sourcesUsed) {}
// Day 6 extends this (category/urgency/intent). Day 24 extends it (tokens/cost/model).
// Returning String today would mean refactoring every caller then. Pay the seam now.
