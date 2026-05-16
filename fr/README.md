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

Apache 2.0 — voir [`LICENSE`](../LICENSE).
