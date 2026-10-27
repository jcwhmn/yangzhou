package yangzhou.api.error

/** 业务异常族:service fail-fast,HTTP 映射集中处理。 */
class NotFoundException(message: String) : RuntimeException(message)
class ConflictException(message: String) : RuntimeException(message)
class BadRequestException(message: String) : RuntimeException(message)
class ForbiddenException(message: String) : RuntimeException(message)
