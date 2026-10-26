package dev.earshot.service

import dev.earshot.bt.DeviceFamily
import dev.earshot.core.HeadphoneRepository
import dev.earshot.service.session.SessionRepository

/**
 * One vendor stack as the router sees it: the [family] it drives, its [repository], and how to
 * [release] its connection when another stack takes over (without stopping the foreground
 * service, which the next stack keeps using).
 *
 * Adding a vendor is: a [DeviceFamily] value with its name rule, a repository (usually a
 * [SessionRepository] over a `ConnectionManager` with a `LinkFactory`), and one entry in
 * `AppContainer`'s driver list. The router has no per-vendor code; the [DeviceFamily.GENERIC]
 * driver must always be present, since every device nobody else claims ends up there. For the Lab
 * scanner, the repository also overrides `labProfile()` / `scanLab()` with a
 * `service.lab.<Stack>Lab` built from the reads its write guard allows (recipe in
 * `service/lab/LabFrames.kt`); without them the Lab says nothing can be scanned.
 */
class VendorDriver(
    val family: DeviceFamily,
    val repository: HeadphoneRepository,
    val release: () -> Unit,
) {
    /** A stack on the session base: releasing it is always [SessionRepository.releaseConnection]. */
    constructor(family: DeviceFamily, repository: SessionRepository<*>) : this(family, repository, repository::releaseConnection)
}
