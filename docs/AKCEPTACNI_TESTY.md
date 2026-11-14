# Akceptační testy — provést a reportovat PASS/FAIL

1. První spuštění bez oprávnění: vysvětlení, postupné vyžádání foreground/background location, notifikací, možnost otevřít systémové nastavení; aplikace nespadne.
2. Nastavení domácí zóny a SSID: ukládá pouze potřebné údaje; žádné heslo; Wi-Fi není jediným triggerem.
3. Odchod z domácí zóny: notifikace ANO/NE; po ANO jedna otevřená směna s časem události, ne časem kliknutí; po NE žádná směna.
4. Návrat: ANO uzavře směnu; NE ji ponechá otevřenou; opakovaný návrat nevytvoří duplicitu.
5. Soukromá cesta: odchod NE, návrat bez otevřené směny nesmí vytvořit hodiny.
6. Druhý výjezd stejný den: 07:00–15:30 + 17:00–19:00 = 10:30.
7. Zaokrouhlení: 06:50→07:00, 15:15→15:30, 15:40→15:30, 15:50→16:00. Raw hodnoty zachované.
8. Půlnoční výjezd, změna času, velmi krátká směna: korektní výpočet nebo explicitní varování.
9. Víkendy/svátky: 14. 11. 2026 sobota, 15. 11. neděle, 17. 11. státní svátek. Součet svátku se nesmí započíst dvakrát.
10. Simulovaný listopad 2026: 186:00 hodin při přesně uvedených testovacích směnách.
11. DOCX: výstup obsahuje originální záhlaví, organizaci, 30 listopadových dnů, součty; bez přidané vlastní tabulky a bez narušených podpisů/vysvětlivek. Den 31 prázdný.
12. Více intervalů, než se vejde do originálního formuláře: žádná ztráta dat; jasná příloha / varování.
13. Offline provoz, restart telefonu, zamítnutí notifikací, deaktivovaná poloha, neznámé SSID, zpožděný geofence event: aplikace se chová předvídatelně.
14. Export přes Android systémový dialog, soubor jde otevřít ve Wordu.
15. Sestavení debug APK (`assembleDebug`) a instalace na reálné zařízení, pokud je dostupné.
