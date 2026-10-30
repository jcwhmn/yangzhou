package yangzhou.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 进程级冒烟(spec 0088):跑构建出的 yz fat-jar,零网络用例,防"加命令忘 usage / 发版未运行"。
 * 锚点 = 主干命令集;新命令合并时须同步加锚点(YPJ-115 合并接力:sprints move / items assign)。
 * 子进程经 -Dfile.encoding=UTF-8 与 Main 的 UTF-8 PrintStream 输出,父进程按 UTF-8 解码——
 * 中文断言同时充当"判定行不乱码"的编码回归。
 */
class SmokeTest {

    private val jar: File = File("build/libs").listFiles { f -> f.name.startsWith("yz-") && f.name.endsWith(".jar") }
        ?.singleOrNull()
        ?: error("build/libs 下未找到唯一的 yz-*.jar,先运行 gradle :cli:jar")

    /** shortcut: java 取自 PATH(CI setup-java 与本地均已装),toolchain 对齐靠环境 */
    private fun run(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder("java", "-Dfile.encoding=UTF-8", "-jar", jar.absolutePath, *args)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return p.exitValue() to out
    }

    @Test
    fun `help lists trunk commands`() {
        val (code, out) = run("--help")
        assertEquals(0, code, "--help 应退出 0")
        // usage 完整性锚点:主干命令集;新命令合并时同步追加(YPJ-115: sprints move / items assign)
        for (anchor in listOf(
            "login", "attrs create", "members add", "teams add", "projects create", "projects delete",
            "items create", "items list", "items move", "items branch", "sprints list",
            "caps set", "caps rm", "caps list", "feasibility", "candidates", "assign", "log", "export", "import",
        )) {
            assertTrue(out.contains(anchor), "usage 缺:$anchor")
        }
    }

    @Test
    fun `unknown command exits nonzero with hint`() {
        val (code, out) = run("nosuchcmd")
        assertTrue(code != 0, "未知命令应非 0 退出(YPJ-119 修复:原 exit 0)")
        assertTrue(out.contains("未知命令"), "应报未知命令(同时验证 UTF-8 判定行)")
    }

    @Test
    fun `missing args fail before any network`() {
        val (code, out) = run("members", "set")
        assertTrue(code != 0, "缺参应非 0 退出")
        assertTrue(out.contains("缺少成员名"), "应报缺少成员名(参数校验先于网络)")
    }

    @Test
    fun `missing subcommand arg exits nonzero`() {
        val (code, _) = run("members", "bogus")
        assertTrue(code != 0, "未知子命令应非 0 退出")
    }
}
