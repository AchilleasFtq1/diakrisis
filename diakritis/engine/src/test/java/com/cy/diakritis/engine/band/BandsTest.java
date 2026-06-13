package com.cy.diakritis.engine.band;

import com.cy.diakritis.common.dto.EventType;
import com.cy.diakritis.common.dto.Rail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies band-edge mapping, the instant-rail shift and the non-monetary CONFIRM cap. */
class BandsTest {

    @Test
    void sepaEdgesAreThirtySixtyEightyFive() {
        assertEquals(Band.ALLOW, Bands.bandFor(0, Rail.SEPA));
        assertEquals(Band.ALLOW, Bands.bandFor(29, Rail.SEPA));
        assertEquals(Band.CONFIRM, Bands.bandFor(30, Rail.SEPA));
        assertEquals(Band.CONFIRM, Bands.bandFor(59, Rail.SEPA));
        assertEquals(Band.HOLD, Bands.bandFor(60, Rail.SEPA));
        assertEquals(Band.HOLD, Bands.bandFor(84, Rail.SEPA));
        assertEquals(Band.BLOCK, Bands.bandFor(85, Rail.SEPA));
        assertEquals(Band.BLOCK, Bands.bandFor(100, Rail.SEPA));
    }

    @Test
    void instantAndP2pRailsSubtractEightFromEveryEdge() {
        // CONFIRM edge moves 30 → 22, HOLD edge 60 → 52, BLOCK edge 85 → 77.
        assertEquals(Band.ALLOW, Bands.bandFor(21, Rail.INSTANT));
        assertEquals(Band.CONFIRM, Bands.bandFor(22, Rail.INSTANT));
        assertEquals(Band.HOLD, Bands.bandFor(52, Rail.INSTANT));
        assertEquals(Band.BLOCK, Bands.bandFor(77, Rail.INSTANT));

        assertEquals(Band.CONFIRM, Bands.bandFor(22, Rail.P2P));
        assertEquals(Band.HOLD, Bands.bandFor(52, Rail.P2P));
        assertEquals(Band.BLOCK, Bands.bandFor(77, Rail.P2P));
    }

    @Test
    void nonMonetaryActionsAreCappedAtConfirm() {
        assertEquals(Band.CONFIRM, Bands.capNonMonetary(Band.HOLD, EventType.TERM_DEPOSIT_BREAK));
        assertEquals(Band.CONFIRM, Bands.capNonMonetary(Band.BLOCK, EventType.BENEFICIARY_ADD));
        assertEquals(Band.CONFIRM, Bands.capNonMonetary(Band.CONFIRM, EventType.LIMIT_CHANGE));
        assertEquals(Band.ALLOW, Bands.capNonMonetary(Band.ALLOW, EventType.LIMIT_CHANGE));
    }

    @Test
    void monetaryActionsAreNotCapped() {
        assertEquals(Band.HOLD, Bands.capNonMonetary(Band.HOLD, EventType.TRANSFER));
        assertEquals(Band.BLOCK, Bands.capNonMonetary(Band.BLOCK, EventType.P2P_TRANSFER));
    }
}
