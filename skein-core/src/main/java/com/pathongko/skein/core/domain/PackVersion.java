package com.pathongko.skein.core.domain;

import com.pathongko.skein.common.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * The version of a modpack. Accepts both date-based ("2026.07.05-3") and
 * semver-like ("1.4.2") strings. Comparison is done on numeric segments, never
 * by comparing the raw strings (which would order "1.10" before "1.9").
 */
public record PackVersion(String raw) implements Comparable<PackVersion> {

    public PackVersion {
        Preconditions.requireNonBlank(raw, "raw");
    }

    public static PackVersion of(String raw) {
        return new PackVersion(raw);
    }

    /** Split into numeric segments, treating any run of non-digits as a separator. */
    private List<Long> segments() {
        List<Long> segs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                current.append(c);
            } else if (current.length() > 0) {
                segs.add(Long.parseLong(current.toString()));
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            segs.add(Long.parseLong(current.toString()));
        }
        return segs;
    }

    @Override
    public int compareTo(PackVersion other) {
        List<Long> a = this.segments();
        List<Long> b = other.segments();
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            long x = i < a.size() ? a.get(i) : 0;
            long y = i < b.size() ? b.get(i) : 0;
            int cmp = Long.compare(x, y);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return raw;
    }
}
