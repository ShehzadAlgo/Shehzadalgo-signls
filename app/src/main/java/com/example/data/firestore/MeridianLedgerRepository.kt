package com.example.data.firestore

import android.content.Context
import com.example.R
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
import com.example.quant.HashChainUtils
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.UUID

class MeridianLedgerRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeRiskConfig(userId: String): Flow<RiskConfigRecord?> = flow {
        val path = "users/$userId/risk_config/default"
        emitAll(
            db.collection("users").document(userId)
                .collection("risk_config").document("default")
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            RiskConfigRecord::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.GET, path, userId)
                    throw error
                }
        )
    }

    fun observeSignals(userId: String): Flow<List<SignalRecord>> = flow {
        val path = "users/$userId/signals"
        emitAll(
            db.collection("users").document(userId)
                .collection("signals")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SignalRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeSignalEvents(userId: String): Flow<List<SignalEventRecord>> = flow {
        val path = "users/$userId/signal_events"
        emitAll(
            db.collection("users").document(userId)
                .collection("signal_events")
                .orderBy("sequenceIndex", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SignalEventRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    fun observeValidationReports(userId: String): Flow<List<ValidationReportRecord>> = flow {
        val path = "users/$userId/strategy_reports"
        emitAll(
            db.collection("users").document(userId)
                .collection("strategy_reports")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ValidationReportRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path, userId)
                    throw error
                }
        )
    }

    suspend fun saveRiskConfig(config: RiskConfig): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("risk_config").document("default")
        try {
            val existing = docRef.get().await()
            val existingCreatedAt = existing.getTimestamp("createdAt")
            val record = RiskConfigRecord.fromDomain(uid, config)
            docRef.set(record.toWriteMap(uid, existingCreatedAt)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun publishSignalWithEvent(
        signal: SignalRecord,
        docId: String = "sig_${UUID.randomUUID().toString().replace("-", "").take(16)}"
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val sigRef = db.collection("users").document(uid)
            .collection("signals").document(docId)

        try {
            val eventId = db.runTransaction { transaction ->
                val stateRef = db.collection("users").document(uid)
                    .collection("ledger_state").document("signal_events")
                val stateSnap = transaction.get(stateRef)
                val lastSequence = stateSnap.getLong("lastSequenceIndex") ?: -1L
                val prevHash = stateSnap.getString("lastEventHash") ?: HashChainUtils.GENESIS_HASH
                val nextSeq = lastSequence + 1L
                val timestampIso = signal.createdAtIso.ifBlank { Instant.now().toString() }
                val summary = "${signal.instrument} ${signal.direction.uppercase()} ${signal.strategy} ${signal.riskStatus} risk=${signal.suggestedRiskPct}%"
                val eventHash = HashChainUtils.computeEventHash(
                    prevHash = prevHash,
                    sequenceIndex = nextSeq,
                    signalId = docId,
                    eventType = "SIGNAL_CREATED",
                    timestampIso = timestampIso,
                    payloadSummary = summary
                )
                val evRef = db.collection("users").document(uid)
                    .collection("signal_events").document("ev_${nextSeq}_${UUID.randomUUID().toString().replace("-", "").take(8)}")
                val eventRecord = SignalEventRecord(
                    userId = uid,
                    signalId = docId,
                    sequenceIndex = nextSeq,
                    eventType = "SIGNAL_CREATED",
                    timestampIso = timestampIso,
                    payloadSummary = summary,
                    prevHash = prevHash,
                    eventHash = eventHash
                )

                transaction.set(
                    stateRef,
                    mapOf(
                        "userId" to uid,
                        "lastSequenceIndex" to nextSeq,
                        "lastEventHash" to eventHash,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                transaction.set(sigRef, signal.toCreateMap(uid))
                transaction.set(evRef, eventRecord.toCreateMap(uid))
                evRef.id
            }.await()
            eventId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, sigRef.path)
            throw e
        }
    }

    suspend fun updateSignalLifecycle(
        signalId: String,
        newStatus: SignalStatus,
        realizedR: Double,
        maeR: Double,
        mfeR: Double,
        durationMinutes: Long
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(signalId.isNotBlank()) { "Signal ID cannot be blank." }
        val sigRef = db.collection("users").document(uid)
            .collection("signals").document(signalId)

        try {
            db.runTransaction { transaction ->
                val stateRef = db.collection("users").document(uid)
                    .collection("ledger_state").document("signal_events")
                val stateSnap = transaction.get(stateRef)
                val lastSequence = stateSnap.getLong("lastSequenceIndex") ?: -1L
                val prevHash = stateSnap.getString("lastEventHash") ?: HashChainUtils.GENESIS_HASH
                val nextSeq = lastSequence + 1L
                val timestampIso = Instant.now().toString()
                val summary = "Status->${newStatus.wire} realizedR=${"%.2f".format(realizedR)} mae=${"%.2f".format(maeR)} mfe=${"%.2f".format(mfeR)}"
                val eventHash = HashChainUtils.computeEventHash(
                    prevHash = prevHash,
                    sequenceIndex = nextSeq,
                    signalId = signalId,
                    eventType = newStatus.wire,
                    timestampIso = timestampIso,
                    payloadSummary = summary
                )
                val eventId = "ev_${nextSeq}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
                val evRef = db.collection("users").document(uid)
                    .collection("signal_events").document(eventId)
                val eventRecord = SignalEventRecord(
                    userId = uid,
                    signalId = signalId,
                    sequenceIndex = nextSeq,
                    eventType = newStatus.wire,
                    timestampIso = timestampIso,
                    payloadSummary = summary,
                    prevHash = prevHash,
                    eventHash = eventHash
                )

                val updates = mapOf(
                    "status" to newStatus.wire,
                    "realizedR" to realizedR,
                    "maeR" to maeR,
                    "mfeR" to mfeR,
                    "durationMinutes" to durationMinutes,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                transaction.set(
                    stateRef,
                    mapOf(
                        "userId" to uid,
                        "lastSequenceIndex" to nextSeq,
                        "lastEventHash" to eventHash,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                transaction.update(sigRef, updates)
                transaction.set(evRef, eventRecord.toCreateMap(uid))
                Unit
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, sigRef.path)
            throw e
        }
    }

    suspend fun appendAuditEvent(
        signalId: String,
        eventType: String,
        payloadSummary: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        try {
            db.runTransaction { transaction ->
                val stateRef = db.collection("users").document(uid)
                    .collection("ledger_state").document("signal_events")
                val stateSnap = transaction.get(stateRef)
                val lastSequence = stateSnap.getLong("lastSequenceIndex") ?: -1L
                val prevHash = stateSnap.getString("lastEventHash") ?: HashChainUtils.GENESIS_HASH
                val nextSeq = lastSequence + 1L
                val timestampIso = Instant.now().toString()
                val eventHash = HashChainUtils.computeEventHash(
                    prevHash = prevHash,
                    sequenceIndex = nextSeq,
                    signalId = signalId,
                    eventType = eventType,
                    timestampIso = timestampIso,
                    payloadSummary = payloadSummary
                )
                val eventId = "ev_${nextSeq}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
                val evRef = db.collection("users").document(uid)
                    .collection("signal_events").document(eventId)
                val record = SignalEventRecord(
                    userId = uid,
                    signalId = signalId,
                    sequenceIndex = nextSeq,
                    eventType = eventType,
                    timestampIso = timestampIso,
                    payloadSummary = payloadSummary,
                    prevHash = prevHash,
                    eventHash = eventHash
                )
                transaction.set(
                    stateRef,
                    mapOf(
                        "userId" to uid,
                        "lastSequenceIndex" to nextSeq,
                        "lastEventHash" to eventHash,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                transaction.set(evRef, record.toCreateMap(uid))
                eventId
            }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/$uid/signal_events")
            throw e
        }
    }

    suspend fun saveValidationReport(report: ValidationReportRecord): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("strategy_reports").document(report.strategyId)
        try {
            val existing = docRef.get().await()
            val existingCreatedAt = existing.getTimestamp("createdAt")
            docRef.set(report.toWriteMap(uid, existingCreatedAt)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun getSignalsOnce(): Result<List<SignalRecord>> = runCatching {
        val uid = requireUserId()
        val ref = db.collection("users").document(uid).collection("signals")
        try {
            ref.get().await().toObjects(
                SignalRecord::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, ref.path)
            throw e
        }
    }

    private suspend fun getLatestEvent(uid: String): SignalEventRecord? {
        val snap = db.collection("users").document(uid)
            .collection("signal_events")
            .orderBy("sequenceIndex", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()
        return snap.toObjects(
            SignalEventRecord::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        ).firstOrNull()
    }

    private fun unwrapAndLog(
        error: Throwable,
        operationType: OperationType,
        path: String,
        expectedUid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == expectedUid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }
}
