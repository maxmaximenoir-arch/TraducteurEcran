# Traducteur Écran (Android)

Traduit en français, par-dessus l'écran, tout le texte anglais affiché.
Pensé pour les webtoons : tu défiles, tu t'arrêtes, la traduction apparaît sur les bulles.

## 1. Construire l'appli (ordinateur, une seule fois)
1. Installe **Android Studio** (gratuit) : https://developer.android.com/studio
2. Dézippe ce dossier, puis dans Android Studio : **File > Open** → choisis le dossier `TraducteurEcran`.
3. Attends la fin de la synchronisation (barre en bas, quelques minutes la 1re fois). Si Android Studio propose une mise à jour de Gradle/AGP, tu peux accepter.
4. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
5. Clique sur **locate** : le fichier est `app/build/outputs/apk/debug/app-debug.apk`.
6. Envoie ce fichier sur ton téléphone (câble, Drive, mail…) et ouvre-le. Autorise « installer des applis inconnues » si demandé.

Variante sans Android Studio : mets le dossier dans un dépôt GitHub. Le fichier
`.github/workflows/build.yml` construit l'APK automatiquement (onglet **Actions** → dernier build → **Artifacts**).

## 2. Utilisation
1. Ouvre **Traducteur Écran** → **Démarrer la traduction**.
2. Autorise l'**affichage par-dessus les autres applis**, reviens, rappuie sur Démarrer.
3. Le dictionnaire se télécharge (1re fois, ~30 Mo). Ensuite tout marche **hors-ligne**.
4. Quand Android demande quoi partager, choisis **Écran entier**.
5. Ouvre ton webtoon et lis normalement.

**La bulle verte :** appui court = pause/reprise · appui long = fermer · glisser = déplacer.

## Traduction par IA (recommandé)
Dans l'appli, choisis **IA Gemini** et colle ta clé gratuite (lien « Créer ma clé gratuite »).
L'IA traduit chaque écran d'un coup, avec le contexte des répliques précédentes : ton, émotions,
argot et tutoiement sont adaptés, pas de mot à mot. Sans clé ou sans réseau, la traduction
hors-ligne prend le relais automatiquement.

## Bon à savoir
- En mode hors-ligne, la traduction est basique. Avec l'IA, elle est bien plus naturelle.
- Offre gratuite Gemini : nombre de requêtes par jour limité ; Google peut utiliser les textes envoyés pour améliorer ses services.
- Les traductions sont légèrement transparentes : Android l'impose pour que tu puisses continuer à défiler à travers.
- **Écran noir / rien ne se traduit ?** Certaines applis bloquent la capture d'écran. Dans ce cas, lis le webtoon dans Chrome plutôt que dans l'appli.
- Pensé pour le mode portrait.
