package yangzhou.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories

@Configuration
@EnableJdbcRepositories(basePackages = ["yangzhou.persistence.repository"])
class JdbcRepositoriesConfig
