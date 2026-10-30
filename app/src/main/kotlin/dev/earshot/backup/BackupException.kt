package dev.earshot.backup

/** Why an export or import failed, in words the screen can turn into one plain sentence. */
enum class BackupError {
    /** Not a zip, or a zip without an Earshot manifest (first). */
    NOT_A_BACKUP,

    /** Made by a newer Earshot: a newer format, or files this build doesn't know from a newer build. */
    NEWER_FORMAT,

    /** A checksum, size or file list doesn't match the manifest, or a settings file doesn't parse. */
    DAMAGED,

    /** A file outside the allow-list (or a path trying to leave the app's folder). */
    UNEXPECTED_FILE,

    /** Over a size or entry-count cap. */
    TOO_LARGE,

    /** The phone ran out of space while unpacking or saving. */
    NO_SPACE,

    /** The chosen file couldn't be read. */
    READ_FAILED,

    /** The backup file couldn't be written; a new, empty document was removed again. */
    WRITE_FAILED,

    /** Copying into a document that already had content failed: that file may now be incomplete (it was left, not deleted). */
    OVERWRITE_FAILED,

    /** An own sound changed between hashing and writing it: try again. */
    CHANGED_DURING_EXPORT,

    /** Another import is still being checked (one at a time). */
    BUSY,

    /** An earlier restore stopped part-way and is still under way: only a restart of Earshot finishes it (or undoes it). */
    RESTORE_UNFINISHED,
}

/** A backup failure. [message] is for the log only (no personal data); the UI shows [error]. */
class BackupException(val error: BackupError, message: String, cause: Throwable? = null) : Exception(message, cause)
