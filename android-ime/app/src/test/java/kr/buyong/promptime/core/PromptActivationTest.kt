package kr.buyong.promptime.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptActivationTest {
    @Test fun activatesAtTokenBoundary() {
        assertTrue(PromptActivation.shouldActivate("/", 'p', false))
        assertTrue(PromptActivation.shouldActivate("hello /", 'p', false))
    }

    @Test fun ignoresUrlAndPathLikeOccurrences() {
        assertFalse(PromptActivation.shouldActivate("https:/", 'p', false))
        assertFalse(PromptActivation.shouldActivate("/tmp/", 'p', false))
        assertFalse(PromptActivation.shouldActivate("/", 'p', true))
    }
}
