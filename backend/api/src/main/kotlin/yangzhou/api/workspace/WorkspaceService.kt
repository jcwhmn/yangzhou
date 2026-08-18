package yangzhou.api.workspace

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.WorkspaceRepository

/** V1:单 workspace,首个成员 bootstrap 时创建(ADR-0001:单租户时隐形)。 */
@Service
class WorkspaceService(private val workspaces: WorkspaceRepository) {

    @Transactional
    fun ensureDefault(): Workspace =
        workspaces.findAll().firstOrNull() ?: workspaces.save(Workspace())

    fun required(): Workspace =
        workspaces.findAll().firstOrNull() ?: error("workspace missing")
}
