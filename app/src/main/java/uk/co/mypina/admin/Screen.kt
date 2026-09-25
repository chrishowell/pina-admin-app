package uk.co.mypina.admin

/** The app's screens. The web view stays alive underneath the native ones. */
sealed interface Screen {
    data object Web : Screen
    data class WriteTag(val tagId: String) : Screen
    /** [tagId] is the tag page the admin came from (`pina-admin://verify-tag/<id>`), or null. */
    data class VerifyTag(val tagId: String?) : Screen
}
