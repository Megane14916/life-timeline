package com.megane14916.lifetimeline.domain

import java.security.SecureRandom

private const val ULID_LENGTH = 26
private const val MAX_ULID_TIMESTAMP = (1L shl 48) - 1
private const val ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

fun generateUlid(
  nowMs: Long = System.currentTimeMillis(),
  random: SecureRandom = SecureRandom(),
): String {
  require(nowMs in 0..MAX_ULID_TIMESTAMP) { "ULID timestamp must fit in 48 bits." }

  val result = CharArray(ULID_LENGTH)
  encodeTimestamp(nowMs, result)

  val randomBytes = ByteArray(10)
  random.nextBytes(randomBytes)
  for (symbolIndex in 0 until 16) {
    var symbol = 0
    repeat(5) { bitOffset ->
      val bitIndex = symbolIndex * 5 + bitOffset
      val bit = (randomBytes[bitIndex / 8].toInt() ushr (7 - bitIndex % 8)) and 1
      symbol = (symbol shl 1) or bit
    }
    result[10 + symbolIndex] = ULID_ALPHABET[symbol]
  }
  return result.concatToString()
}

/** Builds a reproducible ULID from a timestamp and at least 80 bits of caller-derived entropy. */
fun generateDeterministicUlid(
  timestampMs: Long,
  entropy: ByteArray,
): String {
  require(timestampMs in 0..MAX_ULID_TIMESTAMP) { "ULID timestamp must fit in 48 bits." }
  require(entropy.size >= 10) { "Deterministic ULID entropy must contain at least 80 bits." }

  val result = CharArray(ULID_LENGTH)
  encodeTimestamp(timestampMs, result)
  for (symbolIndex in 0 until 16) {
    var symbol = 0
    repeat(5) { bitOffset ->
      val bitIndex = symbolIndex * 5 + bitOffset
      val bit = (entropy[bitIndex / 8].toInt() ushr (7 - bitIndex % 8)) and 1
      symbol = (symbol shl 1) or bit
    }
    result[10 + symbolIndex] = ULID_ALPHABET[symbol]
  }
  return result.concatToString()
}

private fun encodeTimestamp(
  timestampMs: Long,
  result: CharArray,
) {
  var timestamp = timestampMs
  for (index in 9 downTo 0) {
    result[index] = ULID_ALPHABET[(timestamp and 31L).toInt()]
    timestamp = timestamp ushr 5
  }
}

fun validateUlid(
  value: String,
  fieldName: String = "id",
): String {
  require(value.length == ULID_LENGTH) { "$fieldName must be a 26-character ULID." }
  require(value[0] <= '7') { "$fieldName has an invalid timestamp prefix." }
  require(value.all { it in ULID_ALPHABET }) { "$fieldName contains an invalid ULID character." }
  return value
}
