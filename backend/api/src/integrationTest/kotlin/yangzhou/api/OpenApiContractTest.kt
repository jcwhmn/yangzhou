package yangzhou.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiContractTest : AbstractApiTest() {

    @Test
    fun `OpenAPI 契约可下载且覆盖全部端点族`() {
        val body = json.readTree(
            rest.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )

        val paths = body["paths"].fieldNames().asSequence().toList()
        assertTrue("/api/auth/bootstrap" in paths, "缺 auth bootstrap:$paths")
        assertTrue(paths.any { it.startsWith("/api/attributes") }, "缺 attributes:$paths")
        assertTrue(paths.any { it.startsWith("/api/capabilities") }, "缺 capabilities:$paths")
        assertTrue(paths.any { it.startsWith("/api/projects/{key}/items") }, "缺 items:$paths")
        assertTrue(paths.any { it.contains("feasibility") }, "缺 feasibility:$paths")
        assertEquals("3.1.0", body["openapi"].asText())
    }
}
