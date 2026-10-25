package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.TestedInfo
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Test build (0.3.5-test1): a TV whose list is a candidate gets the card before the plan, and the
// plan, the log and the TV report follow the person's answer.
@OptIn(ExperimentalCoroutinesApi::class)
class CandidateListsTest {
    private val candidate: Profile = miProfile.copy(
        id = "xiaomi-mitv-mssp1",
        tested = TestedInfo("candidate, issue #57 #58 #78 #176", "PI.5925", null, rebootTested = false, candidate = true),
    )

    private class Lists(val profile: Profile?) : CandidateLists {
        var tried: Boolean? = null
        val asked = mutableListOf<Pair<String, Boolean>>()
        override fun offer(facts: DeviceFacts): Profile? = profile
        override suspend fun choose(profileId: String, tryIt: Boolean) { asked += profileId to tryIt; tried = tryIt }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.toPlanOrCard(h: Harness, m: SetupMachine) {
        h.discovery.emit(DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    private fun harness(lists: Lists): Harness = Harness().apply {
        sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        // As BundledProfileSource with CandidateChoices: the candidate only after "try".
        profiles.resolver = { _, _ -> if (lists.tried == true) ProfileResolution.Exact(candidate) else ProfileResolution.None(emptyList()) }
    }

    @Test fun theCardComesBeforeThePlanAndTryingUsesTheCandidate() = runTest(timeout = 30.seconds) {
        val lists = Lists(candidate)
        val h = harness(lists)
        val m = SetupMachine(h.setupDeps(candidates = lists), backgroundScope)
        toPlanOrCard(h, m)
        assertIs<SetupState.Probing>(m.state.value)
        assertEquals(CandidateOffer("xiaomi-mitv-mssp1", "#57"), m.extras.value.candidate)
        assertTrue(h.planner.requests.none { it is PlanRequest.Setup }, "nothing is planned before the answer")
        assertTrue("setup: candidate list xiaomi-mitv-mssp1 offered" in h.logLines, h.logLines.toString())

        m.chooseCandidate(true)
        settle()
        assertIs<SetupState.Found>(m.state.value)
        assertNull(m.extras.value.candidate)
        assertTrue(m.extras.value.candidateTried)
        assertFalse(m.extras.value.unknownModel)
        assertEquals(listOf("xiaomi-mitv-mssp1" to true), lists.asked)
        assertTrue("setup: candidate list xiaomi-mitv-mssp1: trying the test list" in h.logLines, h.logLines.toString())
        assertTrue("setup: profile candidate xiaomi-mitv-mssp1" in h.logLines, h.logLines.toString())
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertEquals(ProfileResolution.Exact(candidate), setup.resolution)
        assertEquals("xiaomi-mitv-mssp1", h.tvs.tvs.value.single().profileId)
    }

    @Test fun stayingCarefulPlansWithoutTheCandidate() = runTest(timeout = 30.seconds) {
        val lists = Lists(candidate)
        val h = harness(lists)
        val m = SetupMachine(h.setupDeps(candidates = lists), backgroundScope)
        toPlanOrCard(h, m)
        m.chooseCandidate(false)
        settle()
        assertIs<SetupState.Found>(m.state.value)
        assertFalse(m.extras.value.candidateTried)
        assertTrue(m.extras.value.unknownModel)
        assertEquals(listOf("xiaomi-mitv-mssp1" to false), lists.asked)
        assertTrue("setup: candidate list xiaomi-mitv-mssp1: careful brand set instead" in h.logLines, h.logLines.toString())
        assertTrue("setup: profile none" in h.logLines, h.logLines.toString())
        assertIs<ProfileResolution.None>(h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().resolution)
        assertNull(h.tvs.tvs.value.single().profileId)
        // A second answer is ignored: the card is gone.
        m.chooseCandidate(true)
        settle()
        assertEquals(1, lists.asked.size)
    }

    @Test fun withoutACandidateNoCardIsShown() = runTest(timeout = 30.seconds) {
        val lists = Lists(null)
        val h = Harness().apply { sessions.allowPromptFor[HOST] = "AB:CD:7F:2A" }
        val m = SetupMachine(h.setupDeps(candidates = lists), backgroundScope)
        toPlanOrCard(h, m)
        assertIs<SetupState.Found>(m.state.value)
        assertNull(m.extras.value.candidate)
        assertTrue(h.logLines.none { "candidate list" in it }, h.logLines.toString())
    }

    @Test fun choicesAreKeptAndReadBack() = runTest {
        val store = MemoryDocumentStore()
        val choices = CandidateChoices(store)
        assertFalse(choices.tries("sony-bravia-vh1"))
        choices.save("sony-bravia-vh1", true)
        choices.save("xiaomi-mitv-mssp1", false)
        assertTrue(choices.tries("sony-bravia-vh1"))
        val again = CandidateChoices(store)
        assertTrue(again.tries("sony-bravia-vh1"))
        assertFalse(again.tries("xiaomi-mitv-mssp1"))
        assertEquals("sony-bravia-vh1=try\nxiaomi-mitv-mssp1=brand\n", store.read(CandidateChoices.PATH) { it.decodeToString() })
    }

    @Test fun theLogAndTheTvReportNameACandidate() {
        assertEquals("candidate xiaomi-mitv-mssp1", LogCodes.profile(ProfileResolution.Exact(candidate)))
        assertEquals("exact xiaomi-mitv-axso0", LogCodes.profile(ProfileResolution.Exact(miProfile)))
        val input = TvReportInput(AppAbout("0.3.5-test1", "full", "Phone", "Android 16"), Samples.snapshot(), ProfileResolution.Exact(candidate), null)
        val report = TvReport.text(input)
        assertTrue("Matching\nProfile: candidate xiaomi-mitv-mssp1\n" in report, report)
        assertEquals("#57", CandidateOffer.of(candidate).issue)
    }
}
