package com.capo.diarioclase.processing.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRetryPolicyTest {

    private val policy = ProviderRetryPolicy()

    @Test
    fun `transient failures retry once within budget`() {
        assertEquals(RetryDecision.RETRY, policy.decide(ProviderFailure.SERVER_UNAVAILABLE, requestsUsed = 1))
        assertEquals(RetryDecision.RETRY, policy.decide(ProviderFailure.TIMEOUT, requestsUsed = 1))
        // Sin presupuesto: se detiene el proveedor.
        assertEquals(RetryDecision.STOP_PROVIDER, policy.decide(ProviderFailure.SERVER_UNAVAILABLE, requestsUsed = 2))
    }

    @Test
    fun `invalid or empty responses allow one corrective request`() {
        assertEquals(RetryDecision.CORRECT, policy.decide(ProviderFailure.INVALID_RESPONSE, requestsUsed = 1))
        assertEquals(RetryDecision.CORRECT, policy.decide(ProviderFailure.EMPTY_RESPONSE, requestsUsed = 1))
        assertEquals(RetryDecision.STOP_PROVIDER, policy.decide(ProviderFailure.INVALID_RESPONSE, requestsUsed = 2))
    }

    @Test
    fun `quota auth and billing stop the provider and open its circuit`() {
        listOf(ProviderFailure.QUOTA, ProviderFailure.AUTHENTICATION, ProviderFailure.BILLING_RISK).forEach { code ->
            assertEquals("code $code", RetryDecision.STOP_PROVIDER, policy.decide(code, requestsUsed = 1))
            assertTrue("code $code abre circuito", policy.opensCircuit(code))
        }
    }

    @Test
    fun `no network goes directly to local`() {
        assertEquals(RetryDecision.STOP_ALL, policy.decide(ProviderFailure.NO_NETWORK, requestsUsed = 1))
        assertTrue(policy.opensCircuit(ProviderFailure.NO_NETWORK))
    }

    @Test
    fun `transient failures do not open the circuit`() {
        assertFalse(policy.opensCircuit(ProviderFailure.SERVER_UNAVAILABLE))
        assertFalse(policy.opensCircuit(ProviderFailure.TIMEOUT))
        assertFalse(policy.opensCircuit(ProviderFailure.INVALID_RESPONSE))
    }
}
