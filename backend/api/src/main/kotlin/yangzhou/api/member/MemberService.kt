package yangzhou.api.member

import org.springframework.stereotype.Service
import yangzhou.persistence.Member
import yangzhou.persistence.repository.MemberRepository
import yangzhou.api.support.NotFoundException

/** V1 单用户:唯一成员即「我」。 */
@Service
class MemberService(private val members: MemberRepository) {

    fun current(): Member =
        members.findAll().firstOrNull() ?: throw NotFoundException("尚未初始化,请先 bootstrap")
}
