# Docházka Kutil

Nativní Android aplikace (Kotlin, Jetpack Compose, Room) pro evidenci docházky instalatéra.
Pozná odchod z domácí zóny a návrat (Android Geofencing API + domácí Wi-Fi jako pojistka),
zeptá se „Začal jsi pracovat?“ / „Ukončil jsi práci?“ a na konci měsíce vyplní docházku
**přímo do původního formuláře `Pavel_ORIGINAL.docx`**.

- čeština, časové pásmo `Europe/Prague`
- data jen v telefonu (Room), žádný cloud, žádné přihlášení, žádné reklamy
- minSdk 29 (Android 10), targetSdk 35

## Stažení APK do telefonu

1. Na GitHubu otevři repozitář → **Releases** → nejnovější vydání.
2. Stáhni soubor `DochazkaKutil-<číslo>.apk` a otevři ho.
3. Android se zeptá na povolení instalace z prohlížeče / aplikace Soubory – povol a nainstaluj.

Všechna sestavení jsou podepsaná stejným (debug) klíčem z `keystore/debug.keystore`,
takže novější APK jde nainstalovat přes starší bez ztráty dat.

## Sestavení

```
./gradlew testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. V Android Studiu stačí složku otevřít
(File → Open) a spustit konfiguraci `app`. Potřebné: JDK 17, Android SDK 35.

GitHub Actions (`.github/workflows/android.yml`) při každém pushi do `main` spustí testy,
sestaví APK, uloží ho jako artefakt a vytvoří vydání (Release).

## Jak to funguje

| Část | Kde |
|---|---|
| Zaokrouhlení, dělení přes půlnoc, součty, varování | `core/TimeCalc.kt` |
| České svátky včetně Velikonoc | `core/CzechHolidays.kt` |
| Stavový automat HOME / OUTSIDE_PENDING / WORKING / RETURN_PENDING / AWAY | `core/StateMachine.kt` |
| Export do originálního DOCX | `core/DocxExporter.kt` |
| Room databáze, záloha JSON | `data/` |
| Geofence, oznámení, Wi-Fi, restart telefonu | `track/` |
| Obrazovky (Compose, Material 3) | `ui/` |

### Export do Wordu

Export neskládá nový dokument. Z archivu `Pavel_ORIGINAL.docx` se změní jen `word/document.xml`
a v něm se pouze **vkládají textové běhy do existujících prázdných buněk a odstavců**.
Tabulka, sloučené buňky, šířky, podpisy i vysvětlivky zůstávají bajt po bajtu původní
(ověřuje test `strukturaFormulareZustavaBajtovePuvodni`). Před zápisem se struktura formuláře
validuje; pokud by neodpovídala, export skončí chybou místo zápisu naslepo.

- 1. interval dne → Dopoledne (Příchod/Odchod), 2. interval → Odpoledne (Příchod/Odchod)
- víc než 2 intervaly → v řádku „viz příloha“, všechny intervaly vypsané na druhé straně
- „Práce v sobotu / v neděli / ve svátek“ → do dvou prázdných odstavců pod vysvětlivkami
- dovolená, nemoc, lékař… → zkratka podle vysvětlivek formuláře (D, N, P, NV…), bez hodin
- `PŘESČAS. HODINY` se nevyplňuje (definice přesčasu není zadaná)

### Měsíční uzávěrka

Prvního dne v měsíci (po 7:00) přijde připomenutí. Otevře přehled minulého měsíce, kde jdou dny projít
a doplnit. Tlačítko **Export / odeslat → Odeslat e-mailem** vyplní formulář a otevře poštovní aplikaci
s přílohou a předvyplněným příjemcem (Nastavení → Měsíční uzávěrka). Aplikace sama nic neodesílá a nemá
oprávnění k internetu.

### Reálné limity Androidu

- Přechody zóny chodí se zpožděním (běžně 1–5 min), výjimečně vůbec. Proto se nic nezapisuje
  bez potvrzení, nevyřízené návrhy zůstávají v aplikaci a všechny časy jdou opravit.
- Po restartu telefonu se zóna registruje znovu (`BootReceiver`); po „Vynutit ukončení“ až po
  dalším otevření aplikace.
- Čtení názvu Wi-Fi Android omezuje; neznámá síť se nikdy nebere jako důkaz nepřítomnosti.
- Samsung: doporučeno Nastavení → Aplikace → Docházka Kutil → Baterie → Neomezeno.

## Testy

`./gradlew testDebugUnitTest` – zaokrouhlení, svátky a víkendy, součty (listopad 2026 = 186:00),
půlnoc, změna času, stavový automat (duplicity, kolísání GPS, ignorované oznámení, Wi-Fi),
export do skutečného originálu DOCX včetně přílohy.

Zadání a akceptační testy jsou v `docs/`.
