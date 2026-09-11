package com.megane14916.lifetimeline

import java.text.DateFormat
import java.util.Date

/** Formats persisted epoch milliseconds using the device locale and timezone. */
fun formatDeviceTimestamp(timestampMs: Long?): String =
  timestampMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "未実行"
