package no.nav.tilleggsstonader.sak.vilkår.stønadsperiode

import no.nav.tilleggsstonader.sak.behandling.BehandlingService
import no.nav.tilleggsstonader.sak.infrastruktur.exception.feilHvis
import no.nav.tilleggsstonader.sak.vilkår.stønadsperiode.domain.Stønadsperiode
import no.nav.tilleggsstonader.sak.vilkår.stønadsperiode.domain.StønadsperiodeRepository
import no.nav.tilleggsstonader.sak.vilkår.stønadsperiode.dto.StønadsperiodeDto
import no.nav.tilleggsstonader.sak.vilkår.stønadsperiode.dto.tilSortertDto
import no.nav.tilleggsstonader.sak.vilkår.vilkårperiode.VilkårperiodeService
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class StønadsperiodeService(
    private val behandlingService: BehandlingService,
    private val stønadsperiodeRepository: StønadsperiodeRepository,
    private val vilkårperiodeService: VilkårperiodeService,
) {
    fun hentStønadsperioder(behandlingId: UUID): List<StønadsperiodeDto> {
        return stønadsperiodeRepository.findAllByBehandlingId(behandlingId).tilSortertDto()
    }

    fun lagreStønadsperioder(behandlingId: UUID, stønadsperioder: List<StønadsperiodeDto>): List<StønadsperiodeDto> {
        feilHvis(behandlingService.hentBehandling(behandlingId).status.behandlingErLåstForVidereRedigering()) {
            "Kan ikke lagre stønadsperioder når behandlingen er låst"
        }
        validerStønadsperioder(behandlingId, stønadsperioder)
        stønadsperiodeRepository.deleteAll() // TODO skal vi finne og oppdatere de som har blitt oppdatert/slettet
        return stønadsperiodeRepository.insertAll(
            stønadsperioder.map {
                Stønadsperiode(
                    id = it.id ?: UUID.randomUUID(),
                    behandlingId = behandlingId,
                    fom = it.fom,
                    tom = it.tom,
                    målgruppe = it.målgruppe,
                    aktivitet = it.aktivitet,
                )
            },
        ).tilSortertDto()
    }

    fun validerStønadsperioder(behandlingId: UUID, stønadsperioder: List<StønadsperiodeDto>) {
        val vilkårperioder = vilkårperiodeService.hentVilkårperioder(behandlingId)

        StønadsperiodeValideringUtil.validerStønadsperioder(stønadsperioder, vilkårperioder)
    }
}
