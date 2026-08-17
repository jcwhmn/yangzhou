package yangzhou.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["yangzhou.api", "yangzhou.persistence"])
class YangzhouApplication

fun main(args: Array<String>) {
    runApplication<YangzhouApplication>(*args)
}
