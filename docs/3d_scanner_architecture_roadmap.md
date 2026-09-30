# Myndhamr — architektura, roadmapa, plan implementacji z Codexem i stack technologiczny

**Status dokumentu:** referencyjny blueprint / playbook architektury  
**Data odniesienia:** 2026-09-30  
**Cel:** zbudowanie aplikacji mobilnej do skanowania obiektów, pomieszczeń i większych scen, która zbiera maksymalnie dobre dane na telefonie, wykonuje część rekonstrukcji lokalnie, a cięższe zadania może przekazać do programu desktopowego. Projekt zakłada podejście **architecture-first, spec-driven, no-vibe-coding**: człowiek definiuje wymagania, matematykę, algorytmy i zachowanie, a Codex implementuje ściśle określone zadania.

> Ten dokument jest **trwałą mapą projektu, instrukcją projektową i punktem odniesienia**, a nie bieżącym „source of truth” stanu repozytorium. Ma zachować logikę, matematykę, założenia, uzasadnienia decyzji, zalecany stack i plan działania tak, aby po wielu eksperymentach, refaktorach albo okresie chaosu dało się szybko odzyskać orientację w projekcie. **Nie musi być aktualizowany po każdej zmianie implementacji ani każdej zmianie architektury.**
>
> Przy implementacji konkretnego zadania pierwszeństwo ma aktualna, precyzyjna specyfikacja tego zadania oraz stan repozytorium: kod, testy, schematy, benchmarki i faktyczne ograniczenia platformy. Ten dokument opisuje **domyślny kierunek i referencyjną architekturę** tam, gdzie nowsza decyzja jej nie zastąpiła. Jeżeli kod świadomie odchodzi od rozwiązania opisanego tutaj, nie należy automatycznie „naprawiać” go z powrotem tylko po to, aby zgadzał się z dokumentem.
>
> ADR/RFC można tworzyć dla decyzji, które warto zachować wraz z uzasadnieniem, ale nie jest to obowiązkowa bramka przed zmianą. Sam dokument warto aktualizować **okresowo, przy większym porządkowaniu projektu**, albo wtedy, gdy stał się na tyle nieaktualny, że przestaje spełniać rolę użytecznej mapy.

---

# 0. Najważniejsze decyzje w skrócie

1. **Telefon jest przede wszystkim inteligentnym urządzeniem pomiarowym**, a nie tylko aparatem robiącym folder JPEG-ów.
2. Każdy zapisany keyframe może zawierać RGB, pozycję i orientację kamery, intrinsics, depth, confidence depth, metadane aparatu, IMU i informacje o jakości.
3. Rekonstrukcja ma być **wielotorowa**:
   - fotogrametria/SfM + MVS dla obiektów i wysokiej jakości geometrii,
   - RGB-D/TSDF dla szybkiego skanowania pomieszczeń i podglądu,
   - Gaussian Splatting dla maksymalnego realizmu wizualnego,
   - reprezentacja hybrydowa dla luster, szkła i innych powierzchni problematycznych.
4. Dane surowe są **zawsze zachowywane**. Żaden tryb Smart nie może nadpisywać oryginalnych danych.
5. Nie ma arbitralnego limitu typu „maks. 200 zdjęć”. Są tylko limity wynikające z pamięci, miejsca, czasu i wybranego profilu jakości.
6. **Pro/Raw** nie halucynuje i nie generuje treści. Może stosować deterministyczne korekcje geometryczne i fizyczne, ale każda decyzja jest audytowalna.
7. **Smart** może klasyfikować materiały, maskować odbicia, tworzyć powierzchnie refleksyjne/przezroczyste, naprawiać typowe problemy i wybierać najlepszą reprezentację regionu.
8. **Experimental** zawiera algorytmy o większym ryzyku błędnej interpretacji: view-dependent textures, wykorzystanie luster jako wirtualnych kamer, neural appearance, zaawansowane rekonstrukcje szkła itd.
9. Pipeline desktopowy ma być **produkcyjnie bezpieczny licencyjnie**. OpenMVS jest AGPL-3.0, więc nie jest domyślną zależnością zamkniętej aplikacji. Może być używany do badań, benchmarków lub w wariancie zgodnym z jego licencją.
10. Główny produkcyjny stack geometryczny: **COLMAP + Open3D + OpenCV + Ceres + Eigen + xatlas + meshoptimizer + własne glue/algorytmy**.
11. Główny format eksportu: **glTF 2.0 / GLB**, z rozszerzeniami materiałowymi Khronos i — gdy używane są splaty — `KHR_gaussian_splatting`.
12. Kod ciężkiego core 3D: **C++20**. Logika produktu/UI/network/project management: **Kotlin Multiplatform**. Platformowa warstwa aparatu/AR: natywna dla Android/iOS.
13. Na Androidzie używamy **Camera2 + ARCore**, nie polegamy wyłącznie na CameraX. Na iOS: **AVFoundation + ARKit**.
14. Desktop UI może być współdzielony przez **Compose Multiplatform**, natomiast ciężkie obliczenia działają w osobnych workerach procesowych.
15. Gaussian Splatting na desktopie: **gsplat** jako baza. Nerfstudio może być narzędziem badawczym, ale nie musi być częścią finalnego runtime.

---

# 1. Zakres produktu

## 1.1. Typy skanów

Aplikacja powinna rozróżniać co najmniej cztery klasy problemów:

### A. Object Scan
Małe i średnie przedmioty: but, figurka, mebel, część mechaniczna, elektronika.

Dominujący pipeline:

```text
RGB keyframes
+ camera poses/intrinsics
+ optional depth
        ↓
SfM / pose refinement
        ↓
MVS / dense depth
        ↓
mesh
        ↓
UV + texture
        ↓
GLB / OBJ / PLY
```

### B. Room Scan
Pomieszczenia, mieszkania, biura.

Dominujący pipeline:

```text
RGB + metric depth + pose
        ↓
TSDF / voxel fusion
        ↓
coarse/live mesh
        ↓
optional photogrammetry refinement
        ↓
semantic/material pass
```

### C. Scene Scan
Większe miejsca: ogród, elewacja, fragment ulicy, duże wnętrze.

Dominujący pipeline jest mieszany. Geometria może pochodzić z fotogrametrii, ale reprezentacja wizualna może być wzbogacona splatami.

### D. Appearance Scan
Priorytetem jest nie tyle „idealny mesh”, co fotorealistyczny wygląd sceny z wielu punktów widzenia.

Dominujący pipeline:

```text
registered cameras
+ RGB
+ optional geometry/depth priors
        ↓
3D Gaussian Splatting
        ↓
KHR_gaussian_splatting / PLY / internal format
```

---

# 2. Tryby produktu

## 2.1. Pro / Raw

Cel: maksymalnie wierna, audytowalna rekonstrukcja bez generatywnego „ulepszania”.

Dozwolone:
- kalibracja kamery,
- korekcja dystorsji,
- bundle adjustment,
- usuwanie matematycznie oczywistych outlierów,
- odrzucenie klatki z motion blur,
- użycie depth/IMU jako danych pomiarowych,
- wykrycie płaszczyzny na podstawie geometrii,
- zaznaczenie regionu jako „niepewny”,
- fizycznie uzasadnione przypisanie materiału, jeżeli dowody są wystarczające i wynik jest oznaczony confidence.

Niedozwolone domyślnie:
- generative fill brakującej geometrii,
- generowanie niewidocznej części obiektu,
- AI texture inpainting, który tworzy niezaobserwowaną treść,
- „upiększanie” kształtu,
- neural reconstruction zastępująca niepewne dane bez zaznaczenia.

W Pro każda korekta powinna być zapisana w `audit_log` projektu.

## 2.2. Smart / User-Friendly

Cel: najlepszy praktyczny rezultat bez wymagania wiedzy z fotogrametrii.

Dodatkowo:
- semantic segmentation,
- automatyczne rozpoznawanie lustra/okna/TV/szkła/metalu,
- surface router,
- automatyczna naprawa typowych artefaktów,
- hole filling dla małych luk,
- automatyczna segmentacja obiektu i usuwanie tła,
- material estimation,
- view-dependent appearance tam, gdzie klasyczny PBR jest niewystarczający,
- automatyczna decyzja, czy region ma być meshem, splatem, reflective plane itd.

## 2.3. Experimental

Cel: pole do testowania algorytmów, które mogą dać świetny wynik, ale nie mają jeszcze wystarczającej przewidywalności.

Przykłady:
- wykorzystanie odbić w lustrze jako dodatkowych wirtualnych kamer,
- neural view synthesis dla refleksyjnych powierzchni,
- rekonstrukcja z refrakcją przez szkło,
- mieszanie mesha i Gaussians per-region,
- automatyczna synteza brakujących tekstur,
- zaawansowane inverse rendering/material decomposition.

---

# 3. Architektura wysokiego poziomu

```text
┌──────────────────────────── MOBILE APP ────────────────────────────┐
│                                                                    │
│ UI / Scan Controller                                               │
│      │                                                             │
│      ├── Capture Adapter ── Camera / AR / IMU                      │
│      │                                                             │
│      ├── Sensor Synchronizer                                       │
│      │                                                             │
│      ├── Quality Analyzer                                          │
│      │                                                             │
│      ├── Keyframe Selector                                         │
│      │                                                             │
│      ├── Coverage Engine                                           │
│      │                                                             │
│      ├── Live Preview Reconstruction ── TSDF / point cloud         │
│      │                                                             │
│      ├── Scan Package Store                                        │
│      │                                                             │
│      └── Transfer Client                                           │
│                                                                    │
└──────────────────────────────┬─────────────────────────────────────┘
                               │ LAN / USB / export package
                               ▼
┌────────────────────────── DESKTOP COMPANION ───────────────────────┐
│                                                                    │
│ Transfer Server / Project Manager                                  │
│                  │                                                 │
│                  ▼                                                 │
│        Reconstruction Orchestrator                                 │
│                  │                                                 │
│  ┌───────────────┼────────────────┬─────────────────┐              │
│  ▼               ▼                ▼                 ▼              │
│ Sparse/SfM    Dense/MVS         TSDF             3DGS              │
│ COLMAP        COLMAP/Open3D     Open3D           gsplat            │
│  │               │                │                 │              │
│  └───────────────┴────────────┬───┴─────────────────┘              │
│                               ▼                                    │
│                       Geometry Fusion                              │
│                               │                                    │
│                     Smart Surface Engine                           │
│                               │                                    │
│                  Texture / Material Pipeline                       │
│                               │                                    │
│                        Export / Viewer                              │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

---

# 4. Repozytorium i granice modułów

Proponowany monorepo:

```text
repo/
├── apps/
│   ├── mobile-android/
│   ├── mobile-ios/
│   └── desktop/
│
├── shared/
│   ├── domain/                 # KMP: modele domenowe, state machine
│   ├── scan-format/            # KMP/proto: manifest, wersjonowanie
│   ├── networking/             # KMP: transfer, pairing, discovery abstractions
│   ├── project-store/          # KMP + SQLDelight
│   └── ui-shared/              # Compose Multiplatform
│
├── platform/
│   ├── android-capture/        # Camera2 + ARCore
│   ├── ios-capture/            # AVFoundation + ARKit
│   ├── android-gpu/            # Vulkan compute później
│   └── ios-gpu/                # Metal compute później
│
├── native/
│   ├── core3d/                 # C++20, matematyka, geometry utilities
│   ├── calibration/
│   ├── keyframes-native/
│   ├── tsdf/
│   ├── texture-baker/
│   ├── surface-analysis/
│   ├── renderer-bridge/
│   └── bindings/
│       ├── jni/
│       └── swift/
│
├── desktop-workers/
│   ├── reconstruction-core/    # C++ worker
│   ├── colmap-adapter/
│   ├── open3d-adapter/
│   └── splat-worker/           # Python + PyTorch + gsplat
│
├── ml/
│   ├── datasets/
│   ├── surface-segmentation/
│   ├── training/
│   ├── export-litert/
│   └── export-coreml/
│
├── specs/
│   ├── S000-project.md
│   ├── S010-scan-format.md
│   ├── S020-capture.md
│   ├── ...
│   └── ADR/
│
├── tests/
│   ├── golden/
│   ├── synthetic/
│   ├── device/
│   ├── reconstruction/
│   └── regression/
│
├── benchmarks/
│   ├── datasets/
│   ├── configs/
│   └── reports/
│
└── tools/
    ├── scan-inspector/
    ├── dataset-converter/
    └── benchmark-runner/
```

Zasada: **UI nie zna COLMAP-a. COLMAP nie zna aplikacji mobilnej.** Wszystkie komponenty komunikują się przez jasno zdefiniowane dane domenowe i artefakty pipeline'u.

---

# 5. Kanoniczny układ współrzędnych

Bez jednej konwencji projekt bardzo szybko wpadnie w piekło znaków osi i transpozycji macierzy.

## 5.1. Konwencja projektu

Wewnątrz core używać:
- układu prawoskrętnego,
- jednostka długości: **metr**,
- +X: prawo,
- +Y: góra,
- kamera patrzy lokalnie w kierunku **-Z**,
- quaternion zapisany jako `(x, y, z, w)`,
- macierze transformacji 4×4,
- `T_A_B` oznacza transformację punktu z układu `B` do układu `A`.

Przykład:

```text
X_W = T_W_C * X_C
X_C = T_C_W * X_W
T_C_W = inverse(T_W_C)
```

Ta konwencja jest bliska OpenGL/ARCore i wygodna przy eksporcie do glTF.

## 5.2. Pose

Rigid transform:

\[
T_{WC} =
\begin{bmatrix}
R_{WC} & t_{WC} \\
0 & 1
\end{bmatrix}
\]

`R` musi być ortonormalne:

\[
R^TR = I, \qquad \det(R)=1
\]

Do optymalizacji używać reprezentacji Lie algebra `SE(3)` zamiast bezpośrednio optymalizować 16 elementów macierzy.

---

# 6. Model kamery

## 6.1. Pinhole

Macierz intrinsics:

\[
K =
\begin{bmatrix}
f_x & 0 & c_x \\
0 & f_y & c_y \\
0 & 0 & 1
\end{bmatrix}
\]

Dla punktu w układzie kamery:

\[
X_C = (X,Y,Z)^T
\]

idealna projekcja:

\[
u = f_x\frac{X}{Z}+c_x
\]

\[
v = f_y\frac{Y}{Z}+c_y
\]

W praktyce pipeline musi pracować na rzeczywistym modelu obiektywu, a nie zakładać pinhole dla każdej kamery.

## 6.2. Dystorsja Brown–Conrady

Dla znormalizowanych współrzędnych:

\[
x = X/Z, \qquad y = Y/Z
\]

\[
r^2=x^2+y^2
\]

radial distortion:

\[
L(r)=1+k_1r^2+k_2r^4+k_3r^6
\]

\[
x_d=xL(r)+2p_1xy+p_2(r^2+2x^2)
\]

\[
y_d=yL(r)+p_1(r^2+2y^2)+2p_2xy
\]

Dla ultra-wide potrzebny może być model fisheye zamiast klasycznego Brown-Conrady.

## 6.3. Zasada dotycząca obiektywów

Każda fizyczna kamera telefonu jest osobnym sensorem/kamerą kalibracyjną.

Nie wolno traktować przełączenia np. 1× → 3× jako zmiany zwykłego zoomu.

W jednym ciągłym skanie domyślnie:
- blokować fizyczny obiektyw,
- blokować cyfrowy zoom,
- zapisywać camera ID,
- zapisywać bieżące intrinsics dla każdej klatki,
- zezwalać na przełączanie obiektywu tylko jako świadomą funkcję Pro i tworzyć osobną grupę kalibracyjną.

---

# 7. Capture pipeline

## 7.1. Android

Warstwa Android:

```text
Camera2
+ ARCore
+ SensorManager
+ NDK bridge
```

### Dlaczego Camera2, a nie tylko CameraX

CameraX jest bardzo dobrym API produktowym, ale skaner potrzebuje precyzyjnej kontroli nad:
- fizycznym camera ID,
- ekspozycją,
- ISO,
- czasem ekspozycji,
- ostrością,
- timestampami,
- formatem strumienia,
- metadata capture result,
- synchronizacją z ARCore.

Camera2 daje większą kontrolę i powinno być warstwą bazową.

### ARCore

Pobieramy:
- `Camera.getPose()` / pose tracking,
- `Camera.getImageIntrinsics()`,
- Raw Depth, jeśli dostępny,
- Raw Depth confidence,
- tracking state,
- timestamps,
- point cloud pomocniczo.

Raw Depth jest szczególnie wartościowy, ponieważ jest bardziej dokładny niż wygładzony Depth i dostarcza confidence map.

### Shared Camera

ARCore potrafi dzielić Camera2 z aplikacją przez `SharedCamera`. Trzeba jednak pamiętać, że w tym trybie ARCore może utracić dostęp do sprzętowego sensora depth. Dlatego architektura nie może zakładać, że „SharedCamera + hardware depth” zawsze działa jednocześnie.

Docelowo są dwa profile capture:

#### Profile A — Tracking-first
- ARCore ma priorytet,
- stabilny pose/depth,
- geometry keyframes pochodzą z AR stream/CPU stream,
- najlepszy do room scan i szybkiej geometrii.

#### Profile B — Photo-quality-first
- Camera2 wykonuje pełniejszą kontrolę high-resolution still,
- pose jest interpolowany do timestampu ekspozycji,
- wykorzystywane głównie do wysokiej jakości tekstur,
- nie każda klatka geometryczna musi być pełnym 12/50 MP zdjęciem.

To prowadzi do ważnego podziału:

```text
geometry keyframes != texture keyframes
```

Geometria może używać 2–6 MP, a wysokiej rozdzielczości zdjęcia zachować do finalnego texturingu.

## 7.2. iOS

Warstwa iOS:

```text
AVFoundation
+ ARKit
+ CoreMotion
+ native bridge
```

AVFoundation może dostarczać zdjęcia wraz z depth i camera calibration data na obsługiwanych urządzeniach. ARKit udostępnia pose i — na urządzeniach LiDAR — `sceneDepth` / `smoothedSceneDepth` z confidence.

Object Capture / `PhotogrammetrySession` można używać jako:
- benchmark jakości,
- opcjonalny backend Apple,
- fallback użytkownika iOS.

Nie powinien być jednak centralnym elementem architektury, bo wtedy logika i zachowanie Android/iOS zbyt mocno się rozjadą.

---

# 8. Synchronizacja sensorów

To jeden z krytycznych elementów projektu.

Każdy pomiar ma timestamp w jednej wspólnej domenie czasu albo musi zostać do niej przeliczony.

## 8.1. Frame record

Minimalny logiczny rekord:

```text
FrameRecord
├── frame_id
├── capture_timestamp_ns
├── image_timestamp_ns
├── pose_timestamp_ns
├── rgb_asset_id
├── geometry_image_asset_id?
├── highres_image_asset_id?
├── depth_asset_id?
├── depth_confidence_asset_id?
├── T_W_C
├── intrinsics
├── distortion_model
├── exposure_time
├── ISO
├── aperture?
├── focal_length_mm?
├── white_balance
├── focus_distance?
├── gyro_sample_window
├── accel_sample_window
├── tracking_state
├── quality_metrics
└── semantic_masks?
```

## 8.2. Interpolacja pose

Jeżeli zdjęcie zostało wykonane pomiędzy dwoma pomiarami tracking:

- pozycja: interpolacja liniowa,
- orientacja: SLERP quaternionów.

Dla `t ∈ [0,1]`:

\[
p(t)=(1-t)p_0+tp_1
\]

\[
q(t)=SLERP(q_0,q_1,t)
\]

W Pro należy przechowywać różnicę czasu między zdjęciem a najbliższym pose i odrzucać klatki przekraczające zdefiniowany limit synchronizacji.

## 8.3. IMU preintegration

W późniejszym etapie można użyć preintegracji IMU do lepszego oszacowania ruchu między pomiarami tracking, szczególnie przy szybkim ruchu telefonu.

Nie implementować tego w pierwszym MVP, jeśli ARCore/ARKit dostarcza wystarczająco stabilny pose.

---

# 9. Unprojection depth

ARCore Raw Depth podaje odległość wzdłuż osi kamery do płaszczyzny obrazu, więc dla piksela `(u,v)` i depth `d`:

\[
X = (u-c_x)d/f_x
\]

\[
Y = (v-c_y)d/f_y
\]

\[
Z = d
\]

czyli:

\[
X_C = dK^{-1}[u,v,1]^T
\]

Następnie:

\[
X_W=T_{WC}X_C
\]

Każdy depth sample ma wagę wynikającą z confidence.

---

# 10. Ocena jakości klatki

Każda klatka dostaje zestaw metryk. Nie zapisujemy wszystkiego bezmyślnie.

## 10.1. Blur

Można użyć kilku sygnałów:
- variance of Laplacian,
- Tenengrad/gradient energy,
- gyro magnitude podczas ekspozycji,
- długość exposure time,
- opcjonalnie mały model blur classification.

Wartość musi być normalizowana względem rozdzielczości i ilości tekstury, bo gładka biała ściana naturalnie ma mało gradientów.

## 10.2. Exposure quality

Mierzyć m.in.:
- procent pikseli clipping black,
- procent pikseli clipping white,
- entropy luminance,
- lokalny kontrast,
- zmienność exposure między sąsiadującymi frame'ami.

## 10.3. Tracking quality

Klatka nie może być keyframe'em, jeśli tracking jest utracony lub właśnie się relokalizuje.

## 10.4. Depth quality

Mierzyć:
- odsetek valid depth pixels,
- median confidence,
- rozkład confidence,
- stabilność depth względem sąsiednich klatek.

## 10.5. Dynamic content

Regiony zawierające ludzi, zwierzęta, jadące auta, poruszające się zasłony itd. powinny dostać dynamic mask.

W pierwszej wersji można użyć:
- semantic segmentation,
- optical flow inconsistency,
- reprojection residual pomiędzy klatkami.

---

# 11. Keyframe Selector

To jest własna logika aplikacji i jeden z najważniejszych komponentów.

## 11.1. Cele

Keyframe powinien być zapisany, gdy:
- wnosi nową geometrię/perspektywę,
- ma odpowiednią ostrość,
- ma stabilny tracking,
- ma sensowną ekspozycję,
- nie jest prawie identyczny z ostatnim keyframe'em,
- zwiększa coverage.

## 11.2. Pose novelty

Dla poprzedniego zaakceptowanego keyframe'u:

\[
\Delta p = ||p_i-p_{last}||
\]

Rotacja:

\[
\Delta \theta = 2\arccos(|q_i\cdot q_{last}|)
\]

Sama odległość w metrach nie wystarcza. Dla małego obiektu 5 cm to dużo, dla pokoju mało.

Używać normalized baseline:

\[
b_n = \frac{||p_i-p_j||}{d_{scene}}
\]

gdzie `d_scene` jest medianą wiarygodnego depth lub szacowaną odległością do obiektu.

## 11.3. Coverage gain

Niech `G_i` oznacza przewidywany wzrost pokrycia sceny przez kandydacką klatkę.

## 11.4. Quality score

Przykładowy score:

\[
S_i =
w_gG_i +
w_tN_t +
w_rN_r +
w_sQ_{sharp} +
w_dQ_{depth} +
w_eQ_{exposure} +
w_{track}Q_{track} -
w_{dup}D_{duplicate}
\]

Przy czym krytyczne warunki są hard gate'ami, a nie tylko wagami:

```text
if tracking != TRACKING: reject
if blur < hard_min: reject
if timestamp_error > max: reject
if exposure_clip > hard_max: reject
```

## 11.5. Adaptive thresholds

Thresholdy nie mogą być stałe dla wszystkich scen.

Przykład:
- mały obiekt: mniejszy minimalny translation, większe znaczenie angular coverage,
- pokój: większa translacja, mniejsze znaczenie bardzo małych zmian kąta,
- niska ilość feature'ów: zwiększyć overlap,
- wysoka prędkość ruchu: zaostrzyć blur gate.

## 11.6. Docelowa liczba klatek

Aplikacja może pokazywać rekomendację, ale nie hard limit.

Przykład:

```text
Recommended: 180–260 geometry keyframes
Current: 312
Coverage: 96%
Marginal gain of last 20 frames: 1.2%
```

Użytkownik może kontynuować.

---

# 12. Coverage Engine

## 12.1. Obiekty — coverage sferyczny

Po wstępnym oszacowaniu środka obiektu `c` każda pozycja kamery daje kierunek obserwacji:

\[
v_i = normalize(p_i-c)
\]

Sferę wokół obiektu dzielimy na komórki, najlepiej przez subdivided icosahedron, nie zwykłe latitude/longitude, aby uniknąć nierównych pól przy biegunach.

Każda komórka przechowuje:
- liczbę obserwacji,
- najlepszą sharpness,
- najlepszy kąt obserwacji,
- medianę odległości,
- confidence,
- informację o ekspozycji,
- czy widok pochodzi z góry/dołu.

Coverage UI pokazuje nie tylko „czy byłeś w tym miejscu”, ale „czy mamy stamtąd dobry frame”.

## 12.2. Pomieszczenia

Coverage pokoju nie powinien być liczone tylko po pozycjach kamery.

Używać:
- coarse TSDF/voxel map,
- widocznych surfeli/powierzchni,
- normal powierzchni,
- histogramu obserwacji per voxel/patch.

Region uznajemy za dobrze zeskanowany, gdy ma odpowiednią liczbę obserwacji z różnym baseline'em i dobrą jakością.

---

# 13. Preprocessing obrazu

Kolejność operacji jest ważna.

## 13.1. Zasada

**Nigdy nie niszczyć oryginału.**

```text
raw/original image
        ↓
working derivative
        ↓
geometry derivative
        ↓
texture derivative
```

## 13.2. Operacje

Możliwe kroki:
1. decode do linear RGB lub kontrolowanej przestrzeni,
2. orientacja EXIF,
3. lens undistortion,
4. rolling-shutter correction później,
5. optional denoise,
6. exposure normalization tylko dla feature matching,
7. semantic/dynamic masks,
8. downscale do poziomów pyramid.

## 13.3. Geometria vs tekstury

Dla feature extraction i MVS nie zawsze należy używać pełnych 12/48/50 MP.

Przykład:

```text
50 MP original
├── 3 MP → feature extraction
├── 6 MP → dense geometry
└── 50 MP → final texture projection
```

To daje dużą oszczędność czasu i RAM bez utraty finalnej ostrości tekstury.

---

# 14. Sparse Reconstruction / SfM

Domyślny backend desktopowy: COLMAP.

## 14.1. Feature extraction

Nie pisać własnego klasycznego detektora feature'ów w MVP.

Pierwsza wersja:
- użyć backendu COLMAP,
- później można testować learned features/matchers za interfejsem `FeatureBackend`.

## 14.2. Matching graph

Nie wykonywać naiwnego `N²` exhaustive matching dla sekwencji 500 zdjęć.

Aplikacja zna:
- kolejność czasową,
- przybliżone pozycje kamer,
- kierunki patrzenia,
- depth.

Budować sparse graph kandydatów.

Dla obrazu `i` kandydatami są:
- `i ± k` w oknie czasowym,
- najbliższe kamery przestrzennie,
- kamery z odpowiednim overlap frustum,
- opcjonalne loop closures.

Przykład:

```text
500 images
naive: ~124,750 pairs
pose-aware: np. 4,000–15,000 sensownych par
```

## 14.3. Epipolar geometry

Dla znormalizowanych punktów:

\[
x_2^T E x_1 = 0
\]

\[
E=[t]_\times R
\]

Dla współrzędnych pikselowych:

\[
F=K_2^{-T}EK_1^{-1}
\]

Geometric verification przez RANSAC pozostaje obowiązkowe nawet przy pose priors z telefonu.

## 14.4. Triangulacja

Punkt 3D `X` powinien minimalizować reprojection error:

\[
X^* = \arg\min_X \sum_i \rho(||\pi(P_iX)-x_i||^2)
\]

`ρ` to robust loss.

## 14.5. Bundle Adjustment

Podstawowy cel:

\[
\min_{T_i, K_i, X_j}
\sum_{i,j} \rho( ||x_{ij} - \pi(K_i,T_i,X_j)||^2 )
\]

Do tego dodajemy miękkie priors z AR:

\[
E_{pose}=
\sum_i
r_i^T W_i r_i
\]

\[
r_i = Log(T_{prior,i}^{-1}T_i)
\]

oraz opcjonalnie depth constraints:

\[
E_{depth}=\sum_{i,j} w_{ij}(z_{pred}-z_{obs})^2
\]

Całkowity cel:

\[
E = E_{reproj} + \lambda_pE_{pose}+\lambda_dE_{depth}
\]

### Ważne

ARCore/ARKit pose **nie powinien być traktowany jako absolutna prawda**. Jest to prior, który pomaga inicjalizacji i ogranicza zły lokalny minimum. Finalny pose powinien być poprawiany przez dane obrazowe.

Wagi priors zależą od:
- tracking state,
- szybkości ruchu,
- relocalization events,
- czasu od startu,
- consistency z feature geometry.

---

# 15. Skala metryczna

Klasyczna monocular SfM ma nieokreśloną skalę.

Telefon daje kilka sposobów zakotwiczenia scale:
- metric depth,
- ARCore/ARKit translational scale,
- znana długość z kalibracji,
- opcjonalny marker/obiekt referencyjny.

Po sparse reconstruction wyznaczyć similarity transform `Sim(3)` między przestrzenią SfM a tracking world.

Minimalizować:

\[
\min_{s,R,t}\sum_i ||p_i^{AR}-(sRp_i^{SfM}+t)||^2
\]

Po tym model ma rzeczywistą skalę w metrach.

---

# 16. Dense reconstruction

## 16.1. Desktop object mode

Domyślna ścieżka:

```text
registered cameras
        ↓
COLMAP PatchMatch Stereo / dense depth maps
        ↓
depth fusion
        ↓
dense point cloud
        ↓
surface reconstruction
```

COLMAP ma kompletny pipeline MVS z depth/normal maps, fusion i mesh reconstruction.

## 16.2. Dlaczego nie OpenMVS jako domyślna zależność produkcyjna

Technicznie OpenMVS bardzo dobrze pasuje do pipeline'u: dense cloud → mesh → refinement → texture. Problemem jest **AGPL-3.0**.

Dlatego:
- można używać go lokalnie do benchmarków i porównania jakości,
- można używać w projekcie open-source zgodnym z AGPL,
- nie należy zakładać go jako bezproblemowego składnika proprietary distribution bez analizy prawnej/licencyjnej.

## 16.3. Produkcyjny wariant permissive

```text
COLMAP dense
     ↓
fused point cloud / depth maps
     ↓
Open3D surface operations
     ↓
custom mesh cleanup
     ↓
xatlas UV
     ↓
custom texture baker
```

---

# 17. TSDF / RGB-D fusion

Room mode i live preview.

## 17.1. TSDF

Dla voxela projektowanego do obrazu depth:

Niech `D(u)` będzie zmierzoną głębią, a `z` głębokością voxela w kamerze.

Signed distance:

\[
s = D(u)-z
\]

Truncated:

\[
\phi=clamp(s/\mu,-1,1)
\]

Aktualizacja:

\[
F_{new}=\frac{W F + w_i\phi}{W+w_i}
\]

\[
W_{new}=min(W+w_i,W_{max})
\]

Waga `w_i` uwzględnia:
- depth confidence,
- kąt obserwacji,
- odległość,
- motion/pose confidence.

## 17.2. VoxelBlockGrid

Zamiast gęstego ogromnego gridu użyć sparse blocks/hash map. Open3D ma gotowy VoxelBlockGrid zoptymalizowany pod akceleratory.

## 17.3. Surface extraction

Zero-crossing TSDF → Marching Cubes.

Output live nie musi być finalnym meshem. Jego rolą jest:
- feedback dla użytkownika,
- coverage,
- rough geometry,
- wykrycie braków.

---

# 18. Mesh pipeline

## 18.1. Reconstruction

W zależności od danych:
- Poisson — dobra ciągłość, ale może zamykać obszary, których nie widzieliśmy,
- Delaunay — przydatne w photogrammetry,
- Marching Cubes — naturalne dla TSDF.

## 18.2. Cleanup

Kolejność:
1. remove isolated components,
2. remove degenerate triangles,
3. orient normals,
4. non-manifold inspection,
5. optional hole fill,
6. controlled smoothing,
7. simplification,
8. normal/tangent recompute.

## 18.3. Simplification

Quadric Error Metrics.

Dla wierzchołka:

\[
E(v)=v^TQv
\]

Przy edge collapse sumujemy quadrics końców i wybieramy nową pozycję minimalizującą koszt.

Nie upraszczać wszędzie równie agresywnie.

Zwiększyć ochronę:
- krawędzi ostrych,
- wysokiej curvature,
- granic materiałów,
- granic semantycznych,
- obszarów o wysokim texture detail.

---

# 19. UV i texturing

## 19.1. UV

Biblioteka: **xatlas**.

Powody:
- mała,
- C++,
- permissive MIT,
- generuje unikalne UV,
- nadaje się do bakingu.

## 19.2. Visibility

Dla każdego texela/triangle'a wybierać tylko kamery, z których powierzchnia faktycznie była widoczna.

Potrzebne:
- frustum test,
- backface test,
- z-buffer/depth visibility,
- semantic masks.

## 19.3. Camera score dla tekstury

Dla punktu powierzchni `P`, normalnej `n`, kamery `i`:

\[
v_i = normalize(c_i-P)
\]

\[
\cos\theta_i = max(0,n\cdot v_i)
\]

Przykładowa waga:

\[
w_i=
\frac{\cos^\alpha\theta_i}{z_i^\beta}
Q_{sharp,i}
Q_{exposure,i}
Q_{mask,i}
Q_{visibility,i}
\]

Następnie:

\[
\hat w_i=\frac{w_i}{\sum_jw_j}
\]

Kolor liczyć w linear space, nie bezpośrednio w sRGB.

## 19.4. Photometric calibration

Różne zdjęcia mogą mieć inną ekspozycję/white balance.

Rozwiązać per-image gain/bias na podstawie overlapów:

\[
I_i' = a_iI_i+b_i
\]

Parametry dobierać tak, aby wspólne powierzchnie miały podobny kolor.

## 19.5. Seams

Po wyborze kamer:
- graph-cut lub podobny podział chartów,
- feathering,
- multiband blend,
- gradient-domain correction opcjonalnie.

## 19.6. Maski

Nigdy nie bake'ować do zwykłej diffuse texture regionów oznaczonych jako:
- mirror reflection,
- dynamic object,
- silny glare,
- bardzo niskie confidence,
- clipping.

---

# 20. Smart Surface Engine

To kluczowa część wyróżniająca produkt.

Zamiast pytać tylko:

> „gdzie jest powierzchnia?”

system pyta także:

> „jakiego typu jest ta powierzchnia i jaki model optyczny powinien ją reprezentować?”

## 20.1. Surface classes

Minimalnie:

```text
OPAQUE_DIFFUSE
OPAQUE_GLOSSY
METAL
MIRROR
THIN_GLASS
VOLUMETRIC_GLASS
SCREEN_OFF
SCREEN_ON
FOLIAGE
DYNAMIC
UNKNOWN_UNCERTAIN
GAUSSIAN_PREFERRED
```

## 20.2. Źródła sygnału

Finalna klasyfikacja nie może zależeć od jednego modelu AI.

Łączyć:
- semantic segmentation,
- depth confidence,
- lokalną planarity,
- multi-view photometric consistency,
- feature track consistency,
- specular highlight behaviour,
- edge/frame detection,
- temporal behaviour,
- color/brightness,
- metadata sceny.

## 20.3. Confidence fusion

Nie należy od razu pokazywać użytkownikowi fikcyjnego „94%” bez kalibracji.

Wewnętrzny score może być logit fusion:

\[
L=b+\sum_jw_j\,logit(c_j)
\]

\[
C=\sigma(L)
\]

ale mapping `C → real probability` musi zostać skalibrowany na zbiorze walidacyjnym.

Do czasu kalibracji UI powinno używać klas:
- low,
- medium,
- high confidence.

---

# 21. Lustra

## 21.1. Problem

Klasyczna fotogrametria zakłada, że punkt powierzchni ma stabilny wygląd z różnych kamer. Lustro łamie to założenie: piksel na płaszczyźnie lustra reprezentuje promień odbity i inny fragment otoczenia zależnie od punktu obserwacji.

Dlatego odbicie nie powinno być triangulowane jak zwykła tekstura.

## 21.2. Detekcja

Surface candidate może otrzymywać sygnały:

```text
semantic: mirror/window/screen
+
planar geometry
+
depth invalid/unstable
+
high view-dependent appearance
+
feature reprojection anomalies
+
rectangular frame cues
```

## 21.3. Plane fitting

Płaszczyzna:

\[
n^Tx+d=0
\]

`||n||=1`.

RANSAC dopasować przede wszystkim z:
- obramowania,
- punktów sąsiednich,
- depth o wysokim confidence,
- ewentualnych punktów samej powierzchni, jeśli sensor daje prawidłowy depth.

Nie polegać na depth „obiektu widzianego w lustrze”.

## 21.4. Odbicie punktu

Dla punktu `x`:

\[
x' = x - 2(n^Tx+d)n
\]

Dla wektora kierunku:

\[
v' = v - 2(n^Tv)n
\]

Macierz odbicia wektorów:

\[
H=I-2nn^T
\]

## 21.5. Virtual camera

Pozycja realnej kamery `c` odbita względem lustra:

\[
c' = c-2(n^Tc+d)n
\]

Osie kamery również odbijamy przez `H`.

Uwaga: odbicie zmienia handedness. Renderer musi poprawić winding/culling albo skonstruować odpowiednią macierz widoku z korektą orientacji.

## 21.6. Rendering finalny

Najlepszy przypadek, gdy scena za użytkownikiem została zeskanowana:

```text
real camera
     ↓
reflect camera across mirror plane
     ↓
render scene from virtual camera
     ↓
clip to mirror polygon
     ↓
composite on mirror material
```

To daje fizycznie poprawną zmianę perspektywy przy ruchu obserwatora.

## 21.7. View-dependent fallback

Jeżeli scena odbijana w lustrze nie została w pełni zrekonstruowana, Smart może przechować zestaw obserwacji lustra:

```text
(view_direction_i, camera_position_i, mirror_texture_i)
```

Dla aktualnego widoku wybieramy najbliższe obserwacje kątowo/przestrzennie i interpolujemy.

To jest approximation/light-field-like representation, nie prawdziwe lustro.

Dlatego:
- Smart: dozwolone,
- Pro: oznaczyć jako approximate appearance albo pozostawić physical mirror bez niezaobserwowanej treści.

## 21.8. Mirror-as-camera — Experimental

Jeżeli plane jest znany, piksele odbicia można interpretować jak obserwacje z kamery odbitej względem tej płaszczyzny.

Może to dostarczyć informacji o części sceny niewidocznej bezpośrednio.

Jest to funkcja Experimental z własnym confidence i nigdy nie może potajemnie zmieniać Pro geometry.

---

# 22. Szkło

## 22.1. Dlaczego trudniejsze od lustra

Szyba może równocześnie:
- odbijać,
- transmitować,
- refraktować,
- absorbować,
- generować podwójne odbicia.

## 22.2. Prawo Snella

\[
n_1\sin\theta_1=n_2\sin\theta_2
\]

Typowe szkło ma IOR w okolicach ~1.5, ale aplikacja nie powinna udawać dokładnego pomiaru materiału bez danych.

## 22.3. Fresnel — aproksymacja Schlicka

\[
F_0=\left(\frac{n_1-n_2}{n_1+n_2}\right)^2
\]

\[
F(\theta)=F_0+(1-F_0)(1-\cos\theta)^5
\]

Im bardziej patrzymy pod kątem stycznym, tym silniejsze odbicie.

## 22.4. Thin glass

Dla zwykłego okna w room scan najczęściej wystarczy:
- plane geometry,
- `KHR_materials_transmission`,
- `KHR_materials_ior`,
- roughness,
- opcjonalnie bardzo mała thickness.

Geometrię za oknem rekonstruować preferencyjnie z widoków, które nie przechodzą przez szybę.

## 22.5. Thick glass

Dla szklanej bryły:
- zamknięty mesh,
- `KHR_materials_volume`,
- thickness,
- IOR,
- absorption/attenuation.

Pełna rekonstrukcja geometryczna przez refrakcję jest późnym etapem projektu, nie MVP.

---

# 23. Wyłączone telewizory i ekrany

Wyłączony ekran traktować jako:
- płaską/lekko zakrzywioną powierzchnię,
- ciemny dielectric,
- niska roughness,
- wysoki specular,
- reflection environment/planar reflection zależnie od jakości.

Nie rekonstruować odbicia z ekranu jako geometrii znajdującej się „za telewizorem”.

Włączony ekran:
- `emissive` material,
- najlepiej osobny screen texture z obserwacji o najmniejszym glare,
- dynamic content oznaczyć jako temporal texture, jeśli użytkownik chce zachować konkretną klatkę.

---

# 24. Metale i silnie błyszczące materiały

Nie każdy połysk to lustro.

Surface router powinien rozróżniać:
- conductor/metal,
- glossy dielectric,
- mirror-like dielectric/conductor.

Dla materiału PBR:
- metallic,
- roughness,
- baseColor,
- specular/IOR zależnie od modelu.

Silnie view-dependent piksele mogą być wykluczane z diffuse texture bake, a ich wygląd odtwarzany przez PBR/environment reflection.

---

# 25. Gaussian Splatting

## 25.1. Cel

Nie zastępuje całego pipeline'u meshowego.

Używamy go, gdy:
- priorytetem jest wygląd,
- scena zawiera drobne elementy trudne do zmeshowania,
- roślinność,
- skomplikowane materiały,
- duże sceny do oglądania, nie do CAD/drukowania.

## 25.2. Gaussian

Dla mean `μ` i covariance `Σ`:

\[
G(x)=\exp\left(-\frac{1}{2}(x-\mu)^T\Sigma^{-1}(x-\mu)\right)
\]

Każdy splat przechowuje m.in.:
- position,
- scale,
- rotation,
- opacity,
- spherical harmonics color coefficients.

## 25.3. Covariance

Z rotacji `R` i scale `S`:

\[
\Sigma = RSS^TR^T
\]

lub równoważna reprezentacja zgodna z wybranym formatem.

## 25.4. Alpha composition

Po sortowaniu splatów wzdłuż promienia:

\[
C=\sum_i c_i\alpha_i\prod_{j<i}(1-\alpha_j)
\]

## 25.5. Format

Khronos ma już ratyfikowane `KHR_gaussian_splatting`, więc docelowy eksport powinien preferować ten standard zamiast wymyślać własny publiczny format splatów.

---

# 26. Hybrydowa reprezentacja sceny

Internal scene graph nie może zakładać „wszystko jest triangle mesh”.

```text
Scene
├── MeshNode
├── ReflectivePlaneNode
├── TransparentSurfaceNode
├── GaussianFieldNode
├── Light/EnvironmentNode
├── CameraTrajectoryNode
└── UncertainRegionNode
```

Przykład pokoju:

```text
wall       → mesh + PBR
floor      → mesh + PBR
mirror     → reflective plane
window     → transmissive plane
plant      → mesh or gaussian region
TV         → glossy plane
uncertain  → confidence overlay / optional splat
```

---

# 27. Pro vs Smart — dokładna różnica w pipeline

## Pro

```text
capture
↓
calibration
↓
quality filtering
↓
pose refinement
↓
reconstruction
↓
geometry cleanup with deterministic rules
↓
texture bake from observed pixels
↓
optional evidence-backed material tags
```

Wynik musi zawierać audit metadata:
- które zdjęcia weszły do reconstruction,
- które odrzucono i dlaczego,
- jakie maski zastosowano,
- jakie priors wykorzystano,
- jakie regiony mają niskie confidence.

## Smart

```text
Pro pipeline
+
semantic surfaces
+
material estimation
+
surface router
+
auto background removal
+
mirror/window handling
+
small hole repair
+
view-dependent fallback
+
visual optimization
```

## Experimental

```text
Smart
+
mirror virtual cameras
+
neural appearance
+
advanced glass reconstruction
+
per-region 3DGS
+
generative repair if explicitly enabled
```

---

# 28. On-device reconstruction

## 28.1. Zasada

Nie portować desktopowego pipeline'u 1:1 i nie oczekiwać, że telefon zachowa się jak RTX + 64 GB RAM.

On-device pipeline powinien być projektowany osobno pod:
- RAM,
- bandwidth,
- thermal throttling,
- battery,
- mobile GPU,
- Vulkan/Metal,
- ograniczony czas background execution.

## 28.2. Profile jakości

### Preview
- low-resolution TSDF,
- live point cloud/mesh,
- kilkadziesiąt MB–kilkaset MB working set.

### Mobile Standard
- 80–180 geometry keyframes,
- geometry images 2–4 MP,
- ograniczony dense stage,
- final mesh 100k–800k triangles.

### Mobile High
- 150–300 geometry keyframes,
- 4–6 MP working images,
- top-end devices,
- texture source może nadal pochodzić z pełnych zdjęć.

### Desktop Ultra
- 300–1000+ zdjęć,
- większe rozdzielczości MVS,
- pełniejsze refinement,
- duże atlasy tekstur,
- 3DGS high quality.

## 28.3. Hardware profiler

Przy pierwszym uruchomieniu i okresowo zbierać:
- SoC/device class,
- RAM,
- dostępny storage,
- GPU capabilities,
- Vulkan/Metal features,
- sustained benchmark,
- thermal behaviour,
- depth support.

Nie polegać wyłącznie na nazwie SoC.

## 28.4. Thermal scheduler

Monitorować:
- temperature/thermal status,
- częstotliwości pośrednio przez throughput,
- battery state,
- charging state.

Jeżeli throughput spada np. o 30–40%:
- zmniejszyć parallelism,
- zrobić krótkie cooldown windows,
- przełączyć część pracy na efficiency cores,
- zaproponować desktop.

---

# 29. Desktop reconstruction orchestrator

## 29.1. DAG

Pipeline ma być grafem zadań, nie jednym monolitycznym skryptem.

Przykład:

```text
ValidateInput
 ├─→ PreprocessGeometryImages
 ├─→ PreprocessTextureImages
 └─→ BuildMasks

PreprocessGeometryImages
 → FeatureExtract
 → PairSelection
 → Match
 → SparseReconstruction
 → PoseRefinement
 → DenseDepth
 → DepthFusion
 → Mesh
 → MeshCleanup
 → UV
 → TextureBake
 → MaterialPass
 → Export

SparseReconstruction
 → GaussianTrain (optional, parallel branch)
```

## 29.2. Content-addressed cache

Każdy node ma hash:

```text
hash(inputs + config + algorithm_version)
```

Jeśli input/config się nie zmienił, reuse artefaktu.

Dzięki temu zmiana np. `texture atlas size` nie powinna odpalać ponownie SfM.

## 29.3. Checkpointing

Po każdym ciężkim kroku zapisać wynik.

Użytkownik powinien móc:
- zamknąć aplikację,
- zrestartować PC,
- wznowić pipeline.

## 29.4. Process isolation

COLMAP, worker C++ i splat-worker uruchamiać jako osobne procesy.

Korzyści:
- crash nie zabija UI,
- łatwiejsze zarządzanie GPU memory,
- prostsze logi,
- łatwiejsze aktualizacje workerów,
- różne dependency stacks się nie gryzą.

---

# 30. Scan package

## 30.1. Format roboczy

Podczas capture projekt jest **folderem**, nie jednym ogromnym zipem.

```text
scan-<uuid>/
├── manifest.pb
├── manifest.debug.json
├── frames/
│   ├── 000001/
│   │   ├── geometry.heic
│   │   ├── highres.heic
│   │   ├── depth.zst
│   │   ├── depth_conf.zst
│   │   └── masks.zst
│   └── ...
├── sensors/
│   ├── imu.bin.zst
│   └── tracking.bin.zst
├── calibration/
│   └── cameras.pb
├── audit/
│   └── events.pb
├── derived/
│   ├── previews/
│   └── thumbnails/
└── checksums/
```

## 30.2. Export package

Do transferu można tworzyć `.scan3d` jako kontener ZIP64 lub tar-like bundle, ale:
- obrazy HEIF/JPEG nie są ponownie mocno kompresowane,
- depth/metadata używa Zstd,
- każdy duży asset ma hash,
- transfer może być resumable per asset/chunk.

## 30.3. Protobuf

Właściwy schema: Protocol Buffers.

Powody:
- wersjonowanie,
- generacja Kotlin/C++/Swift/Python,
- mniejszy rozmiar niż JSON,
- stabilne typy.

`manifest.debug.json` jest tylko dla ludzi/debuggingu.

## 30.4. Wersjonowanie

Każdy projekt:

```text
format_major
format_minor
producer_version
algorithm_versions
platform
capture_capabilities
```

Zasady:
- unknown protobuf fields zachowywać, jeśli to możliwe,
- migracje jawne,
- major bump tylko przy breaking change.

---

# 31. Transfer telefon ↔ PC

## 31.1. Discovery

LAN:
- mDNS/Bonjour,
- desktop publikuje `_scan3d._tcp`.

## 31.2. Pairing

Pierwsze parowanie:
- PC pokazuje QR,
- QR zawiera IP/hostname, ephemeral pairing token i fingerprint certyfikatu,
- telefon tworzy trust record.

## 31.3. Transport

Pierwsza produkcyjna wersja:
- TLS 1.3,
- HTTP/2,
- resumable chunk upload,
- binary asset stream,
- Protobuf control messages.

Nie projektować własnej kryptografii.

## 31.4. Resumable transfer

Każdy asset dzielony na chunki, np. 4–16 MB.

Desktop przechowuje bitmapę odebranych chunków.

Po reconnect:

```text
client: which chunks missing?
server: asset A -> [3,7,8], asset B -> complete
```

Hash per chunk + hash finalny.

---

# 32. Renderer i viewer

## 32.1. Mesh/PBR

Renderer: **Google Filament**.

Powody:
- PBR,
- mobile + desktop,
- custom materials,
- glTF tooling,
- Apache-2.0,
- sensowna wydajność.

## 32.2. Planar mirror

Renderer musi umieć:
1. utworzyć virtual reflected camera,
2. renderować do offscreen render target,
3. użyć clip plane,
4. nałożyć texture na polygon lustra,
5. poprawić culling/handedness.

## 32.3. Glass

W viewerze użyć physically based transmission/refraction, zgodnie z możliwościami runtime.

## 32.4. Gaussian viewer

Nie wciskać Gaussians na siłę do standardowego mesh renderer.

Utworzyć `IGaussianRenderer` z własnym backendem.

Pierwszy desktop viewer może korzystać z istniejącego renderera/toola. Mobile splat renderer jest późnym etapem.

---

# 33. Format eksportu

## 33.1. GLB/glTF 2.0 — primary

Dlaczego:
- interoperacyjność,
- PBR,
- rozszerzenia materiałów,
- efektywne ładowanie,
- szerokie wsparcie.

Używać m.in.:
- `KHR_materials_transmission`,
- `KHR_materials_volume`,
- `KHR_materials_ior`,
- `KHR_materials_specular`,
- `KHR_materials_clearcoat` jeśli potrzebne,
- `KHR_texture_basisu`,
- `KHR_mesh_quantization`,
- `KHR_gaussian_splatting`.

## 33.2. Kompresja

Mesh:
- meshoptimizer / gltfpack,
- KTX2/BasisU dla tekstur.

## 33.3. Mirror semantics

glTF nie opisuje wprost „planar mirror odbijający cały scene graph”.

Dlatego eksport ma dwa poziomy:

### Portable GLB
Mirror jako najlepszy możliwy standardowy PBR material.

### Native project/runtime
Dodatkowe metadata w `extras` lub prywatnym extension opisujące reflective plane i jego polygon.

Nie robić prywatnego extension obowiązkowego do samego wyświetlenia pliku — musi istnieć sensowny fallback.

## 33.4. Inne eksporty

- PLY: point clouds/debug/splats,
- OBJ: compatibility,
- USD/USDZ: Apple/pro workflows później,
- STL: tylko geometria do druku 3D, bez materiałów.

---

# 34. Confidence model

Każdy etap powinien propagować jakość, nie tylko finalny „success/fail”.

## 34.1. Frame confidence

Składowe:
- tracking,
- sharpness,
- exposure,
- timestamp sync,
- depth,
- dynamic mask proportion.

## 34.2. 3D point confidence

Składowe:
- liczba obserwacji,
- triangulation angle,
- reprojection error,
- depth consistency,
- baseline distribution.

## 34.3. Face confidence

Agregacja vertex/point confidence + visibility count.

## 34.4. Texture confidence

- liczba kamer,
- camera angle,
- source sharpness,
- seam consistency,
- saturation/glare.

## 34.5. Material confidence

Oddzielny od geometry confidence.

Możemy być bardzo pewni, że istnieje płaska powierzchnia, ale niepewni czy jest to szkło czy błyszczący plastik.

---

# 35. ML w aplikacji

ML nie powinno sterować całą geometrią jako black box.

## 35.1. Surface segmentation v1

Model startowy: lekki semantic segmentation backbone klasy SegFormer-B0 / podobny mały encoder-decoder, fine-tuned do własnych klas.

Klasy treningowe:
- mirror,
- window/thin glass,
- TV/screen,
- glossy metal,
- diffuse wall,
- floor,
- dynamic person,
- foliage,
- unknown.

## 35.2. Runtime

Android:
- LiteRT/TFLite delegate GPU/NNAPI.

iOS:
- Core ML.

Model ma być backendem wymienialnym:

```text
ISurfaceSegmentationBackend
```

Nie kodować architektury produktu na sztywno pod jeden konkretny model.

## 35.3. Offline labeling

Do anotacji można używać dużego foundation segmentera na PC, ale runtime pozostaje mały.

## 35.4. ML nigdy nie jest jedynym dowodem

Przykład mirror:

```text
ML says mirror: 0.91
but surface is non-planar
and multi-view appearance is stable
→ do not classify as mirror automatically
```

---

# 36. Rolling shutter

To funkcja Pro późniejszej fazy.

Smartfony mają rolling shutter. Przy szybkim ruchu pose zmienia się w trakcie odczytu kolejnych rzędów.

Dla wiersza `y`:

\[
t_y=t_0 + \frac{y}{H}t_{readout}
\]

Zamiast jednej macierzy pose dla całego zdjęcia można interpolować pose per feature/scanline.

Pierwszy etap:
- odrzucanie klatek z dużym gyro motion.

Późniejszy:
- rolling-shutter-aware reprojection w BA.

---

# 37. Dynamic objects

## 37.1. Detection

Region dynamiczny, gdy:
- semantic klasa jest dynamiczna,
- optical flow nie pasuje do przewidywanego camera motion,
- reprojection residual jest systematycznie wysoki w spójnym regionie.

## 37.2. Zachowanie

Pro:
- maskować region z geometrii statycznej,
- zachować raw data.

Smart:
- opcjonalnie usunąć obiekt z finalnego modelu,
- nie generować brakującego tła bez jawnej zgody/trybu Experimental.

---

# 38. Occlusion i visibility

Każdy texture/material/semantic projection musi uwzględniać occlusion.

Dla punktu `P` w kamerze `i`:
- projektuj `P` do `(u,v)`,
- porównaj `z_P` z depth/z-buffer w tym pikselu,
- jeśli `z_P > z_visible + epsilon`, kamera nie jest valid source.

To jest krytyczne dla poprawnego texturingu.

---

# 39. Background removal dla object scan

Nie robić go wyłącznie AI.

Łączyć:
- user-defined rough bounding volume,
- depth separation,
- connected components,
- semantic segmentation,
- reconstructed camera/mesh geometry.

Smart może proponować maskę, użytkownik może ją poprawić.

Pro zachowuje również wariant bez background removal.

---

# 40. Quality gates przed reconstruction

Zanim użytkownik rozpocznie 30-minutowy proces, aplikacja robi szybki validation pass.

Przykładowe błędy:
- za mało keyframe'ów,
- brak widoków góry/dół,
- tracking discontinuity,
- >20% klatek z wysokim blur,
- za mały overlap,
- zbyt duże zmiany ekspozycji,
- większość powierzchni reflective,
- storage < required estimate.

Wynik:

```text
Ready: yes
Estimated quality: High
Missing coverage: underside 18%
Risk: reflective left panel
Recommended: 12–20 more frames
```

---

# 41. Testy matematyczne

Każda funkcja transformacji ma mieć property tests.

Przykłady:

```text
inverse(T) * T ≈ I
normalize(q) keeps rotation
reflect(reflect(x, plane), plane) ≈ x
project(unproject(pixel, depth)) ≈ pixel
worldToCamera(cameraToWorld(X)) ≈ X
```

Dla numeric code tolerancje są jawne.

Nie testować floatów przez `==`.

---

# 42. Synthetic test scenes

Potrzebny własny zestaw deterministycznych scen:

1. textured cube,
2. white textureless cube,
3. sphere,
4. room with planar walls,
5. room + mirror,
6. room + window,
7. glossy black TV,
8. transparent box,
9. moving object,
10. mixed lighting,
11. rolling-shutter simulated camera.

Znając ground truth można mierzyć:
- camera pose error,
- scale error,
- point/mesh Chamfer distance,
- completeness,
- reprojection error,
- normal error,
- material classification accuracy.

---

# 43. Benchmark metrics

## Geometry
- mean/median reprojection error [px],
- Chamfer distance [mm/cm],
- completeness at threshold,
- accuracy at threshold,
- normal consistency,
- scale error [%].

## Camera
- ATE — Absolute Trajectory Error,
- RPE — Relative Pose Error.

## Texture
- seam energy,
- source resolution per texel,
- photometric consistency,
- optional SSIM/LPIPS na view synthesis testach.

## Runtime
- capture FPS,
- RAM peak,
- VRAM peak,
- thermal throttling,
- energy/battery,
- stage duration.

## Smart surfaces
- IoU per class,
- precision/recall mirror/window,
- false positive rate — szczególnie ważny, bo błędne „lustro” może zepsuć zwykłą ścianę.

---

# 44. Reproducibility

Każdy wynik reconstruction zapisuje:

```text
pipeline_version
component_versions
config hash
input scan hash
GPU/driver
OS
model versions
random seeds
```

Dla tych samych inputów i configu wynik powinien być możliwie deterministyczny.

---

# 45. Telemetria i prywatność

Domyślnie projekt powinien działać **offline-first**.

Nie ma technicznej potrzeby wysyłania zdjęć pomieszczeń do chmury.

Jeżeli kiedyś pojawi się telemetry:
- opt-in dla content telemetry,
- zwykłe crash/performance metrics bez zdjęć,
- jawne rozróżnienie metadata vs visual data.

Scan packages mogą zawierać bardzo prywatne dane wnętrz, więc traktować je jak dane wrażliwe użytkownika.

---

# 46. Security

- TLS 1.3 transfer,
- paired device fingerprints,
- no open unauthenticated LAN server,
- sandbox worker inputs,
- validate all imported scan packages,
- limits on archive expansion,
- path traversal protection,
- no shell command concatenation from filenames,
- content hash verification.

---

# 47. Pipeline selection logic

Przykładowy decision engine:

```text
if scan_type == ROOM and depth_quality >= threshold:
    run TSDF preview

if scan_type in {OBJECT, SCENE}:
    run SfM

if sufficient_overlap and target_output includes MESH:
    run MVS

if target_output includes PHOTO_REAL and gpu_supports_3dgs:
    run Gaussian branch

if reflective_surface_confidence high:
    route region to reflective representation

if glass confidence high:
    exclude/refine affected rays from classic MVS
    create transmissive material
```

Decision engine powinien zapisywać „dlaczego” podjął decyzję.

---

# 48. Failure handling

Każdy etap może zakończyć się:
- SUCCESS,
- SUCCESS_WITH_WARNINGS,
- RETRYABLE_FAILURE,
- NON_RETRYABLE_FAILURE,
- INSUFFICIENT_DATA.

Przykład:

```text
MVS: INSUFFICIENT_DATA
reason: only 19% of selected image pairs have sufficient stereo baseline
suggestion: capture more side-angle views
```

Nie pokazywać użytkownikowi tylko „Something went wrong”.

---

# 49. Pełna roadmapa wersji

Poniższe wersje są funkcjonalne, nie marketingowe. Numeracja może się zmienić.

## v0.0 — Research harness / repo foundation

Zakres:
- monorepo,
- CI,
- C++ core skeleton,
- KMP shared module,
- Protobuf schema framework,
- benchmark runner,
- golden test infrastructure,
- lightweight decision log / task specs; ADR/RFC opcjonalnie dla ważnych decyzji,
- dependency/license inventory.

Exit criteria:
- build Android/Desktop w CI,
- C++ unit test uruchamia się na host i Android,
- proto round-trip Kotlin ↔ C++.

## v0.1 — Capture Recorder

Zakres:
- Android Camera2 + ARCore,
- pose + intrinsics + timestamp,
- Raw Depth + confidence jeśli dostępne,
- IMU recording,
- podstawowy scan project store,
- ręczne keyframes,
- export scan folder.

Exit criteria:
- 15-minutowy scan bez crasha,
- wszystkie klatki mają zsynchronizowane metadata,
- scan można odtworzyć w desktop inspectorze.

## v0.2 — Desktop Ingest + Sparse Reconstruction

Zakres:
- desktop app,
- import `.scan3d`/folder,
- validation,
- COLMAP adapter,
- pair graph z kolejności/pose,
- sparse cloud,
- camera trajectory viewer,
- scale alignment z AR pose/depth.

Exit criteria:
- >95% dobrych klatek rejestruje się na testowych skanach,
- wynik ma poprawną skalę metryczną w kontrolowanych testach.

## v0.3 — Object Mesh MVP

Zakres:
- dense reconstruction,
- surface mesh,
- basic cleanup,
- normals,
- PLY/OBJ/GLB export,
- pierwszy używalny object scanner.

Exit criteria:
- pełny pipeline zdjęcia → mesh działa bez ręcznego używania CLI.

## v0.4 — Guided Capture

Zakres:
- automatic keyframe selector,
- blur/exposure/tracking gates,
- object coverage sphere,
- UI prowadzące użytkownika,
- quality preflight.

Exit criteria:
- aplikacja samodzielnie wybiera sensowny zestaw keyframe'ów,
- użytkownik widzi missing coverage przed zakończeniem skanu.

## v0.5 — Production Texturing

Zakres:
- xatlas UV,
- camera visibility,
- source-image selection,
- exposure/color balancing,
- seam blending,
- high-res texture source,
- KTX2/BasisU,
- meshoptimizer.

Exit criteria:
- GLB nadaje się do normalnego użycia bez ręcznej obróbki.

## v0.6 — Room Scan

Zakres:
- Raw Depth integration,
- TSDF/VoxelBlockGrid,
- live coarse mesh,
- room coverage,
- final room mesh,
- scale validation.

Exit criteria:
- użytkownik może przejść przez pokój i uzyskać sensowny model + mapę braków.

## v0.7 — Phone ↔ Desktop Companion

Zakres:
- mDNS,
- QR pairing,
- TLS,
- resumable upload,
- progress streaming,
- automatic result return.

Exit criteria:
- wielogigabajtowy scan może zostać przerwany i wznowiony bez restartu całego transferu.

## v0.8 — Mobile Viewer + Preview Reconstruction

Zakres:
- Filament viewer,
- live point cloud/mesh,
- low-res TSDF,
- confidence overlay,
- model inspection.

Exit criteria:
- użytkownik od razu widzi jakość capture bez PC.

## v0.9 — Pro Mode Stabilization

Zakres:
- audit log,
- deterministic pipeline configs,
- confidence maps,
- explicit no-generative guarantee,
- rolling shutter reject logic,
- reproducibility metadata.

Exit criteria:
- reconstruction jest audytowalna i powtarzalna.

## v0.10 — Smart Surface v1

Zakres:
- on-device semantic segmentation,
- surface router,
- mirror/window/TV/glossy candidates,
- dynamic object masking,
- material tags.

Exit criteria:
- model potrafi poprawnie odróżnić problematyczne regiony na datasetach testowych bez niszczenia zwykłych powierzchni.

## v0.11 — Mirror System

Zakres:
- mirror plane RANSAC,
- mirror masks,
- exclude reflection from geometry/texture,
- planar reflection renderer,
- portable GLB fallback,
- view-dependent fallback w Smart.

Exit criteria:
- lustro nie generuje fałszywego pokoju za ścianą,
- w native viewerze odbicie zmienia perspektywę poprawnie.

## v0.12 — Gaussian Splatting Desktop

Zakres:
- gsplat worker,
- initialize from COLMAP cameras/points,
- training profiles,
- viewer/export,
- `KHR_gaussian_splatting`.

Exit criteria:
- jeden scan może wygenerować mesh i splat z tego samego projektu.

## v0.13 — Glass / Advanced Materials

Zakres:
- thin glass,
- transmission + IOR,
- volume materials,
- geometry masking za szkłem,
- specular/glossy handling,
- confidence UI.

Exit criteria:
- okna nie produkują typowych phantom surfaces,
- standardowy viewer daje wiarygodny materiał.

## v0.14 — On-device Mesh Reconstruction

Zakres:
- ograniczony mobile dense pipeline,
- memory-aware scheduling,
- thermal scheduler,
- device quality tiers,
- Vulkan/Metal acceleration wybranych etapów.

Exit criteria:
- topowe telefony mogą ukończyć wybrany object scan bez PC.

## v0.15 — iOS + LiDAR

Zakres:
- AVFoundation capture,
- ARKit pose + intrinsics + timestamp integration,
- `sceneDepth` + `confidenceMap` na urządzeniach z LiDAR,
- `smoothedSceneDepth` przede wszystkim do stabilnego preview/guidance, nie jako bezwarunkowo „lepszy” pomiar finalny,
- wspólny `DepthFrame`/`DepthSource` rozróżniający hardware LiDAR/ToF, AR-estimated depth i brak depth,
- kalibracja oraz synchronizacja RGB ↔ depth ↔ pose,
- LiDAR-assisted object reconstruction: metric scale, coarse geometry/depth prior, outlier validation i pomoc na low-texture regions — bez zastępowania wysokorozdzielczej fotogrametrii RGB,
- LiDAR room TSDF z sensor-specific weighting/noise policy,
- ARKit scene mesh wyłącznie jako pomoc do live guidance/occlusion/sanity check, nie jako jedyny finalny mesh,
- KMP shared logic i ten sam scan-format,
- Core ML segmentation,
- Metal paths,
- parity tests Android/iOS oraz graceful degradation na iPhone bez LiDAR.

Exit criteria:
- ten sam scan-format i desktop pipeline obsługują Android oraz iOS,
- źródło depth jest jawne i nie udajemy, że LiDAR i estimated depth mają ten sam model błędu,
- LiDAR jest poprawnie zsynchronizowany/sklibrowany z RGB/pose w fixture/testach sprzętowych,
- room TSDF potrafi użyć LiDAR przez wspólną abstrakcję depth,
- object mode wykorzystuje LiDAR jako prior/scale/validation bez utraty drobnego detalu z RGB,
- urządzenie bez LiDAR ma jawnie zdefiniowany fallback.

## v0.16 — Robustness / Beta

Zakres:
- crash recovery,
- device matrix,
- memory stress,
- corrupted package handling,
- long scans,
- benchmark dashboards,
- installer/update path desktop.

## v1.0 — Stable Pro + Smart

Minimalna definicja v1.0:
- Android + desktop stable,
- object + room,
- quality-guided capture,
- mesh + high-quality texture,
- Pro auditability,
- basic Smart mirror/window/TV,
- LAN/USB export workflow,
- bez twardego limitu zdjęć,
- pełne resume/checkpoints.

iOS może wejść przed v1.0 lub tuż po, zależnie od priorytetu produktu.

## v1.1+

- mobile Gaussian viewer,
- mirror virtual camera reconstruction,
- advanced refractive reconstruction,
- neural material decomposition,
- multi-device collaborative scans,
- distributed PC processing,
- optional cloud worker,
- advanced LiDAR-specific paths ponad baseline z v0.15 (lepsze noise models, depth refinement/super-resolution tylko gdy zweryfikowane, bardziej agresywne sensor fusion),
- capture with external cameras.

---

# 50. Plan implementacji z Codexem — zasada pracy

Projekt ma być prowadzony jako **spec-driven implementation**.

Dla każdej funkcji najpierw powstaje spec:

```text
specs/S120-keyframe-selector.md
```

Spec zawiera:
- cel,
- wejścia,
- wyjścia,
- invariants,
- matematykę,
- stany błędów,
- performance budget,
- test vectors,
- acceptance criteria,
- pliki/moduły, które wolno zmienić,
- czego nie wolno zmieniać.

Dopiero potem Codex dostaje zadanie implementacyjne.

## 50.1. Prompt template dla Codexa

```text
Implement specification S120-keyframe-selector.md.

Authoritative sources:
- specs/S120-keyframe-selector.md
- docs/architecture.md sections 10–12

Do not change architecture, public data schemas, module boundaries, or algorithm choices.
If the specification is internally inconsistent, stop and report the exact contradiction instead of inventing a new design.

Implementation requirements:
- modify only modules listed in the spec
- add unit/property tests listed in Acceptance Tests
- run the required test commands
- report changed files, test results, and any unresolved issue
- do not add dependencies unless explicitly allowed by the spec
```

To jest ważniejsze niż wybór modelu.

---

# 51. Aktualne modele Codex — strategia po GPT-6.1 Sol

**Snapshot decyzji: 2026-09-30.** Dobór modeli ma być aktualizowany na podstawie realnych wyników w projekcie, nie nazwy modelu ani jednego leaderboardu.

Największa zmiana: **GPT-6.1 Sol zastępuje wcześniejszy GPT-6 Sol i większość rutynowego użycia Astry jako domyślny „mądry” model projektu**. OpenAI opisuje GPT-6.1 Sol jako model o near-Astra performance dla complex coding/professional work przy dużo niższym koszcie. Model wspiera reasoning `low`, `medium`, `high`, `xhigh`, `max`.

Nie oznacza to, że Astra przestała być użyteczna. Publiczne benchmarki pokazują nadal obszary, szczególnie scientific/research troubleshooting, gdzie Astra ma przewagę. Z drugiej strony benchmarki Artificial Analysis pokazują też, że wyższy reasoning effort nie jest monotonicznie lepszy dla każdego zadania — np. określony wariant `high` może wypaść lepiej niż `xhigh` na konkretnym eval. Dlatego **nie używać `max` automatycznie**.

Pełne uzasadnienie benchmarkowe znajduje się w `MODEL_ROUTING.md`, a zasady delegowania w `SUBAGENT.md`.

## 51.1. Domyślna drabina

### GPT-6 Luna — Medium
Używać do:
- boilerplate,
- powtarzalnych bindingów,
- prostych migracji po zdefiniowaniu kontraktu,
- fixtures/test scaffolding,
- rename/refactor bez zmiany logiki,
- mechanicznych poprawek po review.

### GPT-6 Luna — High
**Domyślny worker po dokładnym zdefiniowaniu zadania.**

Używać do:
- implementacji bounded tasków z gotowymi interfejsami/invariants/acceptance tests,
- Kotlin/KMP UI i state plumbing,
- storage/network glue,
- wrapperów bibliotek,
- eksploracji repo i mapowania call-pathów,
- implementacji konkretnych findings z review.

Nie dawać Lunie decyzji architektonicznych, nieznanej matematyki 3D ani sprzecznych wymagań.

### GPT-6.1 Sol — Medium
Używać do:
- zwykłego planowania/integration work,
- repo/module setup,
- normalnego review,
- wyboru między dobrze ograniczonymi rozwiązaniami,
- zadań, gdzie architektura jest już ustalona.

### GPT-6.1 Sol — High
**Domyślny „mądry” model do poważnej pracy inżynierskiej.**

Używać do:
- Camera2/ARCore/ARKit lifecycle/timing,
- concurrency/state machines,
- JNI/Swift/native interop,
- C++ integracji,
- resumable transfer/recovery,
- trudnych bugfixów,
- code review,
- implementacji geometrii, gdy matematyczny kontrakt jest już określony,
- complex implementation, którego nie warto delegować Lunie.

### GPT-6.1 Sol — XHigh
Używać, gdy **samo rozumowanie przed kodem** jest krytyczne:
- coordinate systems/transforms,
- sensor synchronization/calibration,
- pose/depth priors,
- metric scale alignment,
- TSDF/uncertainty weighting,
- visibility/texture projection/seams,
- surface router,
- mirror/reflected-camera geometry,
- LiDAR fusion,
- on-device reconstruction architecture,
- wielowarstwowy root-cause analysis,
- final review correctness-critical numerical subsystem.

### GPT-6.1 Sol — Max
Tylko wyjątkowo:
- bardzo duży, niejednoznaczny problem architektoniczny po nieudanym `xhigh`,
- finalny niezależny reasoning pass nad fundamentalną zmianą matematyczną,
- ekstremalnie trudny bug o słabej obserwowalności.

Nie używać `max` jako domyślnego modelu całej wersji.

### GPT-6 Astra — Max
**Specjalista badawczy, nie standardowy hard-task model.**

Rezerwować do problemów bardziej przypominających research niż software engineering:
- advanced refractive reconstruction,
- novel inverse rendering/material decomposition,
- nowa matematyka sensor fusion,
- research-grade numerical/scientific troubleshooting,
- sytuacja, w której GPT-6.1 Sol `xhigh` nie potrafił wiarygodnie rozstrzygnąć modelu matematycznego.

Po ustaleniu derivation/spec implementację oddać z powrotem GPT-6.1 Sol `high` lub Lunie, jeśli jest bounded.

## 51.2. Planner → worker → reviewer

Preferowany schemat koszt/jakość:

```text
GPT-6.1 Sol high/xhigh
  analiza + architektura + exact task contract
                ↓
GPT-6 Luna high
  bounded implementation + tests
                ↓
GPT-6.1 Sol high
  review against spec/invariants/tests
                ↓
Luna high
  explicit fixes
```

Dla kodu, którego lokalne decyzje są same w sobie trudne (native concurrency, GPU kernel, timing, transform math), implementerem od razu powinien być GPT-6.1 Sol `high`.

Po **dwóch nieudanych próbach** tego samego problemu na tańszym workerze nie powtarzać promptu — eskalować wg `SUBAGENT.md`.

## 51.3. Nie optymalizować pod publiczny leaderboard

Artificial Analysis jest dobrym punktem startowym, ale projekt ma zbudować własny mini-eval z realnych zadań Myndhamr: transform math, timestamp bug, pose-prior integration, TSDF bug, texture visibility, resume corruption, mirror transform, GPU lifetime. Routing modeli zmieniać wtedy na podstawie pass-rate, liczby repair iterations i rzeczywistego kosztu/limitu.

---

# 52. Realne czasy implementacji per etap + routing modeli

Założenia:
- jedna osoba definiuje architekturę/specy,
- Codex wykonuje większość pisania kodu,
- człowiek uruchamia buildy, testuje na realnym sprzęcie, ocenia output i decyduje o zmianach,
- istnieją gotowe test fixtures i jasne acceptance criteria,
- 1 „engineering day” = ok. 6 godzin skupionej pracy projektowej/implementacyjno-testowej; nie jest to 6 godzin ręcznego pisania kodu.

Czasy obejmują implementację + debug + podstawowe testy, nie tylko wygenerowanie pierwszego kodu. Kolumna „think/review” mówi, jaki model powinien podejmować decyzje; „worker” mówi, kto ma faktycznie pisać większość bounded code.

| Etap | Realny zakres | Think / review | Worker | Uwagi |
|---|---:|---|---|---|
| Repo, CI, module boundaries | 3–5 dni | 6.1 Sol medium | Luna high | dużo glue, mało trudnej matematyki |
| Protobuf scan schema + migrations | 4–7 dni | 6.1 Sol medium/high | Luna high | przy breaking schema plan + compatibility tests |
| KMP project store + SQLDelight | 4–6 dni | 6.1 Sol medium | Luna high | standardowy kod aplikacyjny |
| Android Camera2 capture | 7–12 dni | 6.1 Sol high | Luna high / 6.1 high | lifecycle/concurrency może wymagać 6.1 impl |
| ARCore integration | 5–8 dni | 6.1 Sol high | Luna high | pose/depth/timestamps krytyczne |
| Sensor timestamp synchronizer | 5–8 dni | 6.1 Sol xhigh | 6.1 Sol high | subtelne clock-domain errors |
| Calibration abstraction | 4–7 dni | 6.1 Sol xhigh | 6.1 Sol high | transform/calibration math |
| Basic scan recorder | 3–5 dni | 6.1 Sol medium | Luna high | integracja gotowych modułów |
| Quality metrics | 5–8 dni | 6.1 Sol high | Luna high | CV + device testing |
| Keyframe selector v1 | 7–12 dni | 6.1 Sol xhigh | 6.1 Sol high / Luna high po spec | własna logika + tuning |
| Object coverage engine | 5–8 dni | 6.1 Sol high/xhigh | Luna high / 6.1 high | geometria + UI |
| Room coverage engine | 7–12 dni | 6.1 Sol xhigh | 6.1 Sol high | voxel visibility/spatial logic |
| Desktop shell/project manager | 5–8 dni | 6.1 Sol medium | Luna high | CLI-first, GUI później |
| mDNS + pairing + TLS transfer | 6–10 dni | 6.1 Sol high | Luna high | networking/security boundaries |
| Resumable multi-GB transfer | 5–8 dni | 6.1 Sol high | Luna high | fault injection obowiązkowy |
| Pipeline DAG/cache/checkpoint | 7–12 dni | 6.1 Sol high | Luna high / 6.1 high | state + deterministic execution |
| COLMAP basic adapter | 4–7 dni | 6.1 Sol medium/high | Luna high | gotowe API/CLI |
| Pose-aware pair selection | 5–8 dni | 6.1 Sol xhigh | 6.1 Sol high | geometria/przestrzeń |
| AR pose prior integration | 8–14 dni | 6.1 Sol xhigh | 6.1 Sol high | BA/init/coordinate correctness |
| Metric scale alignment | 3–5 dni | 6.1 Sol xhigh | 6.1 Sol high | Sim(3), testy analityczne |
| Dense COLMAP pipeline | 5–9 dni | 6.1 Sol high | Luna high / 6.1 high | resource handling |
| Open3D mesh pipeline | 6–10 dni | 6.1 Sol high | Luna high / 6.1 high | geometry edge cases |
| Mesh cleanup/simplification | 6–10 dni | 6.1 Sol high | Luna high | gotowe algorytmy + polityka |
| xatlas UV pipeline | 3–5 dni | 6.1 Sol medium | Luna high | integracja |
| Visibility/z-buffer texture projection | 8–14 dni | 6.1 Sol xhigh | 6.1 Sol high | krytyczna projective geometry |
| Photometric balancing/seams | 8–14 dni | 6.1 Sol xhigh | 6.1 Sol high | CV/numerical tuning |
| GLB export + materials | 5–8 dni | 6.1 Sol medium/high | Luna high | standard/spec compliance |
| Filament viewer | 5–9 dni | 6.1 Sol high | Luna high | renderer lifecycle |
| TSDF room fusion | 8–14 dni | 6.1 Sol xhigh | 6.1 Sol high | Astra max tylko research-grade fusion issue |
| Live coarse mesh | 5–8 dni | 6.1 Sol high | Luna high / 6.1 high | threading/render sync |
| Mobile memory scheduler | 5–8 dni | 6.1 Sol high | 6.1 Sol high / Luna high | system programming |
| Vulkan/Metal acceleration baseline | 15–25 dni | 6.1 Sol xhigh | 6.1 Sol high/xhigh | Astra nie jest potrzebna tylko dlatego, że to GPU |
| Thermal/device profiler | 5–8 dni | 6.1 Sol high | Luna high | platform APIs/tuning |
| Surface segmentation integration | 5–8 dni | 6.1 Sol high | Luna high | ML runtime integration |
| Dataset + fine-tuning pipeline | 8–15 dni | 6.1 Sol high/xhigh | Luna high / 6.1 high | data correctness ważniejsza niż model prestige |
| Surface router | 7–12 dni | 6.1 Sol xhigh | 6.1 Sol high | multi-signal decision logic |
| Mirror detection + plane fit | 8–14 dni | 6.1 Sol xhigh | 6.1 Sol high | CV + geometry |
| Planar reflection renderer | 7–12 dni | 6.1 Sol xhigh | 6.1 Sol high | reflected-camera math |
| View-dependent mirror fallback | 10–18 dni | 6.1 Sol xhigh | 6.1 Sol high | Astra max tylko przy research escalation |
| Thin glass support | 7–12 dni | 6.1 Sol xhigh | Luna high / 6.1 high | material + masks |
| Advanced refractive glass | 20–40 dni | 6.1 Sol xhigh → Astra max jeśli potrzeba | 6.1 Sol high | problem badawczy |
| gsplat worker | 5–9 dni | 6.1 Sol high | Luna high | gotowy backend |
| Splat initialization + profiles | 5–9 dni | 6.1 Sol high/xhigh | Luna high / 6.1 high | camera/data correctness |
| `KHR_gaussian_splatting` export | 3–6 dni | 6.1 Sol medium | Luna high | standard format |
| Mobile splat renderer | 12–25 dni | 6.1 Sol xhigh | 6.1 Sol high/xhigh | GPU + sort + memory |
| iOS capture + ARKit | 10–16 dni | 6.1 Sol high | Luna high / 6.1 high | nowa platforma |
| LiDAR sync + fusion baseline | 8–14 dni | 6.1 Sol xhigh | 6.1 Sol high | sceneDepth/calibration/sensor weighting |
| Core ML + Metal parity | 7–12 dni | 6.1 Sol high | Luna high / 6.1 high | platform integration |
| Robustness/long scans | 15–30 dni | 6.1 Sol high | Luna high + 6.1 debug | dużo edge cases |
| Device compatibility matrix | 10–20 dni rozłożone | 6.1 Sol medium/high | Luna high/medium | powtarzalne fixy |
| Release packaging/updater | 6–10 dni | 6.1 Sol medium | Luna high | standard engineering |

### Ważne

Te czasy **nie sumują się idealnie liniowo**. Część prac zachodzi równolegle, a sporo testowania pojawia się w kolejnych etapach. GPT-6.1 Sol zmniejsza potrzebę sięgania po Astrę, ale nie skraca fizycznego testowania sensorów, zbierania datasetów, profilowania thermal/memory i iteracji jakościowej do zera.

---

# 53. Realny czas całego projektu

## 53.1. Minimalny techniczny MVP

Android capture → PC → COLMAP → mesh → prosta texture → GLB.

**~6–10 tygodni intensywnej pracy** przy bardzo dobrze zdefiniowanych specach i skutecznym użyciu Codexa.

To nie jest jeszcze konkurent dla najlepszych aplikacji; to dowód, że cały pionowy pipeline działa.

## 53.2. Używalna alpha

Dodatkowo:
- automatic keyframes,
- coverage,
- high-quality texture,
- transfer PC,
- room preview,
- sensowny viewer.

**~3–5 miesięcy full-time equivalent.**

## 53.3. Solidna beta / Pro Android

Dodatkowo:
- robust reconstruction,
- checkpoints,
- metric confidence,
- room mode,
- on-device preview,
- testy wielu telefonów,
- recovery.

**~6–9 miesięcy full-time equivalent.**

## 53.4. v1 z Smart Surface i lustrem

Dodatkowo:
- semantic segmentation,
- surface router,
- lustra,
- podstawowe szkło,
- Gaussian desktop,
- produkcyjny quality system.

**~9–14 miesięcy full-time equivalent.**

## 53.5. Zaawansowany produkt cross-platform

Dodatkowo:
- iOS,
- zaawansowany on-device mesh,
- mobile 3DGS,
- lepsza refrakcja szkła,
- duża device matrix,
- dopracowany UX.

**~14–22 miesięcy full-time equivalent.**

Jeżeli projekt jest robiony np. 12–15 h tygodniowo, należy przyjąć około 2–3× dłuższy kalendarz niż full-time equivalent.

### Dlaczego Codex nie redukuje tego do „miesiąca”

AI bardzo mocno redukuje:
- pisanie boilerplate,
- wyszukiwanie API,
- implementację jasno zdefiniowanych modułów,
- test scaffolding,
- refactory.

Nie redukuje proporcjonalnie:
- testowania na realnych telefonach,
- badania problemów hardware/driver,
- oceny jakości skanu,
- dataset collection,
- strojenia progów,
- błędów numerycznych,
- thermal/memory debugging,
- decyzji produktowych.

---

# 54. Full stack technologiczny

## 54.1. Języki

### Kotlin
Do:
- Android app,
- KMP shared domain,
- Compose UI,
- desktop UI,
- networking orchestration,
- project management.

### Swift
Tylko platformowe elementy iOS, których KMP nie powinien abstrahować na siłę:
- AVFoundation,
- ARKit,
- Core ML,
- Metal integration.

### C++20
Do:
- core 3D,
- matematyki,
- geometry processing,
- TSDF,
- texture baking,
- performance-critical code,
- wspólnego native core Android/iOS/desktop.

Nie wybierać C++23 jako twardego minimum, jeśli utrudni to mobile toolchains bez realnej korzyści.

### Python
Tylko desktop research/ML/splat worker:
- PyTorch,
- gsplat,
- training,
- dataset tools.

Nie robić głównej aplikacji desktopowej w Pythonie.

---

# 55. Mobile stack

## Shared
- Kotlin Multiplatform,
- Compose Multiplatform,
- Kotlin Coroutines + Flow,
- kotlinx.serialization dla lekkich UI/config danych,
- Protobuf dla scan-format/protocol,
- SQLDelight dla project DB,
- Ktor Client dla transferu.

## Android
- Camera2,
- ARCore,
- Android SensorManager,
- NDK,
- JNI,
- Vulkan później,
- LiteRT/TFLite dla małego segmentation modelu,
- Filament.

## iOS
- AVFoundation,
- ARKit,
- CoreMotion,
- Core ML,
- Metal,
- Filament lub platform-adapted shared renderer bridge, zależnie od stanu wsparcia potrzebnych efektów.

---

# 56. Native geometry stack

## Eigen
Macierze, wektory, quaternions.

## Sophus
`SO(3)`, `SE(3)`, Lie algebra i log/exp map — szczególnie przy pose residuals.

Jeżeli dependency zostanie uznane za zbędne, można utrzymać minimalną własną warstwę na Eigen, ale nie pisać całej biblioteki algebry Lie od zera bez potrzeby.

## Ceres Solver
Bundle adjustment i nonlinear least squares.

## OpenCV
- calibration helpers,
- feature/geometry utilities,
- optical flow,
- image processing,
- RANSAC helpers,
- warps,
- masks.

## Open3D
- point clouds,
- TSDF/VoxelBlockGrid,
- mesh/point processing,
- desktop geometry tools.

Nie trzeba linkować pełnego Open3D do każdej wersji mobile, jeśli rozmiar jest zbyt duży. Interfejs `ITsdfBackend` pozwala mieć lightweight mobile backend i Open3D desktop backend.

---

# 57. Photogrammetry stack

## COLMAP — produkcyjna baza

Używać do:
- feature extraction/matching na początku,
- sparse reconstruction,
- BA,
- PatchMatch MVS,
- dense fusion,
- meshing tam, gdzie daje dobry wynik.

Powody:
- dojrzały pipeline,
- BSD dla samego COLMAP,
- szeroko używany,
- CLI + biblioteka/pycolmap.

Zawsze audytować licencje third-party build dependencies.

## OpenMVS — benchmark/prototyp

Nie jako domyślna proprietary dependency z powodu AGPL-3.0.

## Open3D — permissive downstream

MIT, więc dużo bezpieczniejszy komponent produkcyjny.

---

# 58. Texture stack

- xatlas — UV unwrap / atlas packing,
- OpenCV — image sampling, masks, warps,
- własny visibility + view scorer,
- własny photometric equalizer,
- własny texture baker,
- opcjonalnie PhotoMesh jako inspiracja/benchmark, nie jako centralna zależność bez pełnego audytu jakości i utrzymania.

Dlaczego warto mieć własny baker:
- to miejsce, gdzie potrzebne są mirror/glass masks,
- trzeba wykorzystać high-res texture keyframes,
- trzeba mieć pełną kontrolę nad confidence,
- unika się AGPL texturing pipeline.

---

# 59. Mesh optimization / asset stack

- xatlas — UV,
- meshoptimizer — simplification/mesh optimization/glTF tooling,
- gltfpack — final packaging,
- Basis Universal / KTX2 — texture compression,
- cgltf albo tinygltf — zależnie od integracji C++.

Preferencja: **cgltf** dla lekkiego C API lub gotowych narzędzi meshoptimizer, jeżeli nie potrzebujemy dużego object modelu.

---

# 60. Rendering stack

## Filament
Primary mesh/PBR viewer.

## Custom planar reflection layer
Nad Filament.

## Gaussian renderer
Osobny backend za interfejsem:

```text
IRenderBackend
├── MeshPbrRenderer
└── GaussianRenderer
```

Nie blokować architektury na założeniu, że Filament natywnie zrobi wszystkie przyszłe rzeczy z 3DGS.

---

# 61. Gaussian stack

## Desktop training
- Python,
- PyTorch,
- gsplat.

Powód wyboru gsplat:
- aktywny,
- CUDA accelerated,
- Apache-2.0,
- daje większą kontrolę niż wrzucenie całej aplikacji pod Nerfstudio.

## Nerfstudio
- research harness,
- porównania,
- szybkie eksperymenty,
- niekoniecznie runtime dependency.

## Export
- `KHR_gaussian_splatting` jako główny przyszłościowy standard.

---

# 62. ML stack

## Training
- PyTorch,
- Albumentations lub własne augmentations,
- W&B/MLflow opcjonalnie do eksperymentów,
- ONNX jako intermediate tylko jeśli pomaga w konwersji.

## Android inference
- LiteRT/TFLite.

## iOS inference
- Core ML.

## Model v1
- mały SegFormer-like semantic segmentation model.

Nie używać ogromnego VLM do analizy każdego frame'u w runtime.

---

# 63. Networking stack

- Ktor server na desktopie,
- Ktor client w KMP,
- system mDNS/Bonjour adapters,
- TLS 1.3,
- HTTP/2,
- Protobuf messages,
- resumable chunk protocol,
- BLAKE3 lub SHA-256 checksums.

Preferencja:
- BLAKE3 dla szybkiego content hashing,
- SHA-256 tam, gdzie potrzebna jest maksymalna kompatybilność z istniejącym toolingiem.

---

# 64. Storage stack

## App metadata
SQLDelight/SQLite.

## Scan assets
File-based content store.

Nie wkładać wielkich obrazów/depth blobs do SQLite.

## Compression
- HEIF/JPEG/RAW dla obrazów,
- Zstd dla depth/masks/proto streams,
- KTX2 dla finalnych GPU textures.

---

# 65. Build stack

## Kotlin
- Gradle Kotlin DSL,
- version catalog,
- convention plugins.

## C++
- CMake,
- Ninja,
- desktop dependency manifest przez vcpkg lub Conan 2 — wybrać jeden i nie mieszać bez potrzeby.

Rekomendacja: **vcpkg manifest mode dla desktop workerów**, a na mobile pinned source/prebuilt artifacts dla dużych zależności.

## Python worker
- `uv`/locked environment lub ekwiwalent,
- exact dependency lock,
- worker versioned niezależnie od UI.

---

# 66. CI/CD

## CI matrix

- Linux x86_64,
- Windows x86_64,
- macOS arm64,
- Android native compile,
- iOS compile później.

## Jobs
- unit tests,
- formatting,
- static analysis,
- scan-format compatibility,
- C++ sanitizer builds,
- small synthetic reconstruction,
- GLB validation.

Ciężkie GPU benchmarks nie na każdym PR — nightly/scheduled.

---

# 67. Static analysis i jakość kodu

Kotlin:
- ktlint/Spotless,
- detekt.

C++:
- clang-format,
- clang-tidy,
- ASan/UBSan w CI,
- TSan na wybranych testach host.

Python:
- Ruff,
- mypy/pyright dla worker tooling gdzie sensowne.

---

# 68. Testing stack

Kotlin:
- kotlin.test/JUnit,
- Turbine dla Flow,
- Compose UI tests.

C++:
- GoogleTest,
- property tests dla matematyki.

Python:
- pytest.

E2E:
- własny benchmark runner,
- golden scan datasets.

---

# 69. Observability

Każdy pipeline job emituje structured events:

```text
job_id
stage
progress
input_count
output_count
elapsed_ms
cpu_mem
vram
warnings
quality_metrics
```

Logi są maszynowo analizowalne, nie tylko tekstowe.

---

# 70. Licencje — decyzje produkcyjne

| Komponent | Licencja / uwaga | Decyzja |
|---|---|---|
| COLMAP | BSD dla biblioteki; third-party osobno | używać po audycie build deps |
| Open3D | MIT | używać |
| OpenCV | Apache-2.0 | używać |
| Eigen | MPL-2.0 | używać |
| Ceres | BSD-style | używać |
| xatlas | MIT | używać |
| meshoptimizer/gltfpack | MIT | używać |
| Filament | Apache-2.0 | używać |
| gsplat | Apache-2.0 | używać |
| OpenMVS | AGPL-3.0 | benchmark/prototype lub świadoma decyzja licencyjna |
| glTF/Khronos specs | otwarte standardy | primary export |

Przed publiczną dystrybucją wygenerować SBOM i zrobić ponowny audyt wszystkich transitive dependencies.

---

# 71. Czego NIE pisać od zera

- SfM jako cały system,
- klasycznego feature detectora,
- nonlinear least-squares solvera,
- całego image codec stacku,
- kryptografii,
- glTF parsera,
- UV unwrap algorytmu,
- podstawowego PBR renderera,
- ogólnego sparse matrix solvera,
- kompresji obrazów,
- bazowej biblioteki macierzy.

---

# 72. Co pisać od zera

To jest właściwe IP/logika produktu:

- capture state machine,
- sensor synchronization layer,
- keyframe selector,
- coverage engine,
- quality scoring,
- pair graph policy wykorzystujący AR pose,
- confidence propagation,
- pipeline orchestrator i cache semantics,
- scan package semantics,
- phone ↔ PC workflow,
- surface router,
- mirror/glass decision logic,
- planar reflection integration,
- high-res texture source policy,
- texture baker/view selection jeśli nie używamy copyleft stacku,
- mobile reconstruction scheduler,
- Pro audit trail,
- Smart/Pro mode policy,
- device quality profiler,
- user guidance UX.

---

# 73. Co brać gotowe, ale mocno dostosować

- ARCore/ARKit,
- COLMAP,
- Open3D,
- Ceres,
- OpenCV,
- Filament,
- gsplat,
- segmentation backbone,
- xatlas,
- meshoptimizer.

---

# 74. Co brać praktycznie bez zmian

- Protobuf,
- SQLite,
- Zstd,
- TLS,
- JPEG/HEIF decoders,
- KTX2,
- basic hashing,
- mDNS system services,
- Eigen primitives,
- standard containers/threading utilities,
- glTF validation tooling.

---

# 75. Największe ryzyka techniczne

## R1 — capture quality na setkach modeli Androida
Mitigacja:
- device capability abstraction,
- własna compatibility DB,
- fallback paths,
- nie opierać core na jednym Samsungu/Pixelu.

## R2 — AR pose drift
Mitigacja:
- traktować AR jako prior,
- finalne BA,
- loop closure,
- Sim(3) alignment.

## R3 — RAM/thermal on-device
Mitigacja:
- pyramids,
- keyframe reduction,
- streaming pipeline,
- chunked processing,
- desktop fallback.

## R4 — lustro false positives
Mitigacja:
- multi-signal classifier,
- conservative thresholds,
- user correction,
- Pro never silently converts low-confidence surfaces.

## R5 — szkło
Mitigacja:
- zacząć od thin-glass rendering + masks,
- nie obiecywać pełnej refractive reconstruction w v1.

## R6 — licencje
Mitigacja:
- permissive-first stack,
- automated license inventory,
- OpenMVS poza default produkcją.

## R7 — AI-generated code regresses math
Mitigacja:
- specs,
- golden tests,
- property tests,
- GPT-6.1 Sol high/xhigh review krytycznych PR; Astra max tylko dla wybranych problemów research-grade,
- benchmark thresholds w CI.

---

# 76. Definition of Done dla każdego taska Codex

Task nie jest skończony, dopóki:

1. implementacja odpowiada spec 1:1,
2. task nie wprowadza niezamierzonych zmian poza swoim zakresem; świadoma zmiana architektury jest dozwolona, jeśli wynika z aktualnej specyfikacji lub decyzji projektowej,
3. wszystkie nowe public APIs mają testy,
4. numeric code ma test vectors,
5. happy path i failure path są testowane,
6. build przechodzi,
7. lint/static analysis przechodzi,
8. benchmark nie przekracza zdefiniowanego budgetu,
9. zmiany dependency są jawne,
10. dokumentacja schema/protocol jest zaktualizowana.

---

# 77. Przykładowe performance budgets

Nie są to finalne wartości, tylko architektoniczne guardraile.

## Capture loop
- żadnej ciężkiej rekonstrukcji na main thread,
- preview stabilne 30 FPS tam, gdzie hardware pozwala,
- keyframe analysis najlepiej asynchronicznie.

## UI
- brak blokowania >16–32 ms przez I/O,
- thumbnail decoding poza main thread.

## Transfer
- powinien wykorzystywać znaczną część dostępnego Wi-Fi/LAN,
- hashing/compression nie może dławić capture.

## Desktop
- worker memory budget konfigurowalny,
- nie crashować systemu przez alokację „ile się da”.

---

# 78. Rough processing profiles dla 500 zdjęć

Te wartości służą planowaniu UX, nie są obietnicą performance.

Przy 500 zdjęciach:
- nie wszystkie muszą wejść do dense reconstruction,
- wszystkie mogą zostać zachowane do texturingu/pose evidence,
- geometry selector może wybrać np. 150–300 najlepszych.

Na topowym telefonie klasy 2026 high-quality object reconstruction należy projektować UX pod **dziesiątki minut**, nie sekundy.

Desktop z mocnym GPU powinien być preferowany dla Ultra.

Najważniejszą optymalizacją jest redukcja danych **mądrze**, a nie arbitralny limit zdjęć.

---

# 79. Priorytety implementacyjne

Jeżeli celem jest jak najszybciej sprawdzić, czy pomysł ma sens, kolejność powinna być:

1. Android recorder,
2. scan format,
3. desktop ingest,
4. COLMAP sparse,
5. dense mesh,
6. texture,
7. automatic keyframes,
8. guided coverage,
9. room TSDF,
10. transfer,
11. Smart surfaces,
12. mirrors,
13. Gaussian,
14. glass,
15. advanced on-device compute,
16. iOS parity.

Nie zaczynać od szkła, mobile CUDA-equivalent czy własnego 3DGS renderera.

---

# 80. Decyzje, które należy ustalić przed pierwszą linią produkcyjnego kodu

1. Android-only MVP czy Android+iOS od początku?  
   **Rekomendacja: Android-only MVP, architektura gotowa pod iOS.**

2. Minimalny Android SDK.  
   Ustalić na podstawie ARCore Depth/device market, nie „bo tak”.

3. Czy produkt ma być proprietary czy open-source?  
   Wpływa na OpenMVS i inne zależności.

4. Czy desktop ma obsługiwać Windows/Linux/macOS od alpha?  
   **Rekomendacja: Linux + Windows najpierw, macOS zaraz potem**, ale core trzymać cross-platform.

5. Czy mobile reconstruction v1 jest requirementem v1.0?  
   **Rekomendacja: nie.** Mobile preview tak, pełny high-quality reconstruction może zostać po stabilnym PC pipeline.

6. Czy Gaussian jest v1 requirementem?  
   **Rekomendacja: desktop optional przed v1, mobile po v1.**

7. Czy Smart ma używać generative AI?  
   **Rekomendacja: nie w v1.** Najpierw deterministic + discriminative models.

---

# 81. Recommended first milestone

Pierwszym kamieniem milowym, który naprawdę waliduje projekt, nie jest ładny UI.

Powinien to być:

```text
Samsung/Pixel/Android phone
        ↓
200–500 frames + pose + intrinsics + depth
        ↓
scan package
        ↓
Desktop import
        ↓
pose-aware COLMAP
        ↓
dense geometry
        ↓
custom/permissive texture pipeline
        ↓
metric GLB
```

Jeśli ten pipeline daje lepszy lub co najmniej równie dobry wynik jak zwykłe wrzucenie zdjęć do obecnego narzędzia, wtedy warto inwestować w cały Smart layer.

---

# 82. Źródła techniczne i stan technologii

Poniższe źródła były sprawdzone przy przygotowaniu tego dokumentu. Należy je traktować jako punkt odniesienia, a przed implementacją konkretnego modułu ponownie sprawdzić aktualną dokumentację API.

## OpenAI / Codex

- GPT-6.1 Sol model: https://developers.openai.com/api/docs/models/gpt-6.1-sol
- GPT-6 / model guidance: https://developers.openai.com/api/docs/guides/latest-model
- Reasoning models: https://developers.openai.com/api/docs/guides/reasoning
- Models: https://developers.openai.com/api/docs/models
- Codex subagents: https://developers.openai.com/codex/subagents
- Codex AGENTS.md guidance: https://developers.openai.com/codex/guides/agents-md
- Codex ExecPlans/PLANS.md cookbook: https://developers.openai.com/cookbook/articles/codex_exec_plans
- Work and Codex: https://help.openai.com/en/articles/20001275-chatgpt-work-and-codex
- GPT-6.1 Sol system-card addendum: https://deploymentsafety.openai.com/gpt-6-1-sol/respecting-auto-review

## Model benchmark snapshot

- Artificial Analysis: https://artificialanalysis.ai/
- Routing interpretation and exact benchmark snapshot used in this repository: `MODEL_ROUTING.md`

Public benchmark data is used only as initial routing evidence. Myndhamr should maintain its own task-specific model eval set and revisit model/effort choices when project evidence differs.

## ARCore

- Raw Depth: https://developers.google.com/ar/develop/java/depth/raw-depth
- Depth: https://developers.google.com/ar/develop/depth
- Camera intrinsics: https://developers.google.com/ar/reference/java/com/google/ar/core/CameraIntrinsics
- Camera pose: https://developers.google.com/ar/reference/java/com/google/ar/core/Camera
- Shared Camera: https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera

## Apple

- ObjectCaptureSession: https://developer.apple.com/documentation/realitykit/objectcapturesession
- PhotogrammetrySession: https://developer.apple.com/documentation/realitykit/photogrammetrysession
- AVCapturePhoto depth/calibration: https://developer.apple.com/documentation/avfoundation/avcapturephoto
- AVCameraCalibrationData: https://developer.apple.com/documentation/avfoundation/avcameracalibrationdata
- ARKit sceneDepth: https://developer.apple.com/documentation/arkit/arframe/scenedepth

## Reconstruction

- COLMAP tutorial: https://colmap.github.io/tutorial.html
- COLMAP repository/license: https://github.com/colmap/colmap
- Open3D VoxelBlockGrid: https://www.open3d.org/docs/latest/tutorial/t_reconstruction_system/voxel_block_grid.html
- OpenMVS: https://github.com/cdcseacave/openMVS

## Gaussian Splatting

- gsplat: https://github.com/nerfstudio-project/gsplat
- Nerfstudio Splatfacto: https://docs.nerf.studio/nerfology/methods/splat.html
- `KHR_gaussian_splatting`: https://github.com/KhronosGroup/glTF/tree/main/extensions/2.0/Khronos/KHR_gaussian_splatting

## glTF / materials

- glTF 2.0 spec: https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
- Khronos glTF extensions: https://github.com/KhronosGroup/glTF/tree/main/extensions
- KHR_materials_transmission: https://github.com/KhronosGroup/glTF/tree/main/extensions/2.0/Khronos/KHR_materials_transmission
- KHR_materials_volume: https://github.com/KhronosGroup/glTF/tree/main/extensions/2.0/Khronos/KHR_materials_volume

## Rendering / assets

- Filament: https://github.com/google/filament
- Filament materials: https://google.github.io/filament/main/materials.html
- xatlas: https://github.com/jpcy/xatlas
- meshoptimizer/gltfpack: https://github.com/zeux/meshoptimizer

---

# 83. Ostateczna rekomendacja architektoniczna

Nie budować „aplikacji, która robi fotogrametrię”.

Budować **system rekonstrukcji 3D wykorzystujący pełny kontekst współczesnego telefonu**:

```text
RGB
+ Depth
+ Camera intrinsics
+ AR pose
+ IMU
+ temporal order
+ semantic surfaces
+ quality/confidence
        ↓
scene reconstruction engine
        ↓
best representation per region
```

Największą wartością projektu nie będzie samo wywołanie COLMAP-a. Będzie nią:

- sposób zbierania danych,
- wybór keyframe'ów,
- guidance podczas skanu,
- wykorzystanie pose/depth priors,
- propagacja confidence,
- brak sztucznego limitu zdjęć,
- automatyczna decyzja mobile vs desktop,
- poprawna obsługa luster/szkła/ekranów,
- hybrydowy scene model,
- bardzo dobry texture pipeline,
- zachowanie surowych danych i audytowalność Pro.

To właśnie te elementy powinny być traktowane jako własny core produktu; reszta powinna maksymalnie wykorzystywać dojrzałe biblioteki i standardy.
