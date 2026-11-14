package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.firestore.MeridianLedgerRepository
import com.example.data.firestore.SignalRecord
import com.example.data.firestore.ValidationReportRecord
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MeridianLedgerRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun sampleSignal(uid: String, suffix: String): SignalRecord = SignalRecord(
        userId = uid,
        signalUuid = "uuid-$suffix",
        createdAtIso = "2026-11-14T08:15:00Z",
        instrument = "EURUSD",
        assetClass = "fx",
        direction = "long",
        strategy = "S2_SESSION_BREAKOUT",
        strategyVersion = "1.0.0",
        configHash = "cfg_test_01",
        timeframe = "15m",
        regime = "LOW_VOL",
        conviction = 0.68,
        entryType = "zone",
        entryLow = 1.0842,
        entryHigh = 1.0848,
        stopLoss = 1.0815,
        tp1Price = 1.0890,
        tp1R = 1.5,
        tp1ClosePct = 50L,
        tp2Price = 1.0935,
        tp2R = 3.0,
        tp2ClosePct = 50L,
        suggestedRiskPct = 0.5,
        estCostR = 0.06,
        expiresAtIso = "2026-11-14T21:00:00Z",
        invalidation = "Close back inside Asian range",
        rationale = "London open breakout above Asian range.",
        riskStatus = "APPROVED",
        riskAdjustments = emptyList(),
        riskReasons = listOf("ALL_RISK_GATES_PASSED"),
        idempotencyKey = "S2|EURUSD|15m|$suffix",
        executionMode = "PAPER",
        status = "PENDING"
    )

    @Test
    fun publishSignalAndLifecycleUpdate_authenticatedOwner_succeedsAndHashChains() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = MeridianLedgerRepository(firestore)
        val suffix = UUID.randomUUID().toString().take(8)
        val docId = "sig_$suffix"

        val publishRes = withTimeout(TIMEOUT_MS) {
            repo.publishSignalWithEvent(sampleSignal(uid, suffix), docId)
        }
        assertTrue("Publish failed: ${publishRes.exceptionOrNull()}", publishRes.isSuccess)

        val updateRes = withTimeout(TIMEOUT_MS) {
            repo.updateSignalLifecycle(
                signalId = docId,
                newStatus = SignalStatus.WIN,
                realizedR = 2.15,
                maeR = -0.25,
                mfeR = 2.90,
                durationMinutes = 240L
            )
        }
        assertTrue("Update failed: ${updateRes.exceptionOrNull()}", updateRes.isSuccess)

        val events = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSignalEvents(uid).first { list -> list.any { it.signalId == docId && it.eventType == "WIN" } }
        }
        assertTrue(events.any { it.signalId == docId && it.eventType == "SIGNAL_CREATED" })
        assertTrue(events.any { it.signalId == docId && it.eventType == "WIN" })
    }

    @Test
    fun saveRiskConfigAndValidationReport_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = MeridianLedgerRepository(firestore)

        val riskRes = withTimeout(TIMEOUT_MS) {
            repo.saveRiskConfig(RiskConfig(accountBalanceUsd = 15_000.0, riskPerTradePct = 0.5))
        }
        assertTrue("Risk save failed: ${riskRes.exceptionOrNull()}", riskRes.isSuccess)

        val reportRes = withTimeout(TIMEOUT_MS) {
            repo.saveValidationReport(
                ValidationReportRecord(
                    userId = uid,
                    strategyId = "S1_TREND_DONCHIAN",
                    configHash = "cfg_s1",
                    dataSnapshotId = "snap_01",
                    lifecycleStatus = "PAPER",
                    oosTrades = 108L,
                    netExpectancyR = 0.21,
                    profitFactor = 1.38,
                    sharpe = 0.85,
                    deflatedSharpe = 0.69,
                    pbo = 0.15,
                    mc95DrawdownPct = 4.2,
                    ruinProbPct = 0.1,
                    positiveYearsPct = 75.0,
                    causalityPassed = true,
                    beatRandom95th = true,
                    equityCurveR = listOf(0.0, 1.2, 2.5)
                )
            )
        }
        assertTrue("Report save failed: ${reportRes.exceptionOrNull()}", reportRes.isSuccess)
    }

    @Test
    fun observeSignals_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repo = MeridianLedgerRepository(firestore)
        val suffix = UUID.randomUUID().toString().take(8)
        withTimeout(TIMEOUT_MS) {
            repo.publishSignalWithEvent(sampleSignal(aliceUid, suffix), "sig_$suffix").getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        var captured: Exception? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeSignals(aliceUid).first()
            }
        } catch (e: Exception) {
            captured = e
        }
        assertNotNull("Expected PERMISSION_DENIED exception", captured)
        val fsErr = captured?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException, got $captured", fsErr)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    @Test
    fun observeSignals_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = MeridianLedgerRepository(firestore)
        var captured: Exception? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeSignals("alice_uid_placeholder").first()
            }
        } catch (e: Exception) {
            captured = e
        }
        assertNotNull("Expected PERMISSION_DENIED exception", captured)
        val fsErr = captured?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException, got $captured", fsErr)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_meridian@test.com"
        const val BOB_EMAIL = "bob_meridian@test.com"
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3500L
    }
}
