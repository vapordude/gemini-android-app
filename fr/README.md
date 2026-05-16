# Kaimahi (FR)

> 🇫🇷 Version française · [🇬🇧 English](../README.md)

**Kaimahi** (*Te Reo Māori pour « ouvrier »*) est un poste de travail
de codage pour Android. Un agent local et un agent cloud travaillent
côte à côte — pas l'un ou l'autre, les deux en même temps. Mémoire
topologique persistante, surface OpenAPI sur 127.0.0.1, runtime Rust
écrit de zéro. Pas de bibliothèque d'inférence tierce, pas de
télémétrie distante, jamais d'extraction de données.

C'est un outil pour ceux qui veulent bricoler. La boucle d'agent, la
mémoire, la capture d'entraînement, le runtime — chaque pièce est
documentée et remplaçable.

## Points clés

- **Aucune extraction de données, jamais.** Voir
  [`PRIVACY.md`](../PRIVACY.md) — les quatre invariants.
- **Deux agents en parallèle.** Cloud + local authentifiés en même
  temps ; la politique (`PreferFirst`, `RoundRobin`…) choisit par tour.
  Les échecs API reviennent à l'agent sous forme d'événements
  structurés pour qu'il s'adapte au lieu de boucler.
- **Mémoire topologique et temporelle.** Les notes forment un DAG par
  session avec des arêtes typées (`Follows`, `CausedBy`, `Contradicts`,
  `Supersedes`, `Refines`, `References`), des fenêtres de validité et
  un rappel pondéré par fraîcheur.
- **Capture d'entraînement.** Quand les deux moteurs tournent, leurs
  réponses peuvent être enregistrées localement comme corpus de
  distillation pour affiner le modèle local. Activation explicite ;
  rien ne quitte l'appareil sans export manuel.
- **API locale OpenAPI 3.1.** Compatible OpenAI :
  `OPENAI_BASE_URL=http://127.0.0.1:<port>/v1`.
- **Runtime neutre.** L'architecture du modèle vient des métadonnées
  GGUF ; pas de liste blanche, pas d'empreinte, pas de couche de refus.

## Documentation principale

| Fichier | Contenu |
| --- | --- |
| [`README.md`](../README.md) | Vue d'ensemble (anglais). |
| [`PRIVACY.md`](../PRIVACY.md) | Engagement « ne jamais extraire ». |
| [`MIHI.md`](../MIHI.md) | Remerciements aux projets sources. |
| [`docs/AGENTIC.md`](../docs/AGENTIC.md) | Boucle d'agent, mémoire, capture. |
| [`docs/API.md`](../docs/API.md) | Contrat OpenAPI local. |
| [`docs/BRAND.md`](../docs/BRAND.md) | Identité visuelle (pounamu / kowhai / kauri). |
| [`docs/PORTING.md`](../docs/PORTING.md) | Ajouter une architecture de modèle. |
| [`docs/SCAFFOLDING.md`](../docs/SCAFFOLDING.md) | Étendre Kaimahi. |

## Licence

- **Android 8.0+** (API 26+).
- Une **clé API Gemini** (le free tier fonctionne pour le chat ; la
  génération d'image nécessite l'activation du billing sur le projet
  Google Cloud associé) : <https://aistudio.google.com/app/apikey>.
- **Optionnel** — [Termux](https://f-droid.org/packages/com.termux/)
  depuis F-Droid ou [les releases GitHub](https://github.com/termux/termux-app/releases)
  (⚠️ **pas** la version Play Store, abandonnée depuis 2020) si vous
  voulez l'exécution de commandes shell.

Pour compiler depuis les sources :
- **Android SDK** (API 34+), **JDK 17** dans `JAVA_HOME`.

## 🚀 Installation

### Option 1 : APK pré-compilé

Télécharger la dernière APK depuis la
[page Releases](https://github.com/vapordude/gemini-android-app/releases).
Chaque tag publie `gemini-android-app-<tag>-debug.apk` et
`gemini-android-app-<tag>-release.apk`. La variante release utilise le
keystore de release configuré quand les secrets CI sont présents ; sinon
elle retombe sur une signature debug pour rester installable.

### Option 2 : compiler depuis les sources

```bash
git clone https://github.com/aciderix/gemini-android-app
cd gemini-android-app
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 💻 Configuration initiale

1. **Settings → Account** : coller votre clé API Gemini. Stockée
   chiffrée sur l'appareil.
2. **Nom du dossier en top-bar → Change folder** : choisir un
   workspace sous `/storage/emulated/0/` (évitez `/Android/data/…`,
   inaccessible à Termux). Les outils fichiers opèrent relativement à
   ce dossier.
3. **Settings → Termux shell** (optionnel, une fois) : suivre le guide
   en 3 étapes pour autoriser `run_shell_command`. Il faut Termux
   installé et `termux-setup-storage` lancé une fois.
4. **Nom du modèle en top-bar** : tapez pour en choisir un.
   `gemini-2.5-flash` pour le coding quotidien, `gemini-2.5-pro` pour
   le raisonnement plus lourd, `gemini-2.5-flash-image-preview` pour la
   génération d'image inline (nécessite billing).

## 🧱 Architecture

Projet Gradle multi-modules :

| Module           | Rôle                                                                  |
|------------------|-----------------------------------------------------------------------|
| `:app`           | UI Compose, ViewModels, Activity                                      |
| `:core-bridge`   | Client REST Gemini, registry d'outils, Termux IPC, SAF, prefs         |
| `:domain`        | Types purs (`GeminiMessage`, `ToolSpec`, `GeminiEvent`…) — sans Android |
| `:ui-components` | Tokens de design (theme, couleurs) partagés                           |

Tout le réseau passe par `RestGeminiCore` qui émet des `GeminiEvent`
consommés par `ChatViewModel`. Pas de framework DI, pas de Room :
`SharedPreferences` + fichiers JSON sous `filesDir`.

## ⚙️ Configuration avancée

### Auto-compression

Le modèle renvoie `usageMetadata.totalTokenCount` à chaque réponse. Dès
que `total / inputTokenLimit` dépasse le seuil (défaut **70 %**), la
conversation est résumée dans une nouvelle session en arrière-plan —
un bandeau non-bloquant signale l'opération.

Réglage du seuil dans **Settings → Auto-compression** (50 % → 95 %) ou
désactivation totale.

### Génération d'image

- **Imagen** (`imagen-3.0-generate-002` par défaut, picker dans
  Settings → Model) — appelée via l'outil function `generate_image`
  quand le modèle décide qu'il en faut une.
- **Gemini 2.5 Flash Image / Nano Banana** — choisissez-le comme
  modèle de chat, l'app active automatiquement
  `responseModalities: [TEXT, IMAGE]` sur chaque requête pour que le
  modèle renvoie des images inline.
- Les images sont sauvegardées dans `<filesDir>/attachments/` et
  apparaissent en vignettes dans la bulle ; les exports de chat et
  reloads les préservent.

Les deux chemins image nécessitent le billing activé sur le projet
Google Cloud lié à votre clé API — le free tier Gemini a un quota de
**0** pour ces modèles.

### Commandes shell en arrière-plan

`run_shell_command` accepte un flag `background: true` pour que le
modèle puisse lancer des processus de longue durée (serveurs web, file
watchers, boucles d'entraînement) sans que le timeout IPC Termux de
12 secondes ne les tue. La sortie atterrit dans
`$HOME/.gemini-bg/run-<id>.log`, consultable à la demande.

## 🧪 Compatibilité Gemini

Testé avec :

- `gemini-2.5-pro` / `gemini-2.5-flash` / `gemini-2.5-flash-image-preview`
- `gemini-2.0-flash`
- `gemini-1.5-pro` / `gemini-1.5-flash`

Les modèles **Gemma** (`gemma-2-*`, `gemma-3-*`) apparaissent dans le
picker mais ne supportent pas le function calling — ils fonctionnent
pour du chat pur mais la stack d'outils sera indisponible.

## 🤝 Contribuer

Les contributions sont les bienvenues :

1. **Forkez** le projet.
2. Créez une branche : `git checkout -b feature/ma-feature`.
3. Commitez avec un message clair : `git commit -m "Ajout de ma feature"`.
4. Poussez : `git push origin feature/ma-feature`.
5. Ouvrez une **Pull Request**.

### Setup dev

- **Langage** : Kotlin 1.9.24, target JDK 17.
- **UI** : Compose BOM 2024.06.00 + Material 3.
- **Versions centralisées** dans `gradle/libs.versions.toml`.

Commandes utiles :
```bash
./gradlew :app:assembleDebug        # APK debug
./gradlew :app:lint                 # Android lint
./gradlew :core-bridge:test         # tests unitaires
```

Les releases manuelles doivent passer par le workflow GitHub Actions
**Release** avec un tag valide comme `v1.0.0`.

## 📄 Licence

Distribué sous la licence **Apache 2.0**. Voir [`LICENSE`](../LICENSE)
pour les détails.

## 🙏 Crédits

- **Inspiration** : [Gemini CLI](https://github.com/google-gemini/gemini-cli) de Google.
- **Modèle** : [Gemini API](https://ai.google.dev/) de Google.
- **Pont shell** : [Termux](https://termux.dev/) — merveille open-source.
- **UI** : [Jetpack Compose](https://developer.android.com/jetpack/compose) + Material 3.

Lien du projet : <https://github.com/vapordude/gemini-android-app>
