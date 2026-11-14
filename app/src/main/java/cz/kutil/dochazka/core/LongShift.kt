package cz.kutil.dochazka.core

/** Pravidla upozornění na zapomenuté ukončení práce (čistá logika, testovatelná bez Androidu). */
object LongShift {
    /** Po potvrzení „Stále pracuji“ se aplikace zeptá znovu až po této době. */
    const val REPEAT_AFTER_ACK_HOURS = 4

    private const val HOUR_MS = 3_600_000L

    /** true = směna překročila limit a je čas (znovu) upozornit. */
    fun isDue(nowMs: Long, startMs: Long, limitHours: Int, ackAtMs: Long?, notifiedAtMs: Long?): Boolean {
        if (nowMs - startMs < limitHours * HOUR_MS) return false
        // Okamžik, od kterého je nové upozornění na místě.
        val dueFrom = if (ackAtMs != null && ackAtMs >= startMs) ackAtMs + REPEAT_AFTER_ACK_HOURS * HOUR_MS else startMs + limitHours * HOUR_MS
        if (nowMs < dueFrom) return false
        // Už upozorněno v tomto období → neopakovat (ochrana proti duplicitním oznámením).
        return notifiedAtMs == null || notifiedAtMs < dueFrom
    }

    /** true = směna je delší než limit (pro varování přímo v aplikaci). */
    fun exceeds(nowMs: Long, startMs: Long, limitHours: Int): Boolean = nowMs - startMs >= limitHours * HOUR_MS
}
