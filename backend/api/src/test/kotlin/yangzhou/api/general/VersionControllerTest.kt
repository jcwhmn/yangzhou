package yangzhou.api.general

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-101:版本号端点——classpath version.txt 构建期注入;测试环境走真实资源(占位或注入值),兜底 dev。 */
class VersionControllerTest {

    @Test
    fun `返回 version 字段且非空`() {
        val res = VersionController().version()
        assertEquals(setOf("version"), res.keys)
        assertTrue(res["version"]!!.isNotEmpty())
    }
}
