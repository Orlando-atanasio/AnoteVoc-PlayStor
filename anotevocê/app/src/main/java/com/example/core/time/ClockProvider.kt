package com.example.core.time

interface ClockProvider {
  fun nowMillis(): Long
}

class SystemClockProvider : ClockProvider {
  override fun nowMillis(): Long = System.currentTimeMillis()
}
