package dev.picarry.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 重配对（同一 endpointId、新令牌）必须沿用旧节点 id：连接、事件键与 UI 都按旧 id 索引，
 * 否则 connect 拿新 id 找不到节点，配对页会一直停在“正在建立加密连接”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AddNodeTest {
    private fun freshRepo(): CarryRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE).edit().clear().commit()
        return CarryRepository(context)
    }

    private fun linkedIds(repo: CarryRepository): Set<String> = repo.link.value.keys

    private fun awaitLinked(repo: CarryRepository, id: String): Boolean {
        repeat(60) {
            if (id in linkedIds(repo)) return true
            Thread.sleep(50)
        }
        return id in linkedIds(repo)
    }

    private fun awaitConnected(repo: CarryRepository, id: String): Boolean {
        repeat(60) {
            if (repo.link.value[id].let { it != null && it != LinkState.Offline }) return true
            Thread.sleep(50)
        }
        return repo.link.value[id].let { it != null && it != LinkState.Offline }
    }

    @Test
    fun repairingKeepsOldIdAndConnectsIt() {
        val repo = freshRepo()
        val endpoint = "a".repeat(64)
        val first = Node(id = "old-node-id", name = "pc", endpointId = endpoint, token = "token-1")
        repo.addNode(first)
        assertTrue(awaitLinked(repo, "old-node-id"))
        // 先断开：接下来重新触发连接时，只有用对了 id 才会重新出现连接状态。
        repo.disconnect("old-node-id")
        assertEquals(LinkState.Offline, repo.link.value["old-node-id"])

        val repaired = Node(id = "new-node-id", name = "pc", endpointId = endpoint, token = "token-2")
        repo.addNode(repaired)

        assertEquals(1, repo.nodes.value.size)
        assertEquals("old-node-id", repo.nodes.value.single().id)
        assertEquals("token-2", repo.nodes.value.single().token)
        assertTrue("重配对必须用落库后的 id 重连", awaitConnected(repo, "old-node-id"))
        assertTrue("连接不能用新 id 起，否则 node(id) 查不到直接返回", "new-node-id" !in linkedIds(repo))
    }

    @Test
    fun removeNodeClearsPerNodeAndPerSessionState() {
        val repo = freshRepo()
        val node = Node(id = "n1", name = "pc", endpointId = "b".repeat(64), token = "t")
        repo.addNode(node)
        // 只有节点本身留下痕迹，其它派生状态在删除后必须一起消失。
        assertNotNull(repo.node("n1"))

        repo.removeNode("n1")

        assertNull(repo.node("n1"))
        assertTrue(repo.nodes.value.isEmpty())
        assertTrue(repo.link.value.isEmpty())
        assertTrue(repo.errors.value.isEmpty())
        assertTrue(repo.sessions.value.isEmpty())
        assertTrue(repo.approvals.value.isEmpty())
        assertTrue(repo.messages.value.isEmpty())
        assertTrue(repo.models.value.isEmpty())
        assertTrue(repo.thinkingLevels.value.isEmpty())
        assertTrue(repo.runInfo.value.isEmpty())
    }
}
