# Gemini Android App - TODO List

## 🚀 Phase 1 : Initialisation & Architecture
- [x] Installer l'environnement de build (Java 21, Node.js)
- [x] Créer la structure multi-modules du projet
- [ ] Configurer Gradle (build.gradle.kts) pour chaque module
- [ ] Initialiser le projet Git interne

## 🌉 Phase 2 : Le Bridge (Option B)
- [x] Extraire et packager `@google/gemini-cli/core` via esbuild (16MB bundle)
- [x] Intégrer un moteur JS léger (QuickJS Android) dans `:core-bridge`
- [x] Créer les interfaces Kotlin pour appeler les fonctions du Core
- [ ] Valider l'exécution d'une commande réelle via le bridge (en cours)

## 🎨 Phase 3 : Interface Utilisateur (Jetpack Compose)
- [ ] Créer le thème visuel (Aesthetics, Dark Mode)
- [ ] Écran de Bienvenue / Login :
    - [ ] Sign in with Google
    - [ ] Clé API Gemini
    - [ ] Vertex AI Configuration
- [ ] Écran Principal (Chat) :
    - [ ] Liste des messages (bulles de chat)
    - [ ] Champ d'entrée avec bouton "+" pour le menu des commandes
- [ ] Menu Latéral (Drawer) :
    - [ ] Historique des sessions
    - [ ] Paramètres
    - [ ] Gestion du contexte (fichiers/dossiers)

## ⚙️ Phase 4 : Fonctionnalités Avancées
- [ ] Support complet des commandes CLI via menus (ex: `/reset` -> Bouton, `/skill` -> Menu)
- [ ] Gestionnaire de fichiers pour le contexte Gemini
- [ ] Notifications en cas de tâches de longue durée
- [ ] Persistance locale de l'historique (compatible avec le format CLI)

## 🧪 Phase 5 : Validation & Polissage
- [ ] Tests d'intégration Bridge <-> UI
- [ ] Optimisation des performances du moteur JS
- [ ] Build final de l'APK

---
*Note : Ce fichier est mis à jour dynamiquement à chaque étape de l'avancement.*
