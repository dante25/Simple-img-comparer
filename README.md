# Simple Img Comparer

Android galerija (Kotlin + Jetpack Compose) sa before/after compare slajderom i automatskim
uparivanjem anime slika s njihovim a2r (anime-to-realism) rezultatima.

## Galerija i compare
- Compare: ikona gore desno (ili dugi pritisak) → A (glavna) + jedna ili više B slika
- Donja traka: sličice odabranih slijeva → desno, kanta (briše samo B, A nikad)
- Compare ekran: slajder lijevo/desno, pinch-zoom (2 prsta) zajednički za A i B, kanta briše prikazanu B
- Brisanje je bez potvrde uz dozvolu "Pristup svim fajlovima" (traži se jednom)

## Parovi anime ↔ realizam (ikona ✨ gore)
1. Odaberi folder, app ga skenira (keširano, samo nove slike).
2. Uparivanje, od najpouzdanijeg:
   - **Metadata (ComfyUI/RunningHub PNG):** SHA-256 ulazne slike (čvor "Load Image", ne "referenca").
   - **Vizuelno:** potpis 24×32 luminance, radi za JPG i slike bez metadata.
   - Smjer (koja je anime) iz jačine kontura; kad nije jasno, grupa ide u "Provjeri".
3. Preimenovanje: `0001_anime.png`, `0001_real_1.png`, `0001_real_2.png` …
   "Vrati zadnje" poništava posljednju seriju.
4. U galeriji se varijante realizma skrivaju iza anime slike s oznakom `+N`; dodir otvara compare.

## Testovi
`./gradlew testDebugUnitTest` (Robolectric). Testovi nad stvarnim parovima čitaju folder iz
`SAMPLES_DIR` i bez njega se preskaču.
