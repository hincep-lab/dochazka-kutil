# Docházka Kutil

Tento balík obsahuje kompletní grafické podklady pro mobilní aplikaci.

| Složka | Obsah |
| --- | --- |
| `graphics` | 29 samostatných průhledných PNG s 3D renderem nářadí, potrubí, armatur, servisní dodávky a dalších rekvizit. |
| `animations/comedy` | Všech deset příběhů v jednotném 3D loutkovém stylu. |
| `animations/water` | 15 animací vody, potrubí a ventilu. |
| `mascot/movements` | 20 kratších pohybů instalatéra a dodávky. |
| `mascot/reference` | Čtyři pohledy na instalatéra a list s výrazy. |
| `previews` | Katalog PNG, přehled gagů a přehratelný MP4 sestřih. |

## Zapojení do existující aplikace

1. Nové PNG ze složky `graphics` používej jako jednotlivé grafické prvky. Při skládání potrubí srovnej osy hrdla a lehce překryj lemy spojů. Zachovej proporce a stejný vizuální průměr. Středy připojovacích hrdel jsou v souboru `PRIPOJOVACI_BODY.json` v souřadnicích od 0 do 1 od levého horního rohu.
2. Gagy v `animations/comedy` nahrazují všech deset dřívějších gagů. Používej jejich animované WebP s průhledností. MP4 náhled obsahuje dílnu a slouží k prohlížení. Gagy jsou jednorázové a trvají přibližně 7,3 nebo 8 sekund. U všech zachovej poměr stran 8:5.
3. Přesné rozměry, délku a typ smyčky načti z `manifest.json` nebo použij tabulku `PREHLED.csv`. Gagy mají záměrné loutkové snímkování 12 póz za sekundu. Enkodér může shodné pozice spojit do jednoho snímku s delší dobou zobrazení.
4. Dekorační animace nechej běžet na oddělené vrstvě, která nezachytává dotyk. Pro opětovné přehrání animovaného WebP obnov instanci přehrávače. Po uplynutí délky klipu vrstvu skryj. Gagy načítej jednotlivě podle potřeby.
5. Ihned po otevření aplikace zobraz vodní stav odpovídající stavu směny. Pohyb ventilu a závěrečnou kapku lze použít při skutečných přechodech. V režimu omezeného pohybu použij statické PNG.
6. Zachovej stávající ovládání, uložená data, exporty a funkce docházky. Ověř začátek a konec směny, výjezd, opakované spuštění gagu a průhlednost vrstev na mobilu.
