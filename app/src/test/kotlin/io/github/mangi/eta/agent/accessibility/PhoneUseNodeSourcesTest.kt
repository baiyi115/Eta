package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.*
import org.junit.Test

class PhoneUseNodeSourcesTest {
    private data class Source(val id: Int) { override fun hashCode() = 0 }
    @Test fun sourceEqualitySurvivesFreshCopiesEvenWhenAppUniqueIdIsAbsentAndHashesCollide() {
        val sources = PhoneUseNodeSources<Source>("service", { it.copy() })
        val first = sources.identify(7, 42, listOf(Source(1), Source(2)))
        assertNotEquals(first[0], first[1])
        assertEquals(first, sources.identify(7, 42, listOf(Source(1), Source(2))))
        assertEquals(first[1], sources.identify(7, 42, listOf(Source(2))).single())
    }
    @Test fun displayWindowAndServiceScopeChangesNeverReuseAnApprovalIdentity() {
        val sources = PhoneUseNodeSources<Source>("service", { it.copy() })
        val original = sources.identify(7, 42, listOf(Source(1))).single()
        assertNotEquals(original, sources.identify(8, 42, listOf(Source(1))).single())
        assertNotEquals(original, sources.identify(7, 43, listOf(Source(1))).single())
        sources.clear()
        assertNotEquals(original, sources.identify(7, 42, listOf(Source(1))).single())
        val reconnected = PhoneUseNodeSources<Source>("new-service", { it.copy() })
        assertNotEquals(original, reconnected.identify(7, 42, listOf(Source(1))).single())
    }
    @Test fun missingWindowsAndBoundedDisplayEvictionDiscardOldNodes() {
        val sources = PhoneUseNodeSources<Source>("service", { it.copy() }, maxDisplays = 1)
        val first = sources.identify(7, 42, listOf(Source(1))).single()
        sources.retainWindows(mapOf(7 to emptySet()))
        val fresh = sources.identify(7, 42, listOf(Source(1))).single()
        assertNotEquals(first, fresh)
        sources.identify(8, 43, listOf(Source(1)))
        assertNotEquals(fresh, sources.identify(7, 42, listOf(Source(1))).single())
        assertThrows(IllegalArgumentException::class.java) { sources.identify(7, 42, List(251) { Source(it) }) }
    }
}
