package com.skidreport.util;

/**
 * TailNumbers
 *
 * Canonical aircraft-identity extraction from a folder name.
 *
 * Every generator must resolve an aircraft to the SAME key so identities line
 * up across reports -- e.g. "N682P", "682P", and "N682P 2024 logs" all resolve
 * to the canonical "82P". The near-miss detector relies on this: an
 * inconsistent tail string is the only way a self-pair or a missed pair can
 * slip through.
 *
 * This mirrors the {@code ALL_TAIL_NUMBERS} list and {@code contains} matching
 * rule used by SkidReportGenerator and AttitudeEventReportGenerator so all
 * three modules canonicalize identically.
 */
public final class TailNumbers {

    /** Canonical 3-character tail codes for the KMDH Cessna 172S fleet. */
    public static final String[] ALL_TAIL_NUMBERS = {
            "41E","3FS","4FS","5FS","46A","47E","48A","49A",
            "13N","31T","41K","61J","83H","2JP",
            "06H","97B","59Y","78K","49K","29E","82P"
    };

    private TailNumbers() {}

    /**
     * Resolves a folder name to its canonical tail code, or {@code null} if the
     * folder does not correspond to a known fleet aircraft (first match wins,
     * matching the existing generators' behavior).
     */
    public static String detect(String folderName) {
        if (folderName == null) return null;
        for (String tail : ALL_TAIL_NUMBERS) {
            if (folderName.contains(tail)) return tail;
        }
        return null;
    }
}
