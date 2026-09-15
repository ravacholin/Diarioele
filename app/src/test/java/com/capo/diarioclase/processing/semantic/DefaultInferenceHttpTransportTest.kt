package com.capo.diarioclase.processing.semantic

import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultInferenceHttpTransportTest {

    /**
     * Una conexión que se cuelga en la escritura hasta que la desconectan. Modela un proveedor
     * que no responde: la E/S bloqueante no reacciona sola a la cancelación de la corrutina.
     */
    private class BlockingConnection(url: URL) : HttpsURLConnection(url) {
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)

        override fun getOutputStream(): OutputStream {
            started.countDown()
            disconnected.await(5, TimeUnit.SECONDS)
            throw IOException("conexión cancelada")
        }

        override fun disconnect() {
            disconnected.countDown()
        }

        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getCipherSuite(): String? = null
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate>? = null
    }

    @Test
    fun `cancelling the request disconnects the blocked connection within one second`() {
        val fake = BlockingConnection(URL("https://api.groq.com/openai/v1/chat/completions"))
        val transport = DefaultInferenceHttpTransport(openConnection = { fake })

        runBlocking {
            val job = launch(Dispatchers.IO) {
                transport.request(
                    url = "https://api.groq.com/openai/v1/chat/completions",
                    headers = emptyMap(),
                    body = "{}",
                )
            }
            assertTrue("la petición debería haber empezado", fake.started.await(2, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(
                "cancelar debería desconectar la conexión en menos de 1 s",
                fake.disconnected.await(1, TimeUnit.SECONDS),
            )
        }
    }
}
