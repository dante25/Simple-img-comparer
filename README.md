# Simple Img Comparer

Jednostavna Android galerija (Kotlin + Jetpack Compose) sa before/after compare slajderom.

- Brzo skeniranje: jedan MediaStore upit, thumbnaili 256px (Coil)
- Compare: ikona gore desno (ili dugi pritisak na sliku) → odaberi glavnu sliku (A) i jednu ili više za poređenje (B1, B2…)
- Donja traka: sličice odabranih slijeva → desno, kanta (delete) skroz desno
- Tap na sličicu u traci = postaje glavna (A); u compare ekranu se mijenja B slika
- Klizač lijevo/desno: A (prije) lijevo, B (poslije) desno

Otvori u Android Studiju (Sync → Run). minSdk 26.
