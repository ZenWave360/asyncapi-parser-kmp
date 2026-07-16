package io.zenwave360.asyncapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class JavaAsyncApiParserTest {
    @Test
    fun blockingFacadeExposesTraitsAndNavigation() {
        val document = JavaAsyncApiParser.fromText(
            """
            asyncapi: 3.0.0
            channels:
              events:
                messages:
                  Event:
                    name: Event
            operations:
              emit:
                action: send
                channel:
                  ${'$'}ref: '#/channels/events'
                messages:
                  - ${'$'}ref: '#/channels/events/messages/Event'
              emitFromChannel:
                action: send
                channel:
                  ${'$'}ref: '#/channels/events'
            """.trimIndent(),
        ).dereference().applyTraits().getDocument()

        assertEquals("events", document.channels().single().id)
        assertEquals("Event", document.operationMessages("emit").single().id)
        assertEquals("Event", document.operationMessages("emitFromChannel").single().id)
        val message = document.operationMessages("emit").single().value
        val messageRef = document.effective.resolvedRefs.first { it.refString.endsWith("/Event") }
        assertSame(message, messageRef.replacedValue)
    }
}
