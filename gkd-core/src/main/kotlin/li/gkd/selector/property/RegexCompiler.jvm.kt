package li.gkd.selector.property

internal fun String.compilePlatformRegex(): RegexCompileResult {
    val regex = try {
        Regex(this)
    } catch (error: IllegalArgumentException) {
        return RegexCompileResult.Failure(
            listOfNotNull(error::class.simpleName, error.message)
                .distinct()
                .joinToString(": "),
        )
    }
    return RegexCompileResult.Success { input -> regex.matches(input) }
}
