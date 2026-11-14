package cz.kutil.dochazka

import cz.kutil.dochazka.core.Action
import cz.kutil.dochazka.core.LastTransition
import cz.kutil.dochazka.core.OpenShiftInfo
import cz.kutil.dochazka.core.PRAGUE
import cz.kutil.dochazka.core.PendingInfo
import cz.kutil.dochazka.core.PendingStatus
import cz.kutil.dochazka.core.PendingType
import cz.kutil.dochazka.core.Snapshot
import cz.kutil.dochazka.core.StateMachine
import cz.kutil.dochazka.core.TrackEvent
import cz.kutil.dochazka.core.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** Jednoduchá simulace úložiště – aplikuje akce stavového automatu stejně jako aplikace nad Room DB. */
private class Sim {
    data class Shift(val id: Long, val start: Instant, var end: Instant?, val manual: Boolean)

    var state = TrackState.HOME
    var last: LastTransition? = null
    val pending = LinkedHashMap<Long, PendingInfo>()
    val resolved = LinkedHashMap<Long, PendingStatus>()
    val shifts = ArrayList<Shift>()
    var reRegistered = 0
    val uncertain = HashSet<Long>()
    val conflicts = HashSet<Long>()
    private var nextId = 1L

    val open: Shift? get() = shifts.lastOrNull { it.end == null }
    val closed: List<Shift> get() = shifts.filter { it.end != null }
    val visiblePending: List<PendingInfo> get() = pending.values.filter { !it.deferred }

    fun send(e: TrackEvent): String {
        val snap = Snapshot(state, pending.values.toList(), open?.let { OpenShiftInfo(it.id, it.start) }, last)
        val r = StateMachine.reduce(snap, e)
        for (a in r.actions) when (a) {
            is Action.CreatePending -> {
                val id = nextId++
                pending[id] = PendingInfo(id, a.type, a.detectedAt, null, a.shiftId, a.deferred)
            }
            is Action.ResolvePending -> {
                pending.remove(a.id)
                resolved[a.id] = a.status
            }
            is Action.SetPairedEnd -> pending[a.id] = pending[a.id]!!.copy(pairedEndAt = a.endAt)
            is Action.ConfirmDeferred -> {
                pending[a.id] = pending[a.id]!!.copy(deferred = false)
                if (a.uncertain) uncertain.add(a.id)
            }
            is Action.MarkConflict -> conflicts.add(a.id)
            is Action.OpenShift -> shifts.add(Shift(nextId++, a.start, null, a.manual))
            is Action.CloseShift -> shifts.first { it.id == a.shiftId }.end = a.end
            is Action.CreateClosedShift -> shifts.add(Shift(nextId++, a.start, a.end, false))
            Action.ReRegisterZone -> reRegistered++
        }
        state = r.state
        last = r.newLast
        return r.note
    }

    fun onlyPending(): PendingInfo = pending.values.single()
}

private fun t(d: Int, h: Int, m: Int): Instant = LocalDateTime.of(2026, 11, d, h, m).atZone(PRAGUE).toInstant()

class StateMachineTest {

    @Test fun odchodAnoOtevreJednuSmenuCasemUdalosti() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 6, 52)))
        assertEquals(TrackState.OUTSIDE_PENDING, s.state)
        assertTrue(s.shifts.isEmpty()) // nic se nezapisuje bez souhlasu
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 7, 20))) // odpověď o 28 minut později
        assertEquals(TrackState.WORKING, s.state)
        assertEquals(1, s.shifts.size)
        assertEquals(t(12, 6, 52), s.open!!.start) // čas události, ne kliknutí
    }

    @Test fun odchodNeNevytvoriSmenuANavratNevytvoriHodiny() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(14, 10, 0)))
        s.send(TrackEvent.Answer(s.onlyPending().id, false, t(14, 10, 1)))
        assertEquals(TrackState.AWAY, s.state)
        s.send(TrackEvent.GeoEnter(t(14, 12, 0)))
        assertEquals(TrackState.HOME, s.state)
        assertTrue(s.shifts.isEmpty())
        assertTrue(s.pending.isEmpty())
    }

    @Test fun navratAnoUzavreNeNechaOtevrenou() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 6, 52)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 6, 53)))
        s.send(TrackEvent.GeoEnter(t(12, 11, 30)))
        assertEquals(TrackState.RETURN_PENDING, s.state)
        s.send(TrackEvent.Answer(s.onlyPending().id, false, t(12, 11, 31))) // jen oběd doma
        assertEquals(TrackState.WORKING, s.state)
        assertEquals(null, s.open!!.end)
        s.send(TrackEvent.GeoExit(t(12, 12, 0)))
        assertEquals(TrackState.WORKING, s.state)
        s.send(TrackEvent.GeoEnter(t(12, 15, 28)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 16, 0)))
        assertEquals(TrackState.HOME, s.state)
        assertEquals(1, s.closed.size)
        assertEquals(t(12, 15, 28), s.closed[0].end)
        assertNull(s.open)
    }

    @Test fun opakovanyNavratNevytvoriDuplicitu() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 7, 0)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 7, 1)))
        s.send(TrackEvent.GeoEnter(t(12, 15, 28)))
        s.send(TrackEvent.GeoEnter(t(12, 15, 29))) // duplicitní doručení
        s.send(TrackEvent.GeoEnter(t(12, 15, 50))) // další návrat bez odchodu
        assertEquals(1, s.pending.size)
        val id = s.onlyPending().id
        s.send(TrackEvent.Answer(id, true, t(12, 16, 0)))
        s.send(TrackEvent.Answer(id, true, t(12, 16, 1))) // dvojí klik na ANO
        assertEquals(1, s.shifts.size)
        assertEquals(t(12, 15, 28), s.shifts[0].end)
    }

    @Test fun opakovanyOdchodAKolisaniGps() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 7, 0)))
        s.send(TrackEvent.GeoExit(t(12, 7, 1)))
        s.send(TrackEvent.GeoExit(t(12, 7, 30)))
        assertEquals(1, s.pending.size)
        // kmitnutí: odchod a do 3 minut návrat -> návrh zrušen, žádný dotaz nezůstává
        val f = Sim()
        f.send(TrackEvent.GeoExit(t(12, 3, 0)))
        f.send(TrackEvent.GeoEnter(t(12, 3, 2)))
        assertEquals(TrackState.HOME, f.state)
        assertTrue(f.pending.isEmpty())
        assertTrue(f.shifts.isEmpty())
    }

    @Test fun kmitnutiPriNavratuNezrusiSmenu() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 7, 0)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 7, 1)))
        s.send(TrackEvent.GeoEnter(t(12, 10, 0))) // projel kolem domu
        s.send(TrackEvent.GeoExit(t(12, 10, 2)))
        assertEquals(TrackState.WORKING, s.state)
        assertTrue(s.pending.isEmpty())
        assertEquals(null, s.open!!.end)
    }

    @Test fun ignorovaneOznameniZustaneJakoNavrhCelehoIntervalu() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 6, 55)))
        s.send(TrackEvent.GeoEnter(t(12, 15, 35))) // celý den bez odpovědi
        assertEquals(TrackState.HOME, s.state)
        val p = s.onlyPending()
        assertEquals(t(12, 15, 35), p.pairedEndAt)
        assertTrue(s.shifts.isEmpty())
        s.send(TrackEvent.Answer(p.id, true, t(12, 20, 0))) // potvrzení večer v aplikaci
        assertEquals(1, s.closed.size)
        assertEquals(t(12, 6, 55), s.closed[0].start)
        assertEquals(t(12, 15, 35), s.closed[0].end)
        assertEquals(TrackState.HOME, s.state)
    }

    @Test fun druhyVyjezdStejnyDen() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 7, 0)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 7, 1)))
        s.send(TrackEvent.GeoEnter(t(12, 15, 30)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 15, 31)))
        s.send(TrackEvent.GeoExit(t(12, 17, 0)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 17, 1)))
        s.send(TrackEvent.GeoEnter(t(12, 19, 0)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 19, 1)))
        assertEquals(2, s.closed.size)
        val minutes = s.closed.sumOf { java.time.Duration.between(it.start, it.end).toMinutes() }
        assertEquals(10 * 60 + 30, minutes)
    }

    @Test fun domaciWifiOdloziDotazAFalesnyPoplachZrusi() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 2, 0), homeWifiConnected = true))
        assertEquals(TrackState.OUTSIDE_PENDING, s.state)
        assertTrue(s.visiblePending.isEmpty()) // žádné oznámení
        s.send(TrackEvent.WifiRecheck(s.onlyPending().id, stillOnHomeWifi = true, at = t(12, 2, 4)))
        assertEquals(TrackState.HOME, s.state)
        assertTrue(s.pending.isEmpty())
        assertEquals(1, s.reRegistered)
        // další skutečný odchod se nesmí zahodit jako duplicita
        s.send(TrackEvent.GeoExit(t(12, 2, 6)))
        assertEquals(1, s.visiblePending.size)
    }

    @Test fun neznameSsidNeniDukazNepritomnostiAniPritomnosti() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 7, 0), homeWifiConnected = null))
        assertEquals(1, s.visiblePending.size) // dotaz přijde i bez znalosti Wi-Fi
        val w = Sim()
        w.send(TrackEvent.GeoExit(t(12, 7, 0), homeWifiConnected = true))
        w.send(TrackEvent.WifiRecheck(w.onlyPending().id, stillOnHomeWifi = false, at = t(12, 7, 4)))
        assertEquals(1, w.visiblePending.size)
        assertEquals(t(12, 7, 0), w.onlyPending().detectedAt) // čas původní události
    }

    @Test fun rucniStartAKonec() {
        val s = Sim()
        s.send(TrackEvent.ManualStart(t(12, 7, 0)))
        assertEquals(TrackState.WORKING, s.state)
        s.send(TrackEvent.ManualStart(t(12, 7, 5))) // druhý klik nic nezaloží
        assertEquals(1, s.shifts.size)
        s.send(TrackEvent.ManualEnd(t(12, 15, 30)))
        assertEquals(TrackState.HOME, s.state)
        assertEquals(1, s.closed.size)
        // po ručním konci doma musí další odchod znovu vyvolat dotaz
        s.send(TrackEvent.GeoExit(t(12, 17, 0)))
        assertEquals(1, s.pending.size)
    }

    @Test fun rucniKonecVenkuAZtracenyNavrat() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 7, 0)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 7, 1)))
        s.send(TrackEvent.ManualEnd(t(12, 15, 30))) // ukončil ručně ještě venku
        assertEquals(TrackState.AWAY, s.state)
        // návrat se ztratil (Android ho nedoručil); další odchod se přesto zeptá
        s.send(TrackEvent.GeoExit(t(13, 7, 0)))
        assertEquals(TrackState.OUTSIDE_PENDING, s.state)
        assertEquals(1, s.pending.size)
    }

    @Test fun zpozdenaOdpovedNaStaryNavrhNicNerozbije() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 7, 0)))
        val old = s.onlyPending().id
        s.send(TrackEvent.ManualStart(t(12, 7, 10))) // začal ručně, návrh se tím ruší
        assertEquals(PendingStatus.SUPERSEDED, s.resolved[old])
        s.send(TrackEvent.Answer(old, true, t(12, 9, 0)))
        assertEquals(1, s.shifts.size)
        assertEquals(PendingType.START, PendingType.valueOf("START"))
    }

    @Test fun smazanaOtevrenaSmenaNezablokujeDalsiDotazy() {
        val s = Sim()
        s.send(TrackEvent.ManualStart(t(12, 7, 0)))
        s.shifts.clear() // uživatel směnu ručně smazal, stav zůstal WORKING
        assertEquals(TrackState.WORKING, s.state)
        s.send(TrackEvent.GeoExit(t(12, 8, 0)))
        assertEquals(TrackState.OUTSIDE_PENDING, s.state)
        assertEquals(1, s.pending.size)
    }

    @Test fun nepresnaPolohaSeNejdrivOveri() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 3, 0), lowAccuracy = true))
        assertTrue(s.visiblePending.isEmpty()) // žádné oznámení ve tři ráno kvůli skoku GPS
        s.send(TrackEvent.WifiRecheck(s.onlyPending().id, stillOnHomeWifi = true, at = t(12, 3, 3)))
        assertEquals(TrackState.HOME, s.state)
        assertTrue(s.pending.isEmpty())
    }

    @Test fun vypadekGpsIWifiOznaciDotazJakoNejisty() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 6, 53), lowAccuracy = true))
        val id = s.onlyPending().id
        s.send(TrackEvent.WifiRecheck(id, stillOnHomeWifi = false, at = t(12, 6, 56), uncertain = true))
        assertEquals(1, s.visiblePending.size)
        assertTrue(id in s.uncertain)
        // potvrzení v 10:00 použije čas události 06:53, ne čas kliknutí
        s.send(TrackEvent.Answer(id, true, t(12, 10, 0)))
        assertEquals(t(12, 6, 53), s.open!!.start)
    }

    @Test fun navratBezOdpovediANovyOdchodJeKonflikt() {
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 7, 0)))
        s.send(TrackEvent.Answer(s.onlyPending().id, true, t(12, 7, 1)))
        s.send(TrackEvent.GeoEnter(t(12, 12, 0)))
        val end = s.onlyPending().id
        s.send(TrackEvent.GeoExit(t(12, 12, 40))) // znovu odjel, na dotaz neodpověděl
        assertTrue(end in s.conflicts)
        assertEquals(TrackState.WORKING, s.state)
        assertEquals(1, s.pending.size) // návrh nezmizel
        // dodatečné ANO uzavře směnu časem návratu 12:00
        s.send(TrackEvent.Answer(end, true, t(12, 18, 0)))
        assertEquals(t(12, 12, 0), s.closed.single().end)
    }

    @Test fun nevyrizeneNavrhyPreziji_snapshotJeJedinyStav() {
        // Automat nemá žádnou paměť mimo Snapshot: po „restartu“ (nová instance nad stejnými daty) pokračuje stejně.
        val s = Sim()
        s.send(TrackEvent.GeoExit(t(12, 6, 53)))
        val p = s.onlyPending()
        val snapshot = Snapshot(s.state, listOf(p), null, s.last)
        val r = StateMachine.reduce(snapshot, TrackEvent.Answer(p.id, true, t(12, 10, 0)))
        assertEquals(TrackState.WORKING, r.state)
        assertEquals(t(12, 6, 53), (r.actions.first { it is Action.OpenShift } as Action.OpenShift).start)
    }
}
