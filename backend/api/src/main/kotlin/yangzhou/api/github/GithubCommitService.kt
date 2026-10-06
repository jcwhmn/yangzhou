package yangzhou.api.github

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepoRepository
import yangzhou.api.workspace.WorkspaceService
import java.util.UUID

/**
 * V16-2 按需拉取: item 关联分支的 remote 提交历史。
 * 只读直查 GitHub——不落库、不写活动、不依赖轮询开关;拉取失败给可操作报错。
 */
@Service
class GithubCommitService(
    private val items: ItemRepository,
    private val projectRepos: ProjectRepoRepository,
    private val gitRefs: ItemGitRefRepository,
    private val workspaceService: WorkspaceService,
    private val gateway: GithubGateway,
) {
    data class CommitDto(
        val sha: String,
        val message: String,
        val author: String?,
        val date: String?,
        val url: String?,
    )

    data class RefCommitsDto(val repo: String, val ref: String, val commits: List<CommitDto>)

    fun commitsForItem(itemId: UUID): List<RefCommitsDto> {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val mounts = projectRepos.findByProjectId(item.projectId)
        if (mounts.isEmpty()) throw BadRequestException("项目未挂载 GitHub 仓库(设置 → GitHub)")
        val token = workspaceService.required().githubToken
            ?: throw BadRequestException("未配置 workspace GitHub PAT(设置 → GitHub)")

        val branchRefs = gitRefs.findByItemId(item.id!!).filter { it.kind == "branch" }
        return branchRefs.map { ref ->
            RefCommitsDto(
                repo = ref.repo,
                ref = ref.ref,
                commits = runCatching { gateway.listCommits(ref.repo, ref.ref, token) }
                    .getOrElse { e ->
                        if (e is GithubApiException) {
                            throw BadRequestException("GitHub API 错误(${e.status}):先检查 PAT/仓库名")
                        } else throw e
                    }
                    .map { CommitDto(it.sha, it.message, it.author, it.date, it.url) },
            )
        }
    }
}

@RestController
@RequestMapping("/api")
class GithubCommitController(private val service: GithubCommitService) {

    @GetMapping("/items/{itemId}/commits")
    @ResponseStatus(HttpStatus.OK)
    fun commits(@PathVariable itemId: UUID): List<GithubCommitService.RefCommitsDto> =
        service.commitsForItem(itemId)
}
