package com.example.EdgeMemo.data.policy

import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.policy.PolicyInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPolicyEngineTest {

    private val engine = DefaultPolicyEngine(DefaultRedactionService())

    private fun input(
        title: String,
        content: String,
        type: MemoryType = MemoryType.NOTE,
        tags: List<String> = emptyList(),
        sensitivity: MemorySensitivity = MemorySensitivity.STANDARD,
        scope: String? = null,
        userSyncChoice: SyncDecision? = null,
    ) = PolicyInput(
        title = title,
        content = content,
        type = type,
        tags = tags,
        sensitivity = sensitivity,
        importance = 0,
        scope = scope,
        userSyncChoice = userSyncChoice,
        metadata = emptyMap(),
    )

    @Test
    fun gateCodeIsLocalOnlyWithAccessExplanation() {
        val decision = engine.evaluate(input("Site access", "Gate code for Site 7 is 4412"))
        assertEquals(SyncDecision.LOCAL_ONLY, decision.syncDecision)
        assertEquals("Detected access or credential information — memory stays on device.", decision.reason)
        assertNull(decision.redacted)
    }

    @Test
    fun credentialPhrasesAreLocalOnly() {
        val decision = engine.evaluate(input("Login", "Password for the dashboard is hunter2"))
        assertEquals(SyncDecision.LOCAL_ONLY, decision.syncDecision)
    }

    @Test
    fun restrictedSensitivityIsLocalOnly() {
        val decision = engine.evaluate(
            input("Vendor contract", "Quarterly pricing details", sensitivity = MemorySensitivity.RESTRICTED),
        )
        assertEquals(SyncDecision.LOCAL_ONLY, decision.syncDecision)
        assertEquals("Sensitivity is RESTRICTED — memory stays on device.", decision.reason)
    }

    @Test
    fun personalScopeIsLocalOnly() {
        val decision = engine.evaluate(input("Reminder", "Pick up milk", scope = "personal"))
        assertEquals(SyncDecision.LOCAL_ONLY, decision.syncDecision)
        assertEquals("Scope is personal — memory stays on device.", decision.reason)
    }

    @Test
    fun localOnlyTagIsRespected() {
        val decision = engine.evaluate(input("Draft", "Interview notes", tags = listOf("private")))
        assertEquals(SyncDecision.LOCAL_ONLY, decision.syncDecision)
    }

    @Test
    fun procedureIsSyncable() {
        val decision = engine.evaluate(
            input("Procedure revision 4", "Pump maintenance procedure revision 4", type = MemoryType.PROCEDURE),
        )
        assertEquals(SyncDecision.SYNC, decision.syncDecision)
        assertEquals("procedure memory is team-shareable.", decision.reason)
        assertNull(decision.redacted)
    }

    @Test
    fun documentChunksAreSyncable() {
        val decision = engine.evaluate(input("Manual", "Install steps", type = MemoryType.DOCUMENT))
        assertEquals(SyncDecision.SYNC, decision.syncDecision)
    }

    @Test
    fun sensitiveObservationSyncsRedacted() {
        val decision = engine.evaluate(
            input("Observation", "Noticed signs of wear near the drive end", type = MemoryType.OBSERVATION, sensitivity = MemorySensitivity.SENSITIVE),
        )
        assertEquals(SyncDecision.SYNC_REDACTED, decision.syncDecision)
        assertEquals("Sensitivity is SENSITIVE — only a redacted copy may synchronize.", decision.reason)
        assertNotNull(decision.redacted)
    }

    @Test
    fun repairWithIdentifierSyncsRedacted() {
        val decision = engine.evaluate(
            input("P-101 repair", "Replaced P-101 seal; root cause cavitation", type = MemoryType.REPAIR),
        )
        assertEquals(SyncDecision.SYNC_REDACTED, decision.syncDecision)
        assertNotNull(decision.redacted)
        // redaction must strip the identifier
        assertTrue(decision.redacted!!.content.doesNotContain("P-101"))
    }

    @Test
    fun identifierInNoteSyncsRedacted() {
        val decision = engine.evaluate(input("Bearing", "Use the SKF-6205 bearing here"))
        assertEquals(SyncDecision.SYNC_REDACTED, decision.syncDecision)
    }

    @Test
    fun genericNoteDefaultsToLocalOnly() {
        val decision = engine.evaluate(input("Note", "Went to the market today"))
        assertEquals(SyncDecision.LOCAL_ONLY, decision.syncDecision)
        assertEquals("No team-shareable signal detected — defaults to local only.", decision.reason)
    }

    @Test
    fun userChoiceSyncIsHonoredOnGenericNote() {
        val decision = engine.evaluate(input("Idea", "Replace belt drive with a gear drive", userSyncChoice = SyncDecision.SYNC))
        assertEquals(SyncDecision.SYNC, decision.syncDecision)
        assertTrue(decision.reason.startsWith("User explicitly requested SYNC"))
    }

    @Test
    fun hardLocalOnlyRuleBeatsUserSyncChoice() {
        val decision = engine.evaluate(
            input("Vault", "Gate code for Site 3 is 4719", userSyncChoice = SyncDecision.SYNC),
        )
        assertEquals("privacy invariant must win over user choice", SyncDecision.LOCAL_ONLY, decision.syncDecision)
    }

    @Test
    fun evaluationIsDeterministic() {
        val first = engine.evaluate(input("P-101 repair", "Seal cavitation at pump", type = MemoryType.REPAIR))
        val second = engine.evaluate(input("P-101 repair", "Seal cavitation at pump", type = MemoryType.REPAIR))
        assertEquals(first, second)
    }

    private fun String.doesNotContain(needle: String): Boolean = !contains(needle)
}