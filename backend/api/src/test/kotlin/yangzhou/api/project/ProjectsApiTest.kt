package yangzhou.api.project

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectsApiTest : AbstractApiTest() {

    @Test
    fun `建项目自带默认三态 workflow,Done 终态`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "CHE", "chess")

        assertEquals("CHE", project["key"].asText())
        val names = project["statuses"].map { it["name"].asText() }
        assertEquals(listOf("To Do", "In Progress", "Done"), names)
        assertTrue(project["statuses"][2]["isFinal"].asBoolean())
    }

    @Test
    fun `重复 key 409,非法 key 400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        authed.post().uri("/api/projects")
            .body(mapOf("key" to "CHE", "name" to "again"))
            .exchange().expectStatus().isEqualTo(409)

        authed.post().uri("/api/projects")
            .body(mapOf("key" to "che", "name" to "lower"))
            .exchange().expectStatus().isBadRequest()
    }
}
