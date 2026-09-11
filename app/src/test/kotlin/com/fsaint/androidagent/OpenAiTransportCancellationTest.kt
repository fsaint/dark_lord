package com.fsaint.androidagent

import com.fsaint.androidagent.runtime.OpenAiHttpRequest
import java.net.ServerSocket
import kotlinx.coroutines.*
import kotlin.test.Test

class OpenAiTransportCancellationTest {
    @Test fun cancellationDoesNotWaitForSocketReadTimeout() = runBlocking { supervisorScope {
        val server = ServerSocket(0)
        val accepted = CompletableDeferred<java.net.Socket>()
        val serverJob = launch(Dispatchers.IO) { accepted.complete(server.accept()) }
        val clientJob = async(Dispatchers.IO) {
            UrlConnectionOpenAiTransport().execute(OpenAiHttpRequest("http://127.0.0.1:${server.localPort}/", "Bearer test", "{}", 5_000))
        }
        val socket = withTimeout(2_000) { accepted.await() }
        try { withTimeout(1_000) { clientJob.cancelAndJoin() } }
        finally { socket.close(); server.close(); serverJob.cancelAndJoin(); clientJob.cancelAndJoin() }
    } }
}
