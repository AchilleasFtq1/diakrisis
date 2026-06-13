package com.cy.diakritis.ops.web.dto;

import java.util.List;

/**
 * A single page of a server-paged list response. {@code total} is the count <em>after</em> filtering
 * (so the console can render an accurate pager), {@code totalPages} is derived from {@code total} and
 * the clamped page size. Serialised snake_case ({@code total_pages}) by the global Jackson policy.
 *
 * @param items     the items on this page (never null; empty past the last page)
 * @param page      1-based page index actually served (clamped into range)
 * @param size      page size actually used (clamped to 1..{@value #MAX_SIZE})
 * @param total     total matching items across all pages
 * @param totalPages number of pages at this size (at least 1)
 */
public record Page<T>(List<T> items, int page, int size, long total, int totalPages) {

    /** Upper bound on page size, so a caller can't ask the server to materialise an unbounded page. */
    public static final int MAX_SIZE = 100;

    /** Slices an already-sorted, already-filtered list into the requested page, clamping page/size. */
    public static <T> Page<T> of(List<T> all, int page, int size) {
        int clampedSize = Math.max(1, Math.min(size, MAX_SIZE));
        int totalPages = all.isEmpty() ? 1 : (int) Math.ceil((double) all.size() / clampedSize);
        int clampedPage = Math.max(1, Math.min(page, totalPages));
        int from = (clampedPage - 1) * clampedSize;
        int to = Math.min(from + clampedSize, all.size());
        List<T> items = from >= all.size() ? List.of() : List.copyOf(all.subList(from, to));
        return new Page<>(items, clampedPage, clampedSize, all.size(), totalPages);
    }
}
