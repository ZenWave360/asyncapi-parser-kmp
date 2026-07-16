package io.zenwave360.asyncapi

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.OnMissing
import io.zenwave360.jsonrefparser.model.RefParserOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsyncApiParserTest {
    @Test
    fun appliesV2MessageAndOperationTraitsAndPreservesSource() = runTest {
        val parser = AsyncApiParser.fromText(V2).dereference().applyTraits()
        val document = parser.getDocument()

        val operation = document.operationMessages("publishOrders")
        assertEquals(1, operation.size)
        assertEquals("Order", operation.single().id)
        assertEquals("/components/messages/OrderMessage", operation.single().pointer)
        assertEquals("/channels/orders/publish/message", operation.single().usagePointer)

        val effectiveOperation = document.atPointer("/channels/orders/publish") as Map<*, *>
        assertEquals("owned summary", effectiveOperation["summary"])
        assertEquals("first description", effectiveOperation["description"])
        assertEquals(mapOf("clientId" to "first", "later" to true), effectiveOperation["bindings"])
        assertFalse(effectiveOperation.containsKey("traits"))

        val effectiveMessage = document.atPointer("/components/messages/OrderMessage") as Map<*, *>
        assertEquals("Order", effectiveMessage["name"])
        assertEquals("from first message trait", effectiveMessage["title"])
        assertEquals(listOf("target"), effectiveMessage["tags"])
        assertFalse(effectiveMessage.containsKey("traits"))

        val sourceOperation = document.sourceNavigator().atPointer("/channels/orders/publish") as Map<*, *>
        assertTrue(sourceOperation.containsKey("traits"))
        val sourceMessage = document.sourceNavigator().atPointer("/components/messages/OrderMessage") as Map<*, *>
        assertTrue(sourceMessage.containsKey("traits"))
        assertNotSame(sourceOperation, effectiveOperation)

        val reusable = document.atPointer("/components/operationTraits/first") as Map<*, *>
        assertEquals("trait summary", reusable["summary"])

        val before = document.effectiveSchema.toString()
        parser.applyTraits()
        assertEquals(before, parser.getDocument().effectiveSchema.toString())
    }

    @Test
    fun appliesV3ChannelPrecedenceAndNavigation() = runTest {
        val document = AsyncApiParser.fromText(V3)
            .dereference()
            .applyTraits()
            .getDocument()

        val channel = document.channels().single()
        assertEquals("orders", channel.id)
        assertEquals("sendOrder", document.operations().single().id)
        assertEquals("send", document.operations().single().action)
        assertEquals("orders", document.operations().single().channelId)
        assertEquals(listOf("sendOrder"), document.channelOperations("orders").map { it.id })

        val messages = document.operationMessages("sendOrder")
        assertEquals(listOf("OrderMessage"), messages.map { it.id })
        assertEquals("/channels/orders/messages/OrderMessage", messages.single().pointer)
        assertEquals("/operations/sendOrder/messages/0", messages.single().usagePointer)

        val effectiveChannel = document.atPointer("/channels/orders") as Map<*, *>
        assertEquals("preferred title", effectiveChannel["title"])
        assertEquals("owned summary", effectiveChannel["summary"])
        assertNull(effectiveChannel["description"])
        assertFalse(effectiveChannel.containsKey("x-traits"))
        assertFalse(effectiveChannel.containsKey("traits"))

        val sourceChannel = document.sourceNavigator().atPointer("/channels/orders") as Map<*, *>
        assertTrue(sourceChannel.containsKey("x-traits"))
        assertTrue(sourceChannel.containsKey("traits"))

        assertEquals("component message", document.componentMessages().single().value["description"])
        assertEquals("channel message", document.channelMessages("orders").single().value["description"])
    }

    @Test
    fun xTraitsPresenceNeverFallsBackWhenMalformed() = runTest {
        val options = AsyncApiParserOptions(
            invalidTraitHandling = InvalidTraitHandling.COLLECT_AND_SKIP,
        )
        val document = AsyncApiParser.fromText(
            """
            asyncapi: 3.0.0
            channels:
              test:
                x-traits: null
                traits:
                  - title: must-not-apply
            """.trimIndent(),
            options = options,
        ).parse().applyTraits().getDocument()

        val channel = document.channels().single().value
        assertNull(channel["title"])
        assertFalse(channel.containsKey("x-traits"))
        assertFalse(channel.containsKey("traits"))
        assertEquals("ASYNCAPI_TRAIT_SELECTOR_NOT_ARRAY", document.parserDiagnostics.single().code)
    }

    @Test
    fun unresolvedTraitRefsRequireDereference() = runTest {
        val parser = AsyncApiParser.fromText(
            """
            asyncapi: 3.0.0
            operations:
              test:
                action: send
                traits:
                  - ${'$'}ref: '#/components/operationTraits/base'
            components:
              operationTraits:
                base:
                  summary: base
            """.trimIndent(),
        ).parse()

        val failure = assertFailsWith<AsyncApiTraitException> { parser.applyTraits() }
        assertEquals("ASYNCAPI_TRAIT_REF_UNRESOLVED", failure.diagnostic.code)
    }

    @Test
    fun collectsForbiddenChannelFieldsAndV2Attempts() = runTest {
        val options = AsyncApiParserOptions(
            refParserOptions = RefParserOptions(onMissing = OnMissing.SKIP),
            invalidTraitHandling = InvalidTraitHandling.COLLECT_AND_SKIP,
        )
        val v3 = AsyncApiParser.fromText(
            """
            asyncapi: 3.0.0
            channels:
              test:
                x-traits:
                  - address: forbidden
                    title: ignored-with-whole-trait
            """.trimIndent(),
            options = options,
        ).parse().applyTraits().getDocument()
        assertEquals("ASYNCAPI_CHANNEL_TRAIT_FORBIDDEN_FIELD", v3.parserDiagnostics.single().code)
        assertNull(v3.channels().single().value["title"])

        val v2 = AsyncApiParser.fromText(
            """
            asyncapi: 2.6.0
            channels:
              test:
                x-traits: []
            """.trimIndent(),
            options = options,
        ).parse().applyTraits().getDocument()
        assertEquals("ASYNCAPI_V2_CHANNEL_TRAITS", v2.parserDiagnostics.single().code)
    }

    @Test
    fun versionDiagnosticsDoNotPerformValidation() = runTest {
        val missing = AsyncApiParser.fromText("{}").parse().getDocument()
        assertEquals("ASYNCAPI_VERSION_MISSING", missing.parserDiagnostics.single().code)

        val unsupported = AsyncApiParser.fromText("asyncapi: 4.0.0-rc.1+build").parse().getDocument()
        assertEquals(4, unsupported.version.major)
        assertEquals("-rc.1+build", unsupported.version.suffix)
        assertEquals("ASYNCAPI_VERSION_UNSUPPORTED", unsupported.parserDiagnostics.single().code)
    }

    @Test
    fun resolvesCrossFileTraitsThroughCoreLoaders() = runTest {
        val documents = mapOf(
            "memory://root/asyncapi.yaml" to """
                asyncapi: 3.0.0
                operations:
                  external:
                    action: send
                    traits:
                      - ${'$'}ref: '#/components/operationTraits/external'
                components:
                  operationTraits:
                    external:
                      ${'$'}ref: './traits.yaml#/base'
            """.trimIndent(),
            "memory://root/traits.yaml" to """
                base:
                  summary: cross-file summary
            """.trimIndent(),
        )
        val loader = object : DocumentLoader {
            override fun canLoad(uri: String): Boolean = uri in documents
            override suspend fun load(uri: String): String =
                documents[uri] ?: error("Missing test document: $uri")
        }

        val document = AsyncApiParser("memory://root/asyncapi.yaml")
            .withLoaders(loader)
            .dereference()
            .applyTraits()
            .getDocument()

        assertEquals("cross-file summary", document.operations().single().value["summary"])
        assertTrue(document.source.resolvedRefs.any { it.targetUri == "memory://root/traits.yaml" })
    }

    @Test
    fun channelTraitsApplyToEveryV3VersionButNeverV2() = runTest {
        listOf("3.0.0", "3.0.1", "3.1.0-rc.1").forEach { version ->
            val document = AsyncApiParser.fromText(
                """
                asyncapi: $version
                channels:
                  test:
                    traits:
                      - title: applied
                """.trimIndent(),
            ).parse().applyTraits().getDocument()
            assertEquals("applied", document.channels().single().value["title"])
        }
    }
    @Test
    fun expandsV2OneOfMessageAlternativesAndKeepsUsagePointers() = runTest {
        val document = AsyncApiParser.fromText(
            """
            asyncapi: 2.6.0
            channels:
              choices:
                subscribe:
                  operationId: choose
                  message:
                    oneOf:
                      - name: First
                        traits:
                          - title: first title
                      - name: Second
            """.trimIndent(),
        ).parse().applyTraits().getDocument()

        val messages = document.operationMessages("choose")
        assertEquals(listOf("First", "Second"), messages.map { it.id })
        assertEquals(
            listOf(
                "/channels/choices/subscribe/message/oneOf/0",
                "/channels/choices/subscribe/message/oneOf/1",
            ),
            messages.map { it.pointer },
        )
        assertEquals("first title", messages.first().value["title"])
        assertFalse(messages.first().value.containsKey("traits"))
    }

    @Test
    fun pointerNavigationHandlesEscapedSegments() = runTest {
        val document = AsyncApiParser.fromText(
            """
            asyncapi: 3.0.0
            channels:
              "a/b~c":
                address: escaped
            """.trimIndent(),
        ).parse().getDocument()

        assertEquals("escaped", (document.atPointer("/channels/a~1b~0c") as Map<*, *>)["address"])
    }
    @Test
    fun keepsV3RootAndComponentDeclarationsDistinct() = runTest {
        val document = AsyncApiParser.fromText(
            """
            asyncapi: 3.0.0
            components:
              channels:
                componentChannel:
                  messages:
                    ComponentMessage:
                      name: ComponentMessage
              operations:
                componentOperation:
                  action: receive
                  channel:
                    ${'$'}ref: '#/components/channels/componentChannel'
                  messages:
                    - ${'$'}ref: '#/components/channels/componentChannel/messages/ComponentMessage'
            """.trimIndent(),
        ).dereference().applyTraits().getDocument()

        assertTrue(document.channels().isEmpty())
        assertTrue(document.operations().isEmpty())
        assertEquals("componentChannel", document.componentChannels().single().id)
        assertEquals("componentOperation", document.componentOperations().single().id)
        assertEquals(
            listOf("componentOperation"),
            document.channelOperations("componentChannel").map { it.id },
        )
        assertEquals(
            listOf("ComponentMessage"),
            document.operationMessages("componentOperation").map { it.id },
        )
    }
    private companion object {
        val V2 = """
            asyncapi: 2.6.0
            channels:
              orders:
                publish:
                  operationId: publishOrders
                  summary: owned summary
                  traits:
                    - ${'$'}ref: '#/components/operationTraits/first'
                    - ${'$'}ref: '#/components/operationTraits/second'
                  message:
                    ${'$'}ref: '#/components/messages/OrderMessage'
            components:
              operationTraits:
                first:
                  summary: trait summary
                  description: first description
                  bindings:
                    clientId: first
                second:
                  description: second description
                  bindings:
                    clientId: second
                    later: true
              messageTraits:
                first:
                  title: from first message trait
                  tags: [first]
                second:
                  title: from second message trait
                  tags: [second]
              messages:
                OrderMessage:
                  name: Order
                  tags: [target]
                  traits:
                    - ${'$'}ref: '#/components/messageTraits/first'
                    - ${'$'}ref: '#/components/messageTraits/second'
        """.trimIndent()

        val V3 = """
            asyncapi: 3.0.0
            channels:
              orders:
                address: orders
                summary: owned summary
                x-traits:
                  - ${'$'}ref: '#/components/x-channelTraits/preferred'
                traits:
                  - ${'$'}ref: '#/components/channelTraits/fallback'
                messages:
                  OrderMessage:
                    description: channel message
                    traits:
                      - ${'$'}ref: '#/components/messageTraits/base'
            operations:
              sendOrder:
                action: send
                channel:
                  ${'$'}ref: '#/channels/orders'
                messages:
                  - ${'$'}ref: '#/channels/orders/messages/OrderMessage'
                traits:
                  - ${'$'}ref: '#/components/operationTraits/base'
            components:
              x-channelTraits:
                preferred:
                  title: preferred title
              channelTraits:
                fallback:
                  description: must not apply
              operationTraits:
                base:
                  title: send title
              messageTraits:
                base:
                  title: message title
              messages:
                ComponentMessage:
                  description: component message
        """.trimIndent()
    }
}
