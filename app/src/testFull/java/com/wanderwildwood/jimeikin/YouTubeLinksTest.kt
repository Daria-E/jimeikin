package com.wanderwildwood.jimeikin

import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinksTest {
    @Test
    fun allChosenIsOn() {
        assertEquals(YouTubeLinks.State.ON, YouTubeLinks.stateOf(listOf(DOMAIN_STATE_SELECTED, DOMAIN_STATE_VERIFIED, DOMAIN_STATE_SELECTED)))
    }

    @Test
    fun noneChosenIsOff() {
        assertEquals(YouTubeLinks.State.OFF, YouTubeLinks.stateOf(List(5) { DOMAIN_STATE_NONE }))
    }

    @Test
    fun someChosenSaysSo() {
        assertEquals(YouTubeLinks.State.SOME, YouTubeLinks.stateOf(listOf(DOMAIN_STATE_SELECTED, DOMAIN_STATE_NONE)))
    }

    @Test
    fun noAddressesSaysNothing() {
        assertNull(YouTubeLinks.stateOf(emptyList()))
    }
}
