package no.nav.tilleggsstonader.sak.opplysninger.pdl

import no.nav.tilleggsstonader.sak.infrastruktur.config.SecureLogger.secureLogger
import no.nav.tilleggsstonader.sak.infrastruktur.exception.feilHvis
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlAnnenForelder
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlBolkResponse
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlHentIdenter
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlIdent
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlIdentBolkRequest
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlIdentBolkRequestVariables
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlIdentBolkResponse
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlIdentRequest
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlIdentRequestVariables
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlIdenter
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlPersonBolkRequest
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlPersonBolkRequestVariables
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlPersonForelderBarn
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlPersonKort
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlPersonRequest
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlPersonRequestVariables
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlResponse
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlSøker
import no.nav.tilleggsstonader.sak.opplysninger.pdl.dto.PdlSøkerData
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.client.RestOperations
import org.springframework.web.client.exchange

@Service
class PdlClient(
    private val pdlConfig: PdlConfig,
    @Qualifier("azureClientCredential")
    private val restOperations: RestOperations,
) {

    private inline fun <reified RES, REQ> execute(request: REQ): RES {
        return restOperations.exchange<RES>(
            pdlConfig.pdlUri,
            HttpMethod.POST,
            HttpEntity(request, PdlUtil.httpHeaders),
        ).body ?: error("Mangler body")
    }

    fun hentSøker(personIdent: String): PdlSøker {
        val request = PdlPersonRequest(
            variables = PdlPersonRequestVariables(personIdent),
            query = PdlConfig.søkerQuery,
        )

        val pdlResponse: PdlResponse<PdlSøkerData> = execute(request)

        return feilsjekkOgReturnerData(personIdent, pdlResponse) { it.person }
    }

    fun hentPersonForelderBarnRelasjon(personIdenter: List<String>): Map<String, PdlPersonForelderBarn> {
        if (personIdenter.isEmpty()) return emptyMap()
        val request = PdlPersonBolkRequest(
            variables = PdlPersonBolkRequestVariables(personIdenter),
            query = PdlConfig.forelderBarnQuery,
        )

        val pdlResponse: PdlBolkResponse<PdlPersonForelderBarn> = execute(request)

        return feilsjekkOgReturnerData(pdlResponse)
    }

    fun hentAndreForeldre(personIdenter: List<String>): Map<String, PdlAnnenForelder> {
        if (personIdenter.isEmpty()) return emptyMap()
        val request = PdlPersonBolkRequest(
            variables = PdlPersonBolkRequestVariables(personIdenter),
            query = PdlConfig.annenForelderQuery,
        )
        val pdlResponse: PdlBolkResponse<PdlAnnenForelder> = execute(request)
        return feilsjekkOgReturnerData(pdlResponse)
    }

    fun hentPersonKortBolk(personIdenter: List<String>): Map<String, PdlPersonKort> {
        require(personIdenter.size <= 100) { "Liste med personidenter må være færre enn 100 st" }
        val request = PdlPersonBolkRequest(
            variables = PdlPersonBolkRequestVariables(personIdenter),
            query = PdlConfig.personBolkKortQuery,
        )
        val pdlResponse: PdlBolkResponse<PdlPersonKort> = execute(request)
        return feilsjekkOgReturnerData(pdlResponse)
    }

    /**
     * @param ident Ident til personen, samme hvilke type (Folkeregisterident, aktørid eller npid)
     * @return liste med aktørider
     */
    fun hentAktørIder(ident: String): PdlIdenter {
        val request = PdlIdentRequest(
            variables = PdlIdentRequestVariables(ident, "AKTORID"),
            query = PdlConfig.hentIdentQuery,
        )
        val pdlResponse: PdlResponse<PdlHentIdenter> = execute(request)
        return feilsjekkOgReturnerData(ident, pdlResponse) { it.hentIdenter }
    }

    /**
     * @param ident Ident til personen, samme hvilke type (Folkeregisterident, aktørid eller npid)
     * @return liste med folkeregisteridenter
     */
    fun hentPersonidenter(ident: String): PdlIdenter {
        val request = PdlIdentRequest(
            variables = PdlIdentRequestVariables(ident, "FOLKEREGISTERIDENT", historikk = true),
            query = PdlConfig.hentIdentQuery,
        )
        val pdlResponse: PdlResponse<PdlHentIdenter> = execute(request)

        val pdlIdenter = feilsjekkOgReturnerData(ident, pdlResponse) { it.hentIdenter }

        if (pdlIdenter.identer.isEmpty()) {
            secureLogger.error("Finner ikke personidenter for personIdent i PDL $ident ")
        }
        return pdlIdenter
    }

    /**
     * @param identer Identene til personene, samme hvilke type (Folkeregisterident, aktørid eller npid).
     * For tiden (2020-03-22) maks 100 identer lovlig i spørring.
     * @return map med søkeident som nøkkel og liste av folkeregisteridenter
     */
    fun hentIdenterBolk(identer: List<String>): Map<String, PdlIdent> {
        feilHvis(identer.size > MAKS_ANTALL_IDENTER) {
            "Feil i spørring mot PDL. Antall identer i spørring overstiger $MAKS_ANTALL_IDENTER"
        }
        val request = PdlIdentBolkRequest(
            variables = PdlIdentBolkRequestVariables(identer, "FOLKEREGISTERIDENT"),
            query = PdlConfig.hentIdenterBolkQuery,
        )
        val pdlResponse: PdlIdentBolkResponse = execute(request)

        return feilmeldOgReturnerData(pdlResponse)
    }

    companion object {

        const val MAKS_ANTALL_IDENTER = 100
    }
}
