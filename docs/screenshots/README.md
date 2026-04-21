# Captures d'écran du README

Ce dossier contient les images référencées dans le [`README.md`](../../README.md)
principal.

## Images attendues

| Nom de fichier        | Description                                                                     | Utilisation dans le README |
|-----------------------|---------------------------------------------------------------------------------|----------------------------|
| `chat.png`            | Vue principale du chat avec un échange multi-tours (question + réponse Gemini). | Hero image en haut du README (`![Aperçu du chat]`). |
| `tools.png` *(optionnel)* | Une action outil en cours d'exécution (ex. `write_file` ou `run_shell_command`) avec le dialogue d'approbation visible. | Section Utilisation. |
| `markdown.png` *(optionnel)* | Une réponse du modèle qui rend un tableau GFM + un bloc de code + une liste. | Section Fonctionnalités (rendu Markdown). |
| `drawer.png` *(optionnel)* | Le menu latéral (burger) ouvert, montrant Saved chats / Export / Pick folder. | Section Utilisation → Export. |
| `settings.png` *(optionnel)* | La modale Settings avec les sections Account / Workspace / Model / Termux. | Section Utilisation → Configuration. |

## Format recommandé

- **PNG** de préférence (ou JPG pour les photos d'écran avec beaucoup de
  dégradé).
- **Largeur** : ~1080 px (résolution native d'un téléphone moderne). Les
  captures en mode portrait passent bien sur GitHub.
- **Fond sombre** de préférence — le thème de l'app est un dark neutre qui
  rend bien en hero image.

## Capture depuis un émulateur / device

```bash
# Sur un device branché en ADB
adb exec-out screencap -p > chat.png

# Ou depuis Android Studio : bouton « Screenshot » dans la toolbar
# de l'émulateur, puis déposer le PNG ici.
```

Une fois les images déposées, seule `chat.png` est obligatoire (référencée
en haut du README). Les autres sont mentionnées en option — je peux les
intégrer dans des sections dédiées du README si tu les fournis.
