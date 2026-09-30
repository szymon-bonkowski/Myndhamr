# Myndhamr — Codex project pack

**Stan odniesienia:** 2026-09-30  
**Cel:** zestaw trwałych plików sterujących i referencyjnych dla repozytorium Myndhamr.

Ten pakiet został przygotowany tak, aby projekt nadal był czytelny dla człowieka i Codexa po wielu eksperymentach, refaktorach i częściowych przebudowach. Dokumenty nie mają tworzyć biurokracji ani wymuszać ciągłego ręcznego synchronizowania wszystkiego z kodem.

## Co jest czym

- `AGENTS.md` — krótka, repozytoryjna instrukcja operacyjna ładowana przez Codexa. To plik, który ma być zawsze zwięzły i praktyczny.
- `ARCHITECTURE.md` — referencyjny opis systemu: granice modułów, pipeline'y, dane, matematyka, invariants, platformy i przepływy.
- `SUBAGENT.md` — sposób delegowania pracy pomiędzy modelami; mocny model planuje i recenzuje, tańszy model wykonuje dobrze określone zadania.
- `PLANS.md` — zasady tworzenia i prowadzenia ExecPlanów dla największych, wielogodzinnych zmian.
- `ADR.md` — lekka polityka zapisywania tylko tych decyzji, które naprawdę warto zachować na lata.
- `MODEL_ROUTING.md` — aktualna strategia doboru modeli i reasoning effort, wraz z uzasadnieniem benchmarkami z 2026-09-30.
- `.agents/skills/*/SKILL.md` — repozytoryjne skille Codexa dla każdej wersji oraz dodatkowych ciężkich workstreamów.
- `SKILLS_INDEX.md` — indeks wszystkich skillów i ich zastosowań.
- `3d_scanner_architecture_roadmap.md` — zaktualizowana wersja wcześniejszego dużego blueprintu projektu.

## Priorytet źródeł podczas implementacji

W razie sprzeczności używać tej kolejności:

1. bieżące, jawne wymaganie użytkownika i aktualny spec zadania,
2. faktyczny stan repozytorium: kod, testy, schematy, benchmarki i działające interfejsy,
3. aktywny ExecPlan dla wykonywanego zadania,
4. zaakceptowane ADR-y dotyczące trwałych kontraktów,
5. `ARCHITECTURE.md`,
6. duży `3d_scanner_architecture_roadmap.md` jako referencyjny blueprint.

Blueprint i `ARCHITECTURE.md` są mapą projektu, a nie obowiązkiem ręcznego aktualizowania po każdym commicie. Jeżeli implementacja celowo odchodzi od starszego opisu, agent nie może automatycznie „przywracać” starej architektury tylko dlatego, że jest zapisana w dokumencie.

## Zalecane umieszczenie w repo

Skopiuj zawartość pakietu do głównego katalogu repozytorium. Repozytoryjne skille pozostaw pod `.agents/skills/`.

Długie plany wykonawcze warto przechowywać w:

```text
plans/
├── active/
└── completed/
```

Specyfikacje konkretnych zmian:

```text
specs/
```

Trwałe ADR-y:

```text
docs/adr/
```

## Ważne o modelach

Strategia modeli jest datowana. GPT-6.1 Sol zmienił sensowne domyślne routowanie: przy większości trudnej pracy architektonicznej i programistycznej powinien być pierwszym wyborem przed Astrą, natomiast GPT-6 Astra pozostaje użyteczna jako celowany specjalista do naprawdę badawczych problemów naukowych, numerycznych i fizycznych. Implementacja dobrze rozpisanych, ograniczonych zadań może być delegowana do GPT-6 Luna.

Nie traktować publicznego benchmarku jako absolutnej prawdy. `MODEL_ROUTING.md` wymaga okresowego sprawdzania na własnym zestawie zadań Myndhamr.
