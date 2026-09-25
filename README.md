# Červená tužka

Android aplikace pro učitele: vyfotíte nebo vyberete práci žáka (fotka z telefonu, JPEG/PNG/HEIC nebo PDF),
aplikace ji nechá ohodnotit modelem Claude a chyby zakreslí přímo do stránek, jako by je opravoval učitel
červenou tužkou. Výsledek lze uložit jako PDF, sdílet nebo uložit do galerie.

## Co umí

- **Vstup:** fotoaparát (více fotek za sebou = více stránek), výběr souborů (obrázky i vícestránkové PDF),
  sdílení z jiných aplikací („Sdílet → Červená tužka“). Max. 20 stránek najednou.
- **Pokyny pro hodnocení (nepovinné):** zadání, správné řešení, bodování, ročník. Model je bere jako závazné.
- **Značky na stránce:**
  - přeškrtnutí chybného slova/čísla a oprava nad ním,
  - stříška a doplnění chybějícího (písmeno, čárka, jednotka…),
  - zakroužkování problémového místa s krátkou poznámkou,
  - fajfka u správných odpovědí (lze vypnout),
  - poznámka na okraji,
  - známka v kroužku v pravém horním rohu první stránky.
- **Slovní hodnocení:** souhrn, co se povedlo, na čem zapracovat, a očíslovaný seznam oprav s vysvětlením.
- **Ruční doladění:** klepnutím značku vyberete, tažením posunete, lze ji upravit nebo smazat;
  dlouhým stiskem na prázdném místě přidáte vlastní značku.
- **Export:** PDF (stránky s opravami + stránka se slovním hodnocením), sdílení PDF, obrázky do galerie.

## Nastavení

V aplikaci otevřete **Nastavení** a zadejte **Anthropic API klíč** (vytvoříte ho na
[console.anthropic.com](https://console.anthropic.com)). Klíč zůstává jen v telefonu. Dále lze zvolit:

- model: Claude Opus 5 (výchozí, nejpřesnější) nebo Claude Sonnet 5 (rychlejší, levnější),
- stupnici: známka 1–5, procenta, body, nebo bez známky,
- přísnost, jazyk komentářů, číslování značek, fajfky, souhrnnou stránku v PDF.

Fotky prací se odesílají ke zpracování do Claude API.

## Instalace APK

Stáhněte `cervena-tuzka.apk` (z GitHub Actions → artefakt *cervena-tuzka-apk*, nebo z Releases),
otevřete ho v telefonu a povolte instalaci z neznámých zdrojů. Vyžaduje Android 8.0 nebo novější.

## Sestavení

**Gradle / Android Studio:** projekt otevřete v Android Studiu, nebo

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

**Bez Gradle** (jen Android SDK build-tools + JDK):

```sh
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar \
BUILD_TOOLS=$ANDROID_HOME/build-tools/35.0.0 \
scripts/build-apk.sh          # build/cervena-tuzka.apk
```

GitHub Actions (`.github/workflows/build-apk.yml`) sestaví APK při každém pushi; při tagu `v*`
ho přiloží k release.

## Jak to funguje

Aplikace je napsaná v Javě bez externích knihoven (jen Android framework).

| Soubor | Úloha |
|---|---|
| `PageLoader` | PDF → stránky přes `PdfRenderer`, fotky se srovnají podle EXIF a zmenší na 2000 px |
| `ClaudeGrader` | Messages API se streamováním, adaptivním přemýšlením a strukturovaným JSON výstupem; značky mají souřadnice 0–1000 vůči stránce |
| `MarkRenderer` | kreslí značky ručně psaným písmem [Caveat](https://fonts.google.com/specimen/Caveat) (OFL) |
| `PageView`, `ResultActivity` | zobrazení a úpravy značek |
| `Exporter` | PDF (`PdfDocument`), obrázky, galerie |

Polohy značek odhaduje model z obrázku; u neobvyklého rukopisu nebo nakřivo vyfocené stránky
mohou být o kousek vedle – proto je lze v aplikaci přetáhnout. Nejlepší výsledky dává fotka
shora, rovně, s celou stránkou v záběru a dobrým světlem.
