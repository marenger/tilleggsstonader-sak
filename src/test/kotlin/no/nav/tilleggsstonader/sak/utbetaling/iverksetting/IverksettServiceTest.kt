package no.nav.tilleggsstonader.sak.utbetaling.iverksetting

import io.mockk.CapturingSlot
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
import no.nav.tilleggsstonader.sak.IntegrationTest
import no.nav.tilleggsstonader.sak.behandling.domain.Behandling
import no.nav.tilleggsstonader.sak.behandling.domain.BehandlingResultat
import no.nav.tilleggsstonader.sak.behandling.domain.BehandlingStatus
import no.nav.tilleggsstonader.sak.infrastruktur.database.repository.findByIdOrThrow
import no.nav.tilleggsstonader.sak.infrastruktur.mocks.IverksettClientConfig.Companion.clearMock
import no.nav.tilleggsstonader.sak.utbetaling.tilkjentytelse.TilkjentYtelseUtil.andelTilkjentYtelse
import no.nav.tilleggsstonader.sak.utbetaling.tilkjentytelse.TilkjentYtelseUtil.tilkjentYtelse
import no.nav.tilleggsstonader.sak.utbetaling.tilkjentytelse.domain.AndelTilkjentYtelse
import no.nav.tilleggsstonader.sak.utbetaling.tilkjentytelse.domain.StatusIverksetting
import no.nav.tilleggsstonader.sak.utbetaling.tilkjentytelse.domain.TilkjentYtelseRepository
import no.nav.tilleggsstonader.sak.util.behandling
import no.nav.tilleggsstonader.sak.util.fagsak
import no.nav.tilleggsstonader.sak.vedtak.totrinnskontroll.domain.TotrinnInternStatus
import no.nav.tilleggsstonader.sak.vedtak.totrinnskontroll.domain.TotrinnskontrollRepository
import no.nav.tilleggsstonader.sak.vedtak.totrinnskontroll.domain.TotrinnskontrollUtil.totrinnskontroll
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.YearMonth
import java.util.UUID

class IverksettServiceTest : IntegrationTest() {

    @Autowired
    lateinit var iverksettService: IverksettService

    @Autowired
    lateinit var iverksettClient: IverksettClient

    @Autowired
    lateinit var tilkjentYtelseRepository: TilkjentYtelseRepository

    @Autowired
    lateinit var totrinnskontrollRepository: TotrinnskontrollRepository

    val forrigeMåned = YearMonth.now().minusMonths(1)
    val nåværendeMåned = YearMonth.now()
    val nesteMåned = YearMonth.now().plusMonths(1)

    val iverksettingDto = slot<IverksettDto>()

    @BeforeEach
    fun setUp() {
        clearMock(iverksettClient)
        justRun { iverksettClient.iverksett(capture(iverksettingDto)) }
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        clearMock(iverksettClient)
    }

    @Test
    fun `skal ikke iverksette hvis resultat er avslag`() {
        val behandling =
            testoppsettService.opprettBehandlingMedFagsak(behandling(resultat = BehandlingResultat.AVSLÅTT))

        iverksettService.iverksett(behandling.id, behandling.id)

        verify(exactly = 0) { iverksettClient.iverksett(any()) }
    }

    @Test
    fun `skal iverksette og oppdatere andeler med status`() {
        val behandling =
            testoppsettService.opprettBehandlingMedFagsak(behandling(resultat = BehandlingResultat.INNVILGET))
        lagreTotrinnskontroll(behandling)
        val tilkjentYtelse = tilkjentYtelseRepository.insert(tilkjentYtelse(behandlingId = behandling.id))

        iverksettService.iverksett(behandling.id, behandling.id)

        verify(exactly = 1) { iverksettClient.iverksett(any()) }

        val oppdatertTilkjentYtelse = tilkjentYtelseRepository.findByIdOrThrow(tilkjentYtelse.id)
        val andel = oppdatertTilkjentYtelse.andelerTilkjentYtelse.single()
        assertThat(andel.iverksetting?.iverksettingId).isEqualTo(behandling.id)
        assertThat(andel.statusIverksetting).isEqualTo(StatusIverksetting.SENDT)
    }

    @Nested
    inner class IverksettingFlyt {

        val fagsak = fagsak()

        val behandling =
            behandling(fagsak, resultat = BehandlingResultat.INNVILGET, status = BehandlingStatus.FERDIGSTILT)
        val behandling2 =
            behandling(
                fagsak,
                resultat = BehandlingResultat.INNVILGET,
                status = BehandlingStatus.FERDIGSTILT,
                forrigeBehandlingId = behandling.id,
            )

        val tilkjentYtelse =
            tilkjentYtelse(behandlingId = behandling.id, startdato = null, andeler = lag3Andeler(behandling))
        val tilkjentYtelse2 =
            tilkjentYtelse(behandlingId = behandling2.id, startdato = null, andeler = lag3Andeler(behandling2))

        @BeforeEach
        fun setUp() {
            testoppsettService.opprettBehandlingMedFagsak(behandling)
            tilkjentYtelseRepository.insert(tilkjentYtelse)
            lagreTotrinnskontroll(behandling)
            iverksettService.iverksettBehandlingFørsteGang(behandling.id)
        }

        @Test
        fun `første behandling - første iverksetting`() {
            val andeler = hentAndeler(behandling)

            andeler.forMåned(forrigeMåned).assertHarStatusOgId(StatusIverksetting.SENDT, behandling.id)

            andeler.forMåned(nåværendeMåned).assertHarStatusOgId(StatusIverksetting.UBEHANDLET)

            andeler.forMåned(nesteMåned).assertHarStatusOgId(StatusIverksetting.UBEHANDLET)

            iverksettingDto.assertUtbetalingerInneholder(forrigeMåned)

            assertThat(iverksettingDto.captured.forrigeIverksetting).isNull()
        }

        @Test
        fun `første behandling  - andre iverksetting`() {
            val iverksettingId = UUID.randomUUID()
            iverksettService.iverksett(behandling.id, iverksettingId, nåværendeMåned)

            val andeler = hentAndeler(behandling)

            andeler.forMåned(forrigeMåned).assertHarStatusOgId(StatusIverksetting.SENDT, behandling.id)

            andeler.forMåned(nåværendeMåned).assertHarStatusOgId(StatusIverksetting.SENDT, iverksettingId)

            andeler.forMåned(nesteMåned).assertHarStatusOgId(StatusIverksetting.UBEHANDLET)

            iverksettingDto.assertUtbetalingerInneholder(forrigeMåned, nåværendeMåned)

            assertThat(iverksettingDto.captured.forrigeIverksetting?.behandlingId).isEqualTo(behandling.id)
            assertThat(iverksettingDto.captured.forrigeIverksetting?.iverksettingId).isEqualTo(behandling.id)
        }

        @Test
        fun `andre behandling - første iverksetting - skal bruke behandling2 som iverksettingId`() {
            testoppsettService.lagre(behandling2)
            tilkjentYtelseRepository.insert(tilkjentYtelse2)
            lagreTotrinnskontroll(behandling2)
            iverksettService.iverksettBehandlingFørsteGang(behandling2.id)

            val andeler = hentAndeler(behandling2)

            andeler.forMåned(forrigeMåned).assertHarStatusOgId(StatusIverksetting.SENDT, behandling2.id)

            andeler.forMåned(nåværendeMåned).assertHarStatusOgId(StatusIverksetting.UBEHANDLET)

            andeler.forMåned(nesteMåned).assertHarStatusOgId(StatusIverksetting.UBEHANDLET)

            iverksettingDto.assertUtbetalingerInneholder(forrigeMåned)

            assertThat(iverksettingDto.captured.forrigeIverksetting?.behandlingId).isEqualTo(behandling.id)
            assertThat(iverksettingDto.captured.forrigeIverksetting?.iverksettingId).isEqualTo(behandling.id)
        }

        @Test
        fun `andre behandling - første iverksetting med 2 iverksettinger`() {
            val iverksettingIdBehandling1 = UUID.randomUUID()
            iverksettService.iverksett(behandling.id, iverksettingIdBehandling1, nåværendeMåned)

            testoppsettService.lagre(behandling2)
            tilkjentYtelseRepository.insert(tilkjentYtelse2)
            lagreTotrinnskontroll(behandling2)
            iverksettService.iverksettBehandlingFørsteGang(behandling2.id)

            val andeler = hentAndeler(behandling2)

            andeler.forMåned(forrigeMåned).assertHarStatusOgId(StatusIverksetting.SENDT, behandling2.id)

            andeler.forMåned(nåværendeMåned).assertHarStatusOgId(StatusIverksetting.UBEHANDLET)

            andeler.forMåned(nesteMåned).assertHarStatusOgId(StatusIverksetting.UBEHANDLET)

            iverksettingDto.assertUtbetalingerInneholder(forrigeMåned)

            assertThat(iverksettingDto.captured.forrigeIverksetting?.behandlingId).isEqualTo(behandling.id)
            assertThat(iverksettingDto.captured.forrigeIverksetting?.iverksettingId).isEqualTo(iverksettingIdBehandling1)
        }

        @Test
        fun `andre behandling  - andre iverksetting`() {
            val iverksettingId = UUID.randomUUID()

            testoppsettService.lagre(behandling2)
            tilkjentYtelseRepository.insert(tilkjentYtelse2)
            lagreTotrinnskontroll(behandling2)

            iverksettService.iverksettBehandlingFørsteGang(behandling2.id)
            iverksettService.iverksett(behandling2.id, iverksettingId)

            val andeler = hentAndeler(behandling2)

            andeler.forMåned(forrigeMåned).assertHarStatusOgId(StatusIverksetting.SENDT, behandling2.id)

            andeler.forMåned(nåværendeMåned).assertHarStatusOgId(StatusIverksetting.SENDT, iverksettingId)

            andeler.forMåned(nesteMåned).assertHarStatusOgId(StatusIverksetting.UBEHANDLET)

            iverksettingDto.assertUtbetalingerInneholder(forrigeMåned, nåværendeMåned)

            assertThat(iverksettingDto.captured.forrigeIverksetting?.behandlingId).isEqualTo(behandling2.id)
            assertThat(iverksettingDto.captured.forrigeIverksetting?.iverksettingId).isEqualTo(behandling2.id)
        }

        @Test
        fun `andre behandling kun med 0-beløp - skal ikke sende noen andeler`() {
            testoppsettService.lagre(behandling2)
            tilkjentYtelseRepository.insert(
                tilkjentYtelse(
                    behandling2.id,
                    null,
                    lagAndel(behandling, forrigeMåned, beløp = 0),
                ),
            )
            lagreTotrinnskontroll(behandling2)
            iverksettService.iverksettBehandlingFørsteGang(behandling2.id)

            val andeler = hentAndeler(behandling2)

            andeler.forMåned(forrigeMåned).assertHarStatusOgId(StatusIverksetting.SENDT, behandling2.id)

            assertThat(iverksettingDto.captured.vedtak.utbetalinger).isEmpty()
        }
    }

    private fun CapturingSlot<IverksettDto>.assertUtbetalingerInneholder(vararg måned: YearMonth) {
        assertThat(captured.vedtak.utbetalinger.map { YearMonth.from(it.fraOgMedDato) })
            .containsExactly(*måned)
    }

    private fun lagreTotrinnskontroll(behandling: Behandling) {
        totrinnskontrollRepository.insert(
            totrinnskontroll(
                status = TotrinnInternStatus.GODKJENT,
                behandlingId = behandling.id,
                beslutter = "beslutter",
            ),
        )
    }

    private fun hentAndeler(behandling: Behandling): Set<AndelTilkjentYtelse> {
        return tilkjentYtelseRepository.findByBehandlingId(behandling.id)!!.andelerTilkjentYtelse
    }

    private fun Collection<AndelTilkjentYtelse>.forMåned(yearMonth: YearMonth) =
        this.single { it.fom == yearMonth.atDay(1) }

    fun AndelTilkjentYtelse.assertHarStatusOgId(statusIverksetting: StatusIverksetting, iverksettingId: UUID? = null) {
        assertThat(this.statusIverksetting).isEqualTo(statusIverksetting)
        assertThat(this.iverksetting?.iverksettingId).isEqualTo(iverksettingId)
    }

    private fun lag3Andeler(behandling: Behandling) = arrayOf(
        lagAndel(behandling, forrigeMåned),
        lagAndel(behandling, nåværendeMåned),
        lagAndel(behandling, nesteMåned),
    )

    private fun lagAndel(behandling: Behandling, måned: YearMonth, beløp: Int = 10) = andelTilkjentYtelse(
        kildeBehandlingId = behandling.id,
        fom = måned.atDay(1),
        tom = måned.atEndOfMonth(),
        beløp = beløp,
    )
}
