package com.capo.diarioclase.data.db
import org.junit.Assert.*
import org.junit.Test
class DomainModelsTest{@Test fun transitions(){assertFalse(SessionState.FINALIZED.canTransitionTo(SessionState.RECORDING));assertTrue(SessionState.PAUSED.canTransitionTo(SessionState.RECORDING))}}
