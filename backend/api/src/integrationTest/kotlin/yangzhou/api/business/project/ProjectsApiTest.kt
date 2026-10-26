package yangzhou.api.business.project

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectsApiTest : AbstractApiTest() {

    @Test
    fun `建项目自带默认四态 workflow,Done 终态`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "CHE", "chess")

        assertEquals("CHE", project["key"].asText())
        val names = project["statuses"].map { it["name"].asText() }
        assertEquals(listOf("To Do", "In Progress", "In Review", "QA", "Done"), names)
        assertTrue(project["statuses"][4]["isFinal"].asBoolean())
    }

    @Test
    fun `新项目默认种 workflow 规则 3 槽,closed_unmerged 留空(YPJ-36)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        val rules = json.readTree(
            authed.get().uri("/api/projects/CHE/workflow-rules").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )

        assertEquals(
            listOf("branch_created", "pr_opened", "pr_merged", "pr_closed_unmerged"),
            rules.map { it["eventType"].asText() },
        )
        assertEquals(
            listOf("In Progress", "In Review", "Done", null),
            rules.map { if (it["statusName"].isNull) null else it["statusName"].asText() },
        )
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
