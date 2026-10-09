package com.ivanocj.cameras.availability.application.model;

import java.util.Optional;

/** Retains the complete probe result when a check ran; skipped/unstarted codes have no probe result. */
public record BatchResult(BatchEntry entry, State state, Optional<ProbeResult> probeResult, String detail) {
    public enum State { CHECKED, SKIPPED, FAILED, CANCELLED }
}
