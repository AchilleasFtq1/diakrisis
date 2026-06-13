package com.cy.diakritis.engine.band;

import com.cy.diakritis.common.dto.EventType;
import com.cy.diakritis.common.dto.Rail;

import java.util.Set;

/**
 * Maps a raw 0-100 score (plus rail and event type) onto a {@link Band}.
 *
 * <p>Band edges are {30, 60, 85}: {@code [0,30) ALLOW}, {@code [30,60) CONFIRM},
 * {@code [60,85) HOLD}, {@code [85,100] BLOCK}. Real-time rails (INSTANT, P2P) shift every edge
 * down by 8 because an irrevocable instant payment leaves no recall window, so the same score is
 * treated as riskier. Non-monetary actions are capped at CONFIRM — they move no money on their
 * own, so they should never silently HOLD/BLOCK at the band stage.
 */
public final class Bands {

    private static final int EDGE_CONFIRM = 30;
    private static final int EDGE_HOLD = 60;
    private static final int EDGE_BLOCK = 85;
    private static final int INSTANT_RAIL_SHIFT = 8;

    private static final Set<EventType> NON_MONETARY = Set.of(
            EventType.TERM_DEPOSIT_BREAK,
            EventType.BENEFICIARY_ADD,
            EventType.LIMIT_CHANGE
    );

    private Bands() {
    }

    /**
     * Band for {@code score} on {@code rail}. INSTANT and P2P subtract {@value #INSTANT_RAIL_SHIFT}
     * from each edge (no recall window on a real-time rail).
     */
    public static Band bandFor(int score, Rail rail) {
        int shift = (rail == Rail.INSTANT || rail == Rail.P2P) ? INSTANT_RAIL_SHIFT : 0;
        int confirmEdge = EDGE_CONFIRM - shift;
        int holdEdge = EDGE_HOLD - shift;
        int blockEdge = EDGE_BLOCK - shift;

        if (score >= blockEdge) {
            return Band.BLOCK;
        }
        if (score >= holdEdge) {
            return Band.HOLD;
        }
        if (score >= confirmEdge) {
            return Band.CONFIRM;
        }
        return Band.ALLOW;
    }

    /**
     * Cap a band at CONFIRM for non-monetary actions (TERM_DEPOSIT_BREAK, BENEFICIARY_ADD,
     * LIMIT_CHANGE). Monetary actions are returned unchanged.
     */
    public static Band capNonMonetary(Band band, EventType eventType) {
        if (NON_MONETARY.contains(eventType) && band.ordinal() > Band.CONFIRM.ordinal()) {
            return Band.CONFIRM;
        }
        return band;
    }

    /** True if this event type moves no money by itself and is subject to the CONFIRM cap. */
    public static boolean isNonMonetary(EventType eventType) {
        return NON_MONETARY.contains(eventType);
    }
}
