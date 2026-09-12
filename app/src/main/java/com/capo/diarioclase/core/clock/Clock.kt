package com.capo.diarioclase.core.clock
fun interface Clock { fun nowEpochMs(): Long }
object SystemClock : Clock { override fun nowEpochMs() = System.currentTimeMillis() }

