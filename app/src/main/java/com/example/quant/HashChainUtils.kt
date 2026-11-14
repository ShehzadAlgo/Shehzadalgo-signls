package com.example.quant

import java.security.MessageDigest

object HashChainUtils {
    val GENESIS_HASH: String = "0".repeat(64)

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun computeEventHash(
        prevHash: String,
        sequenceIndex: Long,
        signalId: String,
        eventType: String,
        timestampIso: String,
        payloadSummary: String
    ): String {
        val canonical = "$prevHash|$sequenceIndex|$signalId|$eventType|$timestampIso|$payloadSummary"
        return sha256Hex(canonical)
    }
}
