package yangzhou.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-273:boardorder 步进算术——净步进恰好 |current-target| 步,方向正确,零步进时为空(末次"取快照"调用即 bug 根因,禁复现) */
class BoardorderMovesTest {
    @Test
    fun `向上到首尾边界`() {
        assertEquals(List(30) { "up" }, boardorderMoves(31, 1)) // YPJ-248 实跑场景
        assertEquals(listOf("down"), boardorderMoves(1, 2)) // YPJ-257 修复后场景(此前多走一步 up)
    }

    @Test
    fun `零步进_原地不动`() {
        assertEquals(emptyList(), boardorderMoves(2, 2))
        assertEquals(emptyList(), boardorderMoves(1, 1))
    }

    @Test
    fun `步数恒等于距离_方向单调`() {
        assertEquals(List(3) { "up" }, boardorderMoves(5, 2))
        assertEquals(List(4) { "down" }, boardorderMoves(3, 7))
        for ((cur, tgt) in listOf(1 to 9, 9 to 1, 4 to 4, 2 to 6)) {
            val moves = boardorderMoves(cur, tgt)
            assertEquals(kotlin.math.abs(cur - tgt), moves.size)
            assertTrue(moves.all { it == moves.firstOrNull() || it == "up" && moves.first() == "up" })
        }
    }
}
