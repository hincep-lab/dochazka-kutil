package cz.kutil.dochazka.core

import java.time.Duration
import java.time.Instant

/**
 * Stavy sledování:
 * HOME – doma, žádná otevřená směna.
 * OUTSIDE_PENDING – odchod ze zóny zaznamenán, čeká se na odpověď „Začal jsi pracovat?“.
 * WORKING – běží potvrzená (otevřená) směna.
 * RETURN_PENDING – návrat při otevřené směně, čeká se na odpověď „Ukončil jsi práci?“.
 * AWAY – mimo domov soukromě (odpověď NE), žádná směna.
 */
enum class TrackState { HOME, OUTSIDE_PENDING, WORKING, RETURN_PENDING, AWAY }

enum class PendingType { START, END }

enum class PendingStatus { PENDING, YES, NO, SUPERSEDED }

data class PendingInfo(
    val id: Long,
    val type: PendingType,
    val detectedAt: Instant,
    /** U START: čas návratu, pokud se uživatel vrátil dřív, než odpověděl (návrh celého intervalu). */
    val pairedEndAt: Instant? = null,
    /** U END: směna, které se návrh týká. */
    val shiftId: Long? = null,
    /** Odchod zaznamenán, ale domácí Wi-Fi byla ještě připojená – čeká se na ověření, bez oznámení. */
    val deferred: Boolean = false,
)

data class OpenShiftInfo(val id: Long, val start: Instant)

data class LastTransition(val exit: Boolean, val at: Instant)

data class Snapshot(
    val state: TrackState,
    /** Pouze nevyřízené návrhy. */
    val pending: List<PendingInfo>,
    val open: OpenShiftInfo?,
    val last: LastTransition?,
)

sealed interface TrackEvent {
    /** homeWifiConnected: true = telefon je právě na domácí Wi-Fi; null/false = neznámo/není (není důkaz nepřítomnosti). */
    /**
     * lowAccuracy: poloha, která odchod spustila, byla nepřesná (větší chyba než rozumný zlomek zóny).
     * Nejistý odchod (Wi-Fi doma nebo nepřesná poloha) se nejdřív ověří, teprve pak se uživatele ptáme.
     */
    data class GeoExit(val at: Instant, val homeWifiConnected: Boolean? = null, val lowAccuracy: Boolean = false) : TrackEvent
    data class GeoEnter(val at: Instant) : TrackEvent
    /**
     * Výsledek ověření nejistého odchodu. stillOnHomeWifi = ověřeno, že je uživatel doma
     * (domácí Wi-Fi nebo čerstvá poloha uvnitř zóny). uncertain = ověřit se nepodařilo (výpadek GPS i Wi-Fi).
     */
    data class WifiRecheck(val pendingId: Long, val stillOnHomeWifi: Boolean, val at: Instant, val uncertain: Boolean = false) : TrackEvent
    data class Answer(val pendingId: Long, val yes: Boolean, val at: Instant) : TrackEvent
    data class ManualStart(val at: Instant) : TrackEvent
    data class ManualEnd(val at: Instant) : TrackEvent
}

sealed interface Action {
    data class CreatePending(val type: PendingType, val detectedAt: Instant, val shiftId: Long?, val deferred: Boolean) : Action
    data class ResolvePending(val id: Long, val status: PendingStatus, val answeredAt: Instant) : Action
    data class SetPairedEnd(val id: Long, val endAt: Instant) : Action
    /** Odložený návrh se potvrdil (Wi-Fi už není připojená) – zobrazit oznámení. */
    data class ConfirmDeferred(val id: Long, val uncertain: Boolean = false) : Action
    /** Návrh je v rozporu s dalším vývojem (např. návrat bez odpovědi a nový odchod). */
    data class MarkConflict(val id: Long, val reason: String) : Action
    data class OpenShift(val start: Instant, val manual: Boolean) : Action
    data class CloseShift(val shiftId: Long, val end: Instant) : Action
    data class CreateClosedShift(val start: Instant, val end: Instant) : Action
    /** Falešný poplach – je vhodné zónu znovu zaregistrovat. */
    data object ReRegisterZone : Action
}

data class Reduction(
    val state: TrackState,
    val actions: List<Action>,
    /** Lidsky čitelný důvod (pro diagnostiku v aplikaci). */
    val note: String,
    /** Zda si aplikace má zapamatovat tento přechod jako poslední známý signál. */
    val newLast: LastTransition?,
)

object StateMachine {
    /** Stejný typ přechodu v tomto okně se bere jako duplicita (kolísání GPS, opakované doručení). */
    val DUPLICATE_WINDOW: Duration = Duration.ofMinutes(5)

    /** Odchod a návrat (nebo naopak) v tomto okně se bere jako kmitnutí na hranici zóny. */
    val FLUTTER_WINDOW: Duration = Duration.ofMinutes(3)

    fun reduce(snapshot: Snapshot, e: TrackEvent): Reduction {
        val s = normalize(snapshot)
        return when (e) {
            is TrackEvent.GeoExit -> onExit(s, e)
            is TrackEvent.GeoEnter -> onEnter(s, e)
            is TrackEvent.WifiRecheck -> onWifiRecheck(s, e)
            is TrackEvent.Answer -> onAnswer(s, e)
            is TrackEvent.ManualStart -> onManualStart(s, e)
            is TrackEvent.ManualEnd -> onManualEnd(s, e)
        }
    }

    /**
     * Srovná stav se skutečností v databázi – uživatel mohl otevřenou směnu ručně smazat,
     * nebo ji obnovit ze zálohy. Stav „pracuji“ bez otevřené směny (a naopak) nesmí přežít.
     */
    fun normalize(s: Snapshot): Snapshot {
        val working = s.state == TrackState.WORKING || s.state == TrackState.RETURN_PENDING
        return when {
            working && s.open == null -> s.copy(state = if (s.last?.exit == true) TrackState.AWAY else TrackState.HOME)
            !working && s.open != null -> s.copy(state = TrackState.WORKING)
            else -> s
        }
    }

    private fun same(s: Snapshot, note: String) = Reduction(s.state, emptyList(), note, s.last)

    private fun isDuplicate(s: Snapshot, exit: Boolean, at: Instant): Boolean {
        val last = s.last ?: return false
        if (last.exit != exit) return false
        val diff = Duration.between(last.at, at)
        return !diff.isNegative && diff < DUPLICATE_WINDOW
    }

    private fun onExit(s: Snapshot, e: TrackEvent.GeoExit): Reduction {
        if (isDuplicate(s, true, e.at)) return same(s, "Duplicitní odchod ignorován.")
        val last = LastTransition(true, e.at)
        return when (s.state) {
            // AWAY + odchod znamená, že se ztratila událost návratu – raději se zeptat znovu.
            TrackState.HOME, TrackState.AWAY -> {
                if (s.open != null) return Reduction(TrackState.WORKING, emptyList(), "Směna už běží.", last)
                val deferred = e.homeWifiConnected == true || e.lowAccuracy
                Reduction(
                    TrackState.OUTSIDE_PENDING,
                    listOf(Action.CreatePending(PendingType.START, e.at, null, deferred)),
                    if (deferred) "Odchod zaznamenán, ale není jistý (domácí Wi-Fi nebo nepřesná poloha) – za chvíli ho ověřím." else "Odchod – dotaz na začátek práce.",
                    last,
                )
            }
            TrackState.OUTSIDE_PENDING -> Reduction(s.state, emptyList(), "Dotaz na začátek práce už čeká.", last)
            TrackState.WORKING -> Reduction(s.state, emptyList(), "Směna běží, odchod nic nemění.", last)
            TrackState.RETURN_PENDING -> {
                val end = s.pending.filter { it.type == PendingType.END }.maxByOrNull { it.detectedAt }
                if (end != null && Duration.between(end.detectedAt, e.at) < FLUTTER_WINDOW) {
                    Reduction(
                        TrackState.WORKING,
                        listOf(Action.ResolvePending(end.id, PendingStatus.SUPERSEDED, e.at)),
                        "Krátký návrat a hned odchod – bráno jako kolísání polohy.",
                        last,
                    )
                } else {
                    // Dotaz na ukončení zůstává nevyřízený v aplikaci, lze ho potvrdit později.
                    val actions = if (end == null) emptyList() else
                        listOf(Action.MarkConflict(end.id, "Po návratu následoval další odchod bez odpovědi."))
                    Reduction(TrackState.WORKING, actions, "Odchod bez odpovědi na ukončení – návrh zůstává v aplikaci.", last)
                }
            }
        }
    }

    private fun onEnter(s: Snapshot, e: TrackEvent.GeoEnter): Reduction {
        if (isDuplicate(s, false, e.at)) return same(s, "Duplicitní návrat ignorován.")
        val last = LastTransition(false, e.at)
        return when (s.state) {
            TrackState.HOME -> Reduction(s.state, emptyList(), "Už jsi doma.", last)
            TrackState.AWAY -> Reduction(TrackState.HOME, emptyList(), "Návrat ze soukromé cesty – bez hodin.", last)
            TrackState.OUTSIDE_PENDING -> {
                val p = s.pending.filter { it.type == PendingType.START && it.pairedEndAt == null }.maxByOrNull { it.detectedAt }
                when {
                    p == null -> Reduction(TrackState.HOME, emptyList(), "Návrat domů.", last)
                    p.deferred || Duration.between(p.detectedAt, e.at) < FLUTTER_WINDOW -> Reduction(
                        TrackState.HOME,
                        listOf(Action.ResolvePending(p.id, PendingStatus.SUPERSEDED, e.at)),
                        "Krátké opuštění zóny – bráno jako kolísání polohy.",
                        last,
                    )
                    else -> Reduction(
                        TrackState.HOME,
                        listOf(Action.SetPairedEnd(p.id, e.at)),
                        "Návrat před odpovědí – navržen celý interval k potvrzení.",
                        last,
                    )
                }
            }
            TrackState.WORKING -> {
                val open = s.open ?: return Reduction(TrackState.HOME, emptyList(), "Návrat, žádná otevřená směna.", last)
                Reduction(
                    TrackState.RETURN_PENDING,
                    listOf(Action.CreatePending(PendingType.END, e.at, open.id, false)),
                    "Návrat – dotaz na ukončení práce.",
                    last,
                )
            }
            TrackState.RETURN_PENDING -> Reduction(s.state, emptyList(), "Dotaz na ukončení už čeká.", last)
        }
    }

    private fun onWifiRecheck(s: Snapshot, e: TrackEvent.WifiRecheck): Reduction {
        val p = s.pending.firstOrNull { it.id == e.pendingId && it.deferred } ?: return same(s, "Ověření Wi-Fi už není potřeba.")
        return if (e.stillOnHomeWifi) {
            Reduction(
                if (s.state == TrackState.OUTSIDE_PENDING) TrackState.HOME else s.state,
                listOf(Action.ResolvePending(p.id, PendingStatus.SUPERSEDED, e.at), Action.ReRegisterZone),
                "Ověřeno, že jsi doma – odchod byl falešný poplach.",
                null,
            )
        } else {
            Reduction(
                s.state,
                listOf(Action.ConfirmDeferred(p.id, e.uncertain)),
                if (e.uncertain) "Odchod se nepodařilo ověřit – dotaz je označen jako nejistý." else "Odchod ověřen – dotaz na začátek práce.",
                s.last,
            )
        }
    }

    private fun onAnswer(s: Snapshot, e: TrackEvent.Answer): Reduction {
        val p = s.pending.firstOrNull { it.id == e.pendingId } ?: return same(s, "Návrh už byl vyřízen.")
        return when (p.type) {
            PendingType.START -> answerStart(s, p, e)
            PendingType.END -> answerEnd(s, p, e)
        }
    }

    private fun answerStart(s: Snapshot, p: PendingInfo, e: TrackEvent.Answer): Reduction {
        if (!e.yes) {
            val next = if (s.state == TrackState.OUTSIDE_PENDING && p.pairedEndAt == null) TrackState.AWAY else s.state
            return Reduction(next, listOf(Action.ResolvePending(p.id, PendingStatus.NO, e.at)), "NE – žádný pracovní záznam.", s.last)
        }
        val pairedEnd = p.pairedEndAt
        if (pairedEnd != null) {
            return Reduction(
                s.state,
                listOf(Action.CreateClosedShift(p.detectedAt, pairedEnd), Action.ResolvePending(p.id, PendingStatus.YES, e.at)),
                "ANO – zapsán celý interval.",
                s.last,
            )
        }
        if (s.open != null) {
            return Reduction(s.state, listOf(Action.ResolvePending(p.id, PendingStatus.SUPERSEDED, e.at)), "Směna už běží – návrh zrušen.", s.last)
        }
        return Reduction(
            TrackState.WORKING,
            listOf(Action.OpenShift(p.detectedAt, false), Action.ResolvePending(p.id, PendingStatus.YES, e.at)),
            "ANO – směna otevřena časem události.",
            s.last,
        )
    }

    private fun answerEnd(s: Snapshot, p: PendingInfo, e: TrackEvent.Answer): Reduction {
        val others = s.pending.filter { it.type == PendingType.END && it.id != p.id && it.shiftId == p.shiftId }
        if (!e.yes) {
            val next = if (s.state == TrackState.RETURN_PENDING && others.isEmpty()) TrackState.WORKING else s.state
            return Reduction(next, listOf(Action.ResolvePending(p.id, PendingStatus.NO, e.at)), "NE – směna zůstává otevřená.", s.last)
        }
        val open = s.open
        if (open == null || open.id != p.shiftId || !p.detectedAt.isAfter(open.start)) {
            return Reduction(s.state, listOf(Action.ResolvePending(p.id, PendingStatus.SUPERSEDED, e.at)), "Směna už je uzavřená – návrh zrušen.", s.last)
        }
        val actions = ArrayList<Action>()
        actions.add(Action.CloseShift(open.id, p.detectedAt))
        actions.add(Action.ResolvePending(p.id, PendingStatus.YES, e.at))
        others.forEach { actions.add(Action.ResolvePending(it.id, PendingStatus.SUPERSEDED, e.at)) }
        val next = when {
            s.last?.exit == true -> TrackState.AWAY
            else -> TrackState.HOME
        }
        return Reduction(next, actions, "ANO – směna uzavřena časem návratu.", s.last)
    }

    private fun onManualStart(s: Snapshot, e: TrackEvent.ManualStart): Reduction {
        if (s.open != null) return same(s, "Směna už běží.")
        val actions = ArrayList<Action>()
        actions.add(Action.OpenShift(e.at, true))
        s.pending.filter { it.type == PendingType.START && it.pairedEndAt == null }
            .forEach { actions.add(Action.ResolvePending(it.id, PendingStatus.SUPERSEDED, e.at)) }
        return Reduction(TrackState.WORKING, actions, "Směna zahájena ručně.", s.last)
    }

    private fun onManualEnd(s: Snapshot, e: TrackEvent.ManualEnd): Reduction {
        val open = s.open ?: return same(s, "Žádná otevřená směna.")
        if (!e.at.isAfter(open.start)) return same(s, "Konec nemůže být před začátkem.")
        val actions = ArrayList<Action>()
        actions.add(Action.CloseShift(open.id, e.at))
        s.pending.filter { it.type == PendingType.END && it.shiftId == open.id }
            .forEach { actions.add(Action.ResolvePending(it.id, PendingStatus.SUPERSEDED, e.at)) }
        val next = if (s.last?.exit == true && s.state != TrackState.RETURN_PENDING) TrackState.AWAY else TrackState.HOME
        return Reduction(next, actions, "Směna ukončena ručně.", s.last)
    }
}
