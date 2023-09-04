package no.nav.tilleggsstonader.sak.infrastruktur.exception

import java.net.URI

class IntegrasjonException(
    msg: String,
    throwable: Throwable? = null,
    val uri: URI? = null,
    val data: Any? = null,
) : RuntimeException(msg, throwable)
