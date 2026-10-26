package yangzhou.api.business.attachment

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** YPJ-100:从 AttachmentApiTest 抽出的测试 fake,单测与集成两侧共用(集成 sourceSet 挂 test.output)。 */
class FakeObjectStorage : ObjectStorage {
    val puts = mutableListOf<Pair<String, String>>() // key to contentType
    val gets = mutableListOf<String>()
    val deletes = mutableListOf<String>()

    override fun presignPut(key: String, contentType: String): String {
        puts += key to contentType
        return "http://fake/put/$key"
    }

    override fun presignGet(key: String): String {
        gets += key
        return "http://fake/get/$key"
    }

    override fun delete(key: String) {
        deletes += key
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakeStorageConfig {
    @Bean
    @Primary
    fun fakeObjectStorage(): FakeObjectStorage = FakeObjectStorage()
}
