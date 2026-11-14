# Struktura originálního Pavel.docx (ověřeno python-docx)

- 1 tabulka, 36 řádků, 15 logických sloupců; obsahuje sloučené buňky.
- Nadpis `EVIDENCE DOCHÁZKY`.
- Odstavec 2 (index od 0) `ORGANIZACE:`.
- Odstavec 3 `Jméno : Hink ... Měsíc: ... Rok: 2021` — aktualizovat pro zvolený měsíc/rok bez zbytečného přestavění formátu.
- Řádky tabulky 0–1 jsou záhlaví; den 1 je řádek 2, den 31 řádek 32. Tedy `row = day + 1`.
- Záhlaví logických sloupců: 0 Den; 1 Příchod (dopoledne); 2 Odchod (dopoledne); 4 Příchod (odpoledne); 5 Odchod (odpoledne); 14 Hodin odprac. **Je třeba ověřit, že zvolená pole odpovídají skutečnému významu směn a rozložení formuláře.**
- Řádek 33 obsahuje podpisy a `ODPRAC. HODINY` (hodnota na konci v logickém sloupci 14); řádek 34 `PŘESČAS. HODINY` (nevymýšlet hodnotu); řádek 35 `CELKEM` (hodnota na konci).
- Pod tabulkou jsou vysvětlivky, které se musí zachovat.
- Všechny indexy jsou **orientační**, protože sloučené buňky se při přístupu přes `python-docx` opakují. Zkontroluj přímo `word/document.xml`, zachovej `gridSpan`, `vMerge`, šířky a styly.
- Uživatel výslovně odmítl nový design tabulky. Zápis musí probíhat do originálního DOCX.
- Ukázkový soubor z dřívějšího pokusu nemusí správně vyplňovat pole ORGANIZACE ani štítky; ber jej jen jako orientační kontrolu.
