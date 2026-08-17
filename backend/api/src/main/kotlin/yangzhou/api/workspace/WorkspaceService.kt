package yangzhou.api.workspace

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.persistence.WorkspaceEntity
import yangzhou.persistence.repository.WorkspaceRepository

/** V1:单 workspace,首个成员 bootstrap 时创建(ADR-0001:单租户时隐形)。 */
@Service
class WorkspaceService(private val workspaces: WorkspaceRepository) {

    @Transactional
    fun ensureDefault(): WorkspaceEntity =
        workspaces.findAll().firstOrNull() ?: workspaces.save(WorkspaceEntity())

    fun required(): WorkspaceEntity =
        workspaces.findAll().firstOrNull() ?: error("workspace missing")
}
