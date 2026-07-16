package io.zenwave360.asyncapi

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AsyncApiJsExportTest {
    @Test
    fun exportsPlainDocumentCollections() = runTest {
        val exported = parseAsyncApiText(
            """
            asyncapi: 3.0.0
            channels:
              events:
                messages:
                  Event:
                    name: Event
            """.trimIndent(),
        ).await()

        assertNotNull(exported)
        val value = exported.asDynamic()
        assertEquals("3", value.version.major.toString())
        assertEquals(1, value.channels.length as Int)
        assertEquals("events", value.channels[0].id as String)
    }
}
